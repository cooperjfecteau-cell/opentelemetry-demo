#!/usr/bin/env bash
# Installs the three CLIs participants use. Logins happen later in ./setup.sh
# because they need a browser round-trip.
set -euo pipefail
curl -fsSL https://raw.githubusercontent.com/dynatrace-oss/dtctl/main/install.sh | sh
npm install -g @anthropic-ai/claude-code
# The Bluebox installer wants to run its interactive setup; skip that here.
curl -fsSL https://app.bluebox.ai/install.sh -o /tmp/bluebox-install.sh
BLUEBOX_NO_AGENT=1 bash /tmp/bluebox-install.sh < /dev/null || true
echo
echo "Tools installed. Run ./setup.sh to sign in to Dynatrace, Bluebox, and Claude Code."
