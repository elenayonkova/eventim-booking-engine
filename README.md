# Eventim High-Concurrency Booking Engine

This project is a ticket booking system built with Java 17, Spring Boot,
PostgreSQL, Kubernetes kind, and Carvel.

The main goal is to protect seats and payments when many customers use the
system at the same time. The system must not sell one seat twice or charge one
reservation twice.

## What is included

- A customer can hold one or more seats for a short time.
- A multi-seat request holds all requested seats or none of them.
- PostgreSQL row locks prevent two customers from holding the same seat.
- Expired holds are released automatically.
- The server calculates the price. It does not trust a price sent by a client.
- Payment and refund requests are idempotent. This means a safe retry does not
  create a second payment or refund.
- The system can recover when a payment response is delayed or lost.
- If payment succeeds but booking cannot finish, the system requests a refund.
- Flyway manages database changes, and database constraints block invalid seat
  states.
- Docker Compose provides a quick local setup.
- Carvel and kind provide a local Kubernetes setup with two copies of each
  service, health checks, resource limits, and persistent PostgreSQL storage.
- Integration tests cover concurrent reservations and payment edge cases.

## Architecture

```mermaid
flowchart LR
  Client["Client"] --> K8s["Kubernetes Service"]
  K8s --> Booking["Booking Service ×2"]
  Booking --> BookingDb["PostgreSQL booking schema"]
  Booking --> Payment["Payment Service ×2"]
  Payment --> PaymentDb["PostgreSQL payment schema"]
  Booking -. "check an unknown payment result" .-> Payment
```

The Booking Service and Payment Service do not keep important state in memory.
PostgreSQL is the source of truth. Because of this, a request can go to any
service instance and the same rules still apply.

### How seat reservations stay correct

The Booking Service locks requested seat rows in a fixed order. It creates a
reservation only when every requested seat exists and is `AVAILABLE`.

For example, if a customer asks for seats A-1 and A-2 but A-2 is already held,
the request fails. A-1 is not held on its own. This prevents partial bookings
and also reduces the risk of database deadlocks.

Expired reservations are processed in small batches. `FOR UPDATE SKIP LOCKED`
allows more than one Booking Service instance to run this work safely.

### Reservation lifecycle

The reservation status records the durable checkout decision. Seat state is
updated in the same transaction as each reservation transition.

```mermaid
stateDiagram-v2
  [*] --> HELD: reservation created
  HELD --> EXPIRED: hold expires
  HELD --> PAYMENT_PENDING: checkout begins
  PAYMENT_PENDING --> PAYMENT_PENDING: payment is still processing
  PAYMENT_PENDING --> BOOKED: matching payment succeeds
  PAYMENT_PENDING --> PAYMENT_FAILED: payment fails or cancellation is confirmed
  PAYMENT_PENDING --> REFUND_REQUIRED: successful payment cannot be applied safely
  PAYMENT_PENDING --> REFUNDED: payment was already refunded
  REFUND_REQUIRED --> REFUND_REQUIRED: refund is unresolved
  REFUND_REQUIRED --> REFUNDED: refund succeeds
  EXPIRED --> [*]
  BOOKED --> [*]
  PAYMENT_FAILED --> [*]
  REFUNDED --> [*]
```

`BOOKED` moves the held seats to `BOOKED`. Expiry or payment failure releases
them to `AVAILABLE`. A successful payment that cannot be applied safely enters
`REFUND_REQUIRED` and releases the seats before the refund call; `REFUNDED`
records that the financial recovery completed.

### How checkout stays correct

The Booking Service does not keep a database transaction open while it waits for
the Payment Service, and the Payment Service does not keep one open while it
waits for the provider. A first checkout follows these transaction boundaries:

