create table payments (
    id uuid primary key,
    reservation_id uuid not null unique,
    amount bigint not null check (amount > 0),
    currency char(3) not null,
    payment_method_fingerprint text not null,
    status text not null check (status in ('SUCCEEDED', 'FAILED', 'REFUNDED')),
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table refunds (
    id uuid primary key,
    reservation_id uuid not null unique,
    payment_id uuid not null references payments(id),
    status text not null check (status in ('SUCCEEDED', 'FAILED')),
    created_at timestamptz not null default now()
);

create index idx_payments_reservation_id on payments(reservation_id);
create index idx_refunds_reservation_id on refunds(reservation_id);
