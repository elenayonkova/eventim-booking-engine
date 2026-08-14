alter table refunds drop constraint refunds_status_check;

alter table refunds
    add constraint refunds_status_check
    check (status in ('PROCESSING', 'SUCCEEDED', 'FAILED'));

alter table refunds
    add column updated_at timestamptz not null default now();
