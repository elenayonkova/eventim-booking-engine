create table events (
    id text primary key,
    name text not null,
    currency char(3) not null,
    created_at timestamptz not null default now()
);

create table reservations (
    id uuid primary key,
    event_id text not null references events(id),
    status text not null check (
        status in (
            'HELD',
            'PAYMENT_PENDING',
            'BOOKED',
            'EXPIRED',
            'PAYMENT_FAILED',
            'REFUND_REQUIRED',
            'REFUNDED'
        )
    ),
    expires_at timestamptz not null,
    payment_id uuid,
    checkout_amount bigint not null check (checkout_amount > 0),
    checkout_currency char(3) not null,
    payment_method_fingerprint text,
    checkout_started_at timestamptz,
    payment_failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_reservation_checkout_state check (
        (
            status in ('HELD', 'EXPIRED')
            and payment_method_fingerprint is null
            and checkout_started_at is null
        )
        or
        (
            status in ('PAYMENT_PENDING', 'BOOKED', 'PAYMENT_FAILED', 'REFUND_REQUIRED', 'REFUNDED')
            and payment_method_fingerprint is not null
            and checkout_started_at is not null
        )
    )
);

create table seats (
    id uuid primary key,
    event_id text not null references events(id),
    seat_label text not null,
    price_amount bigint not null check (price_amount > 0),
    status text not null check (status in ('AVAILABLE', 'HELD', 'BOOKED')),
    reservation_id uuid references reservations(id),
    hold_expires_at timestamptz,
    unique (event_id, seat_label),
    constraint chk_seat_current_state check (
        (status = 'AVAILABLE' and reservation_id is null and hold_expires_at is null)
        or
        (status = 'HELD' and reservation_id is not null and hold_expires_at is not null)
        or
        (status = 'BOOKED' and reservation_id is not null and hold_expires_at is null)
    )
);

create table reservation_seats (
    reservation_id uuid not null references reservations(id) on delete cascade,
    seat_id uuid not null references seats(id),
    primary key (reservation_id, seat_id)
);

create index idx_seats_event_status on seats(event_id, status);
create index idx_seats_reservation_id on seats(reservation_id);
create index idx_reservations_status_expires_at on reservations(status, expires_at);
create index idx_reservations_event_id on reservations(event_id);

create index idx_reservations_payment_reconciliation
    on reservations(updated_at, id)
    where status = 'PAYMENT_PENDING';

create index idx_reservations_refund_reconciliation
    on reservations(updated_at, id)
    where status = 'REFUND_REQUIRED';

insert into events (id, name, currency)
values ('event-1', 'Eventim Arena Night', 'EUR');

insert into seats (id, event_id, seat_label, price_amount, status)
values
    ('00000000-0000-0000-0000-000000000101', 'event-1', 'A-1', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000102', 'event-1', 'A-2', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000103', 'event-1', 'A-3', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000104', 'event-1', 'A-4', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000105', 'event-1', 'A-5', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000106', 'event-1', 'A-6', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000107', 'event-1', 'A-7', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000108', 'event-1', 'A-8', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000109', 'event-1', 'A-9', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000110', 'event-1', 'A-10', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000201', 'event-1', 'B-1', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000202', 'event-1', 'B-2', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000203', 'event-1', 'B-3', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000204', 'event-1', 'B-4', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000205', 'event-1', 'B-5', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000206', 'event-1', 'B-6', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000207', 'event-1', 'B-7', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000208', 'event-1', 'B-8', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000209', 'event-1', 'B-9', 5000, 'AVAILABLE'),
    ('00000000-0000-0000-0000-000000000210', 'event-1', 'B-10', 5000, 'AVAILABLE');