```mermaid
sequenceDiagram
  actor Client
  participant Booking as Booking Service
  participant Payment as Payment Service
  participant Provider as Payment Provider

  Client->>Booking: POST /v1/checkout
  Booking->>Booking: DB tx: save PAYMENT_PENDING
  Booking->>Payment: Create or return payment
  Payment->>Payment: DB tx: save PROCESSING
  Note over Payment,Provider: The provider call runs after the commit
  Payment->>Provider: Charge
  Provider-->>Payment: Succeeded or failed
  Payment->>Payment: DB tx: save final status
  Payment-->>Booking: Payment result

  alt Matching payment succeeded
    Booking->>Booking: DB tx: book seats and mark BOOKED
    Booking-->>Client: BOOKED
  else Payment failed
    Booking->>Booking: DB tx: mark PAYMENT_FAILED and release seats
    Booking-->>Client: PAYMENT_FAILED
  else Successful payment cannot be applied safely
    Booking->>Booking: DB tx: mark REFUND_REQUIRED and release seats
    Note over Booking,Payment: Continue with the refund flow below
  end
```

If a successful charge cannot be applied safely, refund recovery follows the
same durable start, provider call, and completion pattern:

```mermaid
sequenceDiagram
  actor Client
  participant Booking as Booking Service
  participant Payment as Payment Service
  participant Provider as Payment Provider

  Booking->>Payment: Refund reservation
  Payment->>Payment: DB tx: save PROCESSING refund
  Note over Payment,Provider: The provider call runs after the commit
  Payment->>Provider: Refund
  Provider-->>Payment: Succeeded or failed
  Payment->>Payment: DB tx: save final refund status
  Payment-->>Booking: Refund result

  alt Refund succeeded
    Booking->>Booking: DB tx: mark REFUNDED
    Booking-->>Client: REFUNDED
  else Refund failed or is unresolved
    Booking->>Booking: Keep REFUND_REQUIRED
    Booking-->>Client: 503, retry safely
  end
```

The reservation ID is used as the payment idempotency key. If the same checkout
is sent again, the Payment Service returns the existing payment. It does not
create another charge.

If the payment response is lost or takes too long, the Booking Service keeps the
reservation in `PAYMENT_PENDING`. The client can retry safely, and a background
job follows this recovery flow:

```mermaid
flowchart TD
  Pending["Reservation PAYMENT_PENDING"] --> Lookup{"Payment lookup result?"}
  Lookup -->|PROCESSING| Keep["Keep PAYMENT_PENDING for the next sweep"]
  Lookup -->|Final result| Apply["Verify and apply result"]
  Lookup -->|404| Timeout{"Reconciliation timeout elapsed?"}
  Timeout -->|No| Keep
  Timeout -->|Yes| Cancel["Cancel reservation-scoped intent"]

  Cancel --> Exists{"Payment exists when cancellation locks the intent?"}
  Exists -->|No| Tombstone["Save durable CANCELLED tombstone"]
  Tombstone --> Failed["Mark PAYMENT_FAILED and release seats"]

  Exists -->|Started during race| CancelPending["Save CANCELLATION_PENDING"]
  CancelPending --> Provider["Provider cancels or resolves the charge"]
  Provider -->|Cancellation wins| Cancelled["Payment FAILED and intent CANCELLED"]
  Cancelled --> Failed
  Provider -->|Charge wins| Succeeded["Payment SUCCEEDED and intent ACTIVE"]
  Succeeded --> Apply
```

Before applying any recovered result, the Booking Service verifies the amount,
currency, and payment-token fingerprint against the stored checkout. A
successful mismatched payment is refunded instead of booking seats. The durable
cancellation handshake prevents a late payment from succeeding after its seats
have been released.

The Payment Service saves a `PROCESSING` payment or refund before the simulated
provider delay, then applies the final result in a separate transaction.
Unique constraints on `reservation_id` and `INSERT ... ON CONFLICT` collapse
concurrent payment or refund retries into one durable record. A retry with a
different amount, currency, or payment token is rejected.

### Seat and reservation relationship

`reservation_seats` is the main many-to-many table. It records which seats
belong to each reservation.

`seats.reservation_id` also stores the reservation that currently owns a seat.
This is intentional duplication. It makes the most important availability
checks simple and fast.

Both values are changed in the same database transaction. Database constraints
also enforce these rules:

- `AVAILABLE`: no reservation and no expiry time.
- `HELD`: a reservation and an expiry time are present.
- `BOOKED`: a reservation is present, but there is no expiry time.

`reservation_seats` keeps the reservation history. `seats.reservation_id` shows
only the current owner of the seat.

