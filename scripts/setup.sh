#!/usr/bin/env bash
set -euo pipefail

required_tools=(docker kubectl kind ytt kbld kapp kctrl)

for tool in "${required_tools[@]}"; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Missing required tool: ${tool}" >&2
    exit 1
  fi
done

echo "Prerequisites look good."
echo "Deployment automation will be added after the Kubernetes/Carvel manifests are implemented."
