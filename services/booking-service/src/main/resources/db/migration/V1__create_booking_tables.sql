create table events (
    id text primary key,
    name text not null,
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
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table seats (
    id uuid primary key,
    event_id text not null references events(id),
    seat_label text not null,
    status text not null check (status in ('AVAILABLE', 'HELD', 'BOOKED')),
    reservation_id uuid references reservations(id),
    hold_expires_at timestamptz,
    version bigint not null default 0,
    unique (event_id, seat_label)
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
