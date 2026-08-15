#!/usr/bin/env bash
set -euo pipefail

required_tools=(docker kubectl kind ytt kbld kapp kctrl jq)
cluster_name="${CLUSTER_NAME:-eventim}"
kind_context="kind-${cluster_name}"
booking_image="eventim/booking-service:dev"
payment_image="eventim/payment-service:dev"
package_file="$(mktemp /tmp/eventim-package.XXXXXX)"
rendered_workloads_file="$(mktemp /tmp/eventim-workloads.XXXXXX)"

cleanup() {
  rm -f "${package_file}" "${rendered_workloads_file}"
}
trap cleanup EXIT

for tool in "${required_tools[@]}"; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Missing required tool: ${tool}" >&2
    exit 1
  fi
done

if ! docker info >/dev/null 2>&1; then
  echo "Docker is installed but its daemon is not reachable. Start Docker Desktop and retry." >&2
  exit 1
fi

if ! kind get clusters | grep -Fxq "${cluster_name}"; then
  echo "Creating kind cluster ${cluster_name}..."
  kind create cluster --name "${cluster_name}" --wait 120s
else
  echo "Reusing kind cluster ${cluster_name}."
fi

kubectl config use-context "${kind_context}" >/dev/null

echo "Installing kapp-controller v0.60.1..."
kapp deploy \
  --yes \
  --app kapp-controller \
  --file https://github.com/carvel-dev/kapp-controller/releases/download/v0.60.1/release.yml
kubectl rollout status deployment/kapp-controller \
  --namespace kapp-controller \
  --timeout 180s

echo "Building application images through kbld..."
ytt -f packages/eventim-booking-engine/config \
  | kbld -f packages/eventim-booking-engine/kbld.yml -f - \
  >"${rendered_workloads_file}"

deployment_image() {
  local deployment_name="$1"
  local container_name="$2"

  ytt -f "${rendered_workloads_file}" -o json \
    | jq --slurp --raw-output \
      --arg deployment "${deployment_name}" \
      --arg container "${container_name}" \
      'first(
        .[]
        | select(.apiVersion == "apps/v1" and .kind == "Deployment")
        | select(.metadata.name == $deployment)
        | .spec.template.spec.containers[]
        | select(.name == $container)
        | .image
      ) // empty'
}

resolved_booking_image="$(deployment_image booking-service booking-service)"
resolved_payment_image="$(deployment_image payment-service payment-service)"

if [[ -z "${resolved_booking_image}" || -z "${resolved_payment_image}" ]]; then
  echo "kbld did not produce resolved application image references." >&2
  exit 1
fi

echo "Loading application images into kind..."
kind load docker-image \
  --name "${cluster_name}" \
  "${resolved_booking_image}" \
  "${resolved_payment_image}"

echo "Creating package installer service account and PackageInstall..."
ytt \
  -f installs/values-schema.yml \
  -f installs/eventim-booking-engine.yml \
  --data-value booking_image="${resolved_booking_image}" \
  --data-value payment_image="${resolved_payment_image}" \
  | kubectl apply -f -

echo "Creating versioned Carvel Package resources..."
ytt \
  -f packages/eventim-booking-engine/package-values.yml \
  -f packages/eventim-booking-engine/package-template.yml \
  --data-value-file schema=packages/eventim-booking-engine/config/schema.yml \
  --data-value-file workloads=packages/eventim-booking-engine/config/workloads.yml \
  >"${package_file}"
kubectl apply -f "${package_file}"

echo "Waiting for PackageInstall reconciliation..."
kctrl package installed kick \
  --package-install eventim-booking-engine \
  --namespace eventim-install \
  --wait=false \
  --tty=false \
  --yes
kubectl wait packageinstall/eventim-booking-engine \
  --namespace eventim-install \
  --for condition=ReconcileSucceeded \
  --timeout 10m

kubectl rollout status statefulset/postgres --namespace eventim --timeout 5m
kubectl rollout status deployment/payment-service --namespace eventim --timeout 5m
kubectl rollout status deployment/booking-service --namespace eventim --timeout 5m

echo
echo "Eventim Booking Engine is ready."
echo "Run: kubectl port-forward --namespace eventim service/booking-service 8080:8080"
echo "Health: curl http://localhost:8080/actuator/health"