## API

Booking Service (`:8080`):

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/v1/events/{eventId}/seats` | Show current seat availability and release expired holds for the event |
| `POST` | `/v1/reservations` | Hold one or more seats |
| `POST` | `/v1/checkout` | Pay for and complete a reservation |
| `GET` | `/actuator/health` | Check service health |

Payment Service (`:8081`, internal in Kubernetes):

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/v1/payments` | Create a payment or return the existing payment |
| `GET` | `/v1/payments/by-reservation/{reservationId}` | Find a payment during recovery |
| `POST` | `/v1/payments/cancellations` | Prevent a late payment or return the payment that won the race |
| `POST` | `/v1/refunds` | Create a refund or return the existing refund |
| `GET` | `/actuator/health` | Check service health |

Checkout forwards the following headers only when creating a payment. Direct
calls to the Payment Service accept the same headers on payment creation,
cancellation, and refund creation while `PAYMENT_SIMULATION_ENABLED` is true:

- `X-Simulate-Delay-Ms`: delay the simulated provider response by up to 60,000 ms.
- `X-Simulate-Failure: true`: complete a payment or refund as failed. For a
  cancellation, it simulates the charge winning the race, so the payment
  completes as `SUCCEEDED`.

## Run with Docker Compose

You need Docker Desktop with Docker Compose.

```bash
docker compose up --build
```

Example requests:

```bash
curl http://localhost:8080/v1/events/event-1/seats

curl -X POST http://localhost:8080/v1/reservations \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"event-1","seatIds":["A-1","A-2"]}'

curl -X POST http://localhost:8080/v1/checkout \
  -H 'Content-Type: application/json' \
  -d '{"reservationId":"REPLACE_ME","paymentMethodToken":"tok_example"}'
```

Stop the services without deleting the database data:

```bash
docker compose down
```

## Run on kind with Carvel

You need Docker Desktop, `kubectl`, `kind`, `ytt`, `kbld`, `kapp`, and `kctrl`.

Run:

```bash
./scripts/setup.sh
```

The script:

1. Creates or reuses the `eventim` kind cluster.
2. Installs kapp-controller `v0.60.1`.
3. Builds the service images with kbld.
4. Loads the images into kind.
5. Creates and installs the Carvel package.
6. Waits until the services are ready.

Open the Booking Service on your computer:

```bash
kubectl port-forward --namespace eventim service/booking-service 8080:8080
```

Check the installation:

```bash
kctrl package installed get \
  --package-install eventim-booking-engine \
  --namespace eventim-install

kapp list --namespace eventim
kubectl get pods --namespace eventim
```

The local installer uses cluster-admin permission because it creates a
namespace and all required resources in a disposable kind cluster. A production
setup should use smaller, pre-created permissions.

## Tests

Docker must be running. The tests use Testcontainers and PostgreSQL 17, and the
build fails rather than silently skipping the suites when Docker is unavailable.

```bash
mvn test
```

The tests check that:

- only one concurrent request can win the same seat;
- an overlapping multi-seat request does not create a partial hold;
- expired holds release their seats;
- successful, failed, repeated, and delayed checkouts work correctly;
- concurrent payment requests create only one payment;
- the same payment key cannot be reused with different payment details;
- mismatched recovered payments are refunded instead of booking seats;
- cancellation tombstones prevent late payments after seats are released;
- refund responses must match the stored reservation and payment;
- stale recovery cannot be overwritten by late payment completion; and
- excessive simulated delays are rejected before work starts.

## Confirmed exercise scope

The following points were confirmed after the assignment questions were sent:

- Customers may remain anonymous. Authentication and reservation ownership are
  not required.
- The required Payment Service is the simulated Java service in this repository.
  An external payment provider is not required.
- A reproducible local deployment with kind, Carvel, and `scripts/setup.sh` is
  enough. Public hosting is not required.
- There is no fixed throughput target. The important requirement is safe
  concurrent behavior without race conditions, double bookings, or inventory
  leaks.
- Database partitioning is not required.

The implementation therefore focuses on concurrency, payment idempotency and
recovery, automated tests, and a reproducible local deployment.

