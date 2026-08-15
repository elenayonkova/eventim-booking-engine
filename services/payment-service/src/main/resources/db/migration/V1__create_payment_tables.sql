create table payments (
    id uuid primary key,
    reservation_id uuid not null unique,
    amount bigint,
    currency char(3),
    payment_method_fingerprint text,
    status text not null check (
        status in (
            'PROCESSING',
            'CANCELLATION_PENDING',
            'UNKNOWN',
            'CANCELLED',
            'SUCCEEDED',
            'FAILED',
            'REFUNDED'
        )
    ),
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_payment_payload check (
        (
            amount is null
            and currency is null
            and payment_method_fingerprint is null
            and status = 'CANCELLED'
        )
        or
        (
            amount > 0
            and currency is not null
            and payment_method_fingerprint is not null
        )
    )
);

create table refunds (
    id uuid primary key,
    reservation_id uuid not null unique,
    payment_id uuid not null references payments(id),
    status text not null check (status in ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    attempt integer not null default 1 check (attempt > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_payments_processing_updated_at
    on payments(updated_at, reservation_id)
    where status in ('PROCESSING', 'CANCELLATION_PENDING');

create index idx_refunds_processing_updated_at
    on refunds(updated_at, reservation_id)
    where status = 'PROCESSING';
