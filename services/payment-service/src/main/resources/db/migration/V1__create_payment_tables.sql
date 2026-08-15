create table payments (
    id uuid primary key,
    reservation_id uuid not null unique,
    amount bigint not null check (amount > 0),
    currency char(3) not null,
    payment_method_token varchar(256),
    payment_method_token_digest varchar(64) not null,
    status text not null check (
        status in (
            'PROCESSING',
            'SUCCEEDED',
            'FAILED',
            'REFUNDED'
        )
    ),
    attempt integer not null default 1 check (attempt > 0),
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

comment on column payments.payment_method_token is
    'Temporary simulated provider token retained only while payment recovery is pending';

create index idx_payments_processing_updated_at
    on payments(updated_at, id)
    where status = 'PROCESSING';

create table refunds (
    id uuid primary key,
    reservation_id uuid not null unique,
    payment_id uuid not null references payments(id),
    status text not null check (status in ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    attempt integer not null default 1 check (attempt > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_refunds_processing_updated_at
    on refunds(updated_at, reservation_id)
    where status = 'PROCESSING';
