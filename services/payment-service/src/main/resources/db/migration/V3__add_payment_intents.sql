create table payment_intents (
    reservation_id uuid primary key,
    status text not null check (status in ('ACTIVE', 'CANCELLED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

insert into payment_intents (reservation_id, status)
select reservation_id, 'ACTIVE'
from payments;

alter table payments
    add constraint fk_payments_payment_intent
    foreign key (reservation_id) references payment_intents(reservation_id);
