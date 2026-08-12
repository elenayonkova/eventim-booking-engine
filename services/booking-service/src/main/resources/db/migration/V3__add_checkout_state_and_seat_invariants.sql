alter table seats
    add column price_amount bigint not null default 5000 check (price_amount > 0),
    add column currency char(3) not null default 'EUR';

alter table reservations
    add column checkout_amount bigint,
    add column checkout_currency char(3),
    add column payment_method_fingerprint text,
    add column checkout_started_at timestamptz,
    add column payment_failure_reason text;

alter table reservations
    add constraint chk_checkout_payload_complete check (
        (checkout_amount is null
            and checkout_currency is null
            and payment_method_fingerprint is null
            and checkout_started_at is null)
        or
        (checkout_amount > 0
            and checkout_currency is not null
            and payment_method_fingerprint is not null
            and checkout_started_at is not null)
    );

alter table seats
    add constraint chk_seat_current_state check (
        (status = 'AVAILABLE' and reservation_id is null and hold_expires_at is null)
        or
        (status = 'HELD' and reservation_id is not null and hold_expires_at is not null)
        or
        (status = 'BOOKED' and reservation_id is not null and hold_expires_at is null)
    );
