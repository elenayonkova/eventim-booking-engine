# Eventim High-Concurrency Booking Engine

A Java 17, Spring Boot, and PostgreSQL exercise in concurrent seat allocation,
idempotent payments, and recovery from interrupted calls.

## Architecture

The stateless Booking Service owns reservations and seat inventory. The
stateless Payment Service owns payments, refunds, and a simulated provider.
Each service uses its own PostgreSQL schema.

```text
Client → Booking Service → Payment Service → Simulated provider
              ↓                 ↓
        booking schema     payment schema
```

The main guarantees are:

- Multi-seat reservations succeed completely or not at all.
- Stable row-lock ordering prevents double booking and reduces deadlocks.
- Checkout never holds a database transaction during a provider call.
- Payments and refunds are idempotent per reservation.
- Scheduled jobs recover expired holds and interrupted payment flows.

## API

| Service | Method | Path | Purpose |
| --- | --- | --- | --- |
| Booking | `GET` | `/v1/events/{eventId}/seats` | List seats |
| Booking | `POST` | `/v1/reservations` | Hold seats for five minutes |
| Booking | `POST` | `/v1/checkout` | Pay for a reservation |
| Payment | `POST` | `/v1/payments` | Create or return a payment |
| Payment | `GET` | `/v1/payments/{reservationId}` | Read payment status |
| Payment | `POST` | `/v1/refunds` | Create, return, or retry a refund |
| Both | `GET` | `/actuator/health` | Health check |

The provider is always simulated. Add `X-Simulate-Delay-Ms` (maximum 60,000)
or `X-Simulate-Failure: true` to payment or refund requests to demonstrate
latency and failures.

## Run locally

Requirements: Docker Desktop, Docker Compose, `curl`, `jq`, and `uuidgen`.

```bash
docker compose up --build
./scripts/smoke-test.sh
```

The smoke test covers health, atomic and conflicting holds, successful and
failed checkout, idempotent retries, seat release, and refund retry.

The V1 seed creates `event-1` with twenty EUR 50.00 seats. A short manual flow:

```bash
curl -sS http://localhost:8080/v1/events/event-1/seats | jq

reservation=$(curl -sS --fail-with-body \
  -X POST http://localhost:8080/v1/reservations \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"event-1","seatIds":["A-1"]}')

reservation_id=$(jq -r '.reservationId' <<<"$reservation")

curl -sS --fail-with-body \
  -X POST http://localhost:8080/v1/checkout \
  -H 'Content-Type: application/json' \
  -d "{\"reservationId\":\"$reservation_id\",\"paymentMethodToken\":\"tok_demo\"}" \
  | jq
```

Stop the services without deleting the database:

```bash
docker compose down
```

## Run on kind with Carvel

Requirements: Docker Desktop, `kubectl`, `kind`, `ytt`, `kbld`, `kapp`,
`kctrl`, and `jq`.

```bash
./scripts/setup.sh
kubectl port-forward --namespace eventim service/booking-service 8080:8080
```

The script creates or reuses the `eventim` kind cluster, installs
kapp-controller, builds and loads both service images, installs the Carvel
package, and waits for the workloads.

```bash
kctrl package installed get \
  --package-install eventim-booking-engine \
  --namespace eventim-install
kubectl get pods --namespace eventim
```

## Tests

Docker must be running because integration tests use PostgreSQL 17 through
Testcontainers.

```bash
mvn clean test
```

The suite covers concurrent allocation, expiry, price snapshots, checkout
states, payment and refund idempotency, attempt recovery, and late-completion
fencing.

## Design decisions and scope

- `reservation_seats` keeps seat history, while `seats.reservation_id` shows the
  current owner. Both are updated in one transaction.
- Payment IDs are stored before provider calls and used as idempotency keys.
  Stale attempts are retried, and late results cannot overwrite newer ones.
- Booking Service keeps checking old `PAYMENT_PENDING` reservations. If no
  payment appears, it releases the seats but keeps the reservation pending so a
  late successful charge can still be detected and refunded.
- The simulated payment token is stored only while recovery is needed and is
  cleared when processing finishes.
- The provider is always simulated, and artificial delays block the request
  thread. A real integration would use HTTPS, strict timeouts, encrypted
  provider references, and bounded concurrency.
- Customers, authentication, public hosting, database partitioning, and a fixed
  throughput target are out of scope. Reservations therefore have no
  customer-scoped idempotency key.

Database schemas:

- [Booking V1](services/booking-service/src/main/resources/db/migration/V1__create_booking_tables.sql)
- [Payment V1](services/payment-service/src/main/resources/db/migration/V1__create_payment_tables.sql)

## Repository layout

- `services/` — Booking and Payment Spring Boot services
- `packages/` — Carvel package and Kubernetes workloads
- `installs/` — Package installation resources
- `scripts/` — local setup and smoke test

## Demo

[Watch the recorded demo](demo.mp4).
