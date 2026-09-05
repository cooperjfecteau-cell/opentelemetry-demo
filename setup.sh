#!/usr/bin/env bash
# One-time sign-in for the Bluebox workshop. Each step opens a browser link.
set -uo pipefail
echo "== 1/3 Dynatrace (dtctl) =="
dtctl auth login --context onk --environment https://onk99503.sprint.apps.dynatracelabs.com
dtctl config use-context onk >/dev/null 2>&1 || true
dtctl doctor || true
echo
echo "== 2/3 Bluebox =="
bluebox setup || bluebox auth login
echo
echo "== 3/3 Claude Code =="
echo "Run 'claude' once and follow the login prompt. Then you are ready."
