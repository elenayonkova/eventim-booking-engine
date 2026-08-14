alter table payment_intents drop constraint payment_intents_status_check;

alter table payment_intents
    add constraint payment_intents_status_check
    check (status in ('ACTIVE', 'CANCELLATION_PENDING', 'CANCELLED'));
