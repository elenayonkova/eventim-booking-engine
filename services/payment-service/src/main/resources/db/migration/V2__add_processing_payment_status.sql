alter table payments drop constraint payments_status_check;

alter table payments
    add constraint payments_status_check
    check (status in ('PROCESSING', 'SUCCEEDED', 'FAILED', 'REFUNDED'));
