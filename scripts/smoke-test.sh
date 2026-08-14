#!/usr/bin/env bash
set -euo pipefail

booking_url="${BOOKING_URL:-http://localhost:8080}"
payment_url="${PAYMENT_URL:-http://localhost:8081}"
temporary_dir="$(mktemp -d /tmp/eventim-smoke.XXXXXX)"

cleanup() {
  rm -rf "${temporary_dir}"
}
trap cleanup EXIT

for tool in curl jq uuidgen; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Missing required tool: ${tool}" >&2
    exit 1
  fi
done

request() {
  local method="$1"
  local url="$2"
  local body=''
  shift 2
  if [[ "$#" -gt 0 ]]; then
    body="$1"
    shift
  fi

  if [[ -n "${body}" ]]; then
    curl --silent --show-error --fail-with-body \
      --request "${method}" \
      --header 'Content-Type: application/json' \
      "$@" \
      --data "${body}" \
      "${url}"
  else
    curl --silent --show-error --fail-with-body \
      --request "${method}" \
      "$@" \
      "${url}"
  fi
}

expect_json() {
  local json="$1"
  local expression="$2"
  local description="$3"

  if ! jq --exit-status "${expression}" >/dev/null <<<"${json}"; then
    echo "FAILED: ${description}" >&2
    jq . <<<"${json}" >&2
    exit 1
  fi
  echo "PASS: ${description}"
}

echo "Eventim Booking Engine curl smoke test"

booking_health="$(request GET "${booking_url}/actuator/health")"
payment_health="$(request GET "${payment_url}/actuator/health")"
expect_json "${booking_health}" '.status == "UP"' 'Booking Service is healthy'
expect_json "${payment_health}" '.status == "UP"' 'Payment Service is healthy'

inventory="$(request GET "${booking_url}/v1/events/event-1/seats")"
available_seats=($(jq --raw-output \
  '.seats[] | select(.status == "AVAILABLE") | .seatId' \
  <<<"${inventory}" | head -3))
if [[ "${#available_seats[@]}" -lt 3 ]]; then
  echo 'FAILED: at least three available seats are required; use a clean database' >&2
  exit 1
fi
first_seat="${available_seats[0]}"
second_seat="${available_seats[1]}"
failure_seat="${available_seats[2]}"

successful_reservation="$(request POST "${booking_url}/v1/reservations" "$(jq --null-input \
  --arg first "${first_seat}" \
  --arg second "${second_seat}" \
  '{eventId:"event-1",seatIds:[$first,$second]}')")"
expect_json "${successful_reservation}" \
  '.status == "HELD" and .amount == 10000 and .currency == "EUR"' \
  'A two-seat hold captures the authoritative EUR 100.00 price'
successful_reservation_id="$(jq --raw-output '.reservationId' <<<"${successful_reservation}")"

overlap_status="$(curl --silent --output "${temporary_dir}/overlap.json" --write-out '%{http_code}' \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "$(jq --null-input --arg seat "${first_seat}" \
    '{eventId:"event-1",seatIds:[$seat]}')" \
  "${booking_url}/v1/reservations")"
if [[ "${overlap_status}" != '409' ]]; then
  echo "FAILED: overlapping hold returned HTTP ${overlap_status}, expected 409" >&2
  exit 1
fi
echo 'PASS: an overlapping hold is rejected with HTTP 409'

checkout_body="$(jq --null-input \
  --arg reservationId "${successful_reservation_id}" \
  '{reservationId:$reservationId,paymentMethodToken:"tok_smoke_success"}')"
first_checkout="$(request POST "${booking_url}/v1/checkout" "${checkout_body}")"
second_checkout="$(request POST "${booking_url}/v1/checkout" "${checkout_body}")"
expect_json "${first_checkout}" '.status == "BOOKED" and .amount == 10000' \
  'Successful checkout books both seats'
successful_payment_id="$(jq --raw-output '.paymentId' <<<"${first_checkout}")"
expect_json "${second_checkout}" \
  ".status == \"BOOKED\" and .paymentId == \"${successful_payment_id}\"" \
  'Checkout retry returns the same payment without another charge'

failed_reservation="$(request POST "${booking_url}/v1/reservations" "$(jq --null-input \
  --arg seat "${failure_seat}" \
  '{eventId:"event-1",seatIds:[$seat]}')")"
failed_reservation_id="$(jq --raw-output '.reservationId' <<<"${failed_reservation}")"
failed_checkout="$(request POST "${booking_url}/v1/checkout" "$(jq --null-input \
  --arg reservationId "${failed_reservation_id}" \
  '{reservationId:$reservationId,paymentMethodToken:"tok_smoke_failure"}')" \
  --header 'X-Simulate-Failure: true')"
expect_json "${failed_checkout}" '.status == "PAYMENT_FAILED"' \
  'Failed payment moves the reservation to PAYMENT_FAILED'
inventory_after_failure="$(request GET "${booking_url}/v1/events/event-1/seats")"
expect_json "${inventory_after_failure}" \
  ".seats[] | select(.seatId == \"${failure_seat}\") | .status == \"AVAILABLE\"" \
  'Failed payment releases its seat'

tombstone_reservation_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
tombstone="$(request POST "${payment_url}/v1/payments/cancellations" "$(jq --null-input \
  --arg reservationId "${tombstone_reservation_id}" \
  '{reservationId:$reservationId}')")"
expect_json "${tombstone}" '.payment == null' \
  'Cancellation before payment creates a payloadless tombstone'
late_charge_status="$(curl --silent --output "${temporary_dir}/late-charge.json" --write-out '%{http_code}' \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "$(jq --null-input \
    --arg reservationId "${tombstone_reservation_id}" \
    '{reservationId:$reservationId,amount:5000,currency:"EUR",paymentMethodToken:"tok_too_late"}')" \
  "${payment_url}/v1/payments")"
if [[ "${late_charge_status}" != '409' ]]; then
  echo "FAILED: late charge returned HTTP ${late_charge_status}, expected 409" >&2
  exit 1
fi
echo 'PASS: a cancellation tombstone blocks a late charge with HTTP 409'

refund_reservation_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
refund_payment="$(request POST "${payment_url}/v1/payments" "$(jq --null-input \
  --arg reservationId "${refund_reservation_id}" \
  '{reservationId:$reservationId,amount:5000,currency:"EUR",paymentMethodToken:"tok_refund"}')")"
expect_json "${refund_payment}" '.status == "SUCCEEDED"' \
  'A direct provider payment succeeds for the refund scenario'
failed_refund="$(request POST "${payment_url}/v1/refunds" "$(jq --null-input \
  --arg reservationId "${refund_reservation_id}" \
  '{reservationId:$reservationId}')" \
  --header 'X-Simulate-Failure: true')"
retried_refund="$(request POST "${payment_url}/v1/refunds" "$(jq --null-input \
  --arg reservationId "${refund_reservation_id}" \
  '{reservationId:$reservationId}')")"
failed_refund_id="$(jq --raw-output '.refundId' <<<"${failed_refund}")"
expect_json "${failed_refund}" '.status == "FAILED"' \
  'The first refund attempt fails durably'
expect_json "${retried_refund}" \
  ".status == \"SUCCEEDED\" and .refundId == \"${failed_refund_id}\"" \
  'Refund retry succeeds with the same refund ID'
refunded_payment="$(request GET \
  "${payment_url}/v1/payments/by-reservation/${refund_reservation_id}")"
expect_json "${refunded_payment}" '.status == "REFUNDED"' \
  'Successful retry marks the payment REFUNDED'

echo 'All curl smoke tests passed.'
