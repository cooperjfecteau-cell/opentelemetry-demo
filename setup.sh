#!/usr/bin/env bash
# One-time sign-in for the Bluebox workshop. Each step opens a browser link.
set -uo pipefail
echo "== 1/3 Dynatrace (dtctl) =="
# The facilitator sets DT_ENVIRONMENT_URL as a Codespaces secret; otherwise you are asked.
if [ -z "${DT_ENVIRONMENT_URL:-}" ]; then
  read -r -p "Dynatrace environment URL (https://<env>.apps.dynatrace.com): " DT_ENVIRONMENT_URL
fi
dtctl auth login --context workshop --environment "$DT_ENVIRONMENT_URL"
dtctl config use-context workshop >/dev/null 2>&1 || true
dtctl doctor || true
echo
echo "== 2/3 Bluebox =="
bluebox setup || bluebox auth login
echo
echo "== 3/3 Claude Code =="
echo "Run 'claude' once and follow the login prompt. Then you are ready."
