#!/usr/bin/env bash
set -euo pipefail

DEVICE="${1:-/dev/nvme0}"

echo "Checking SMART data for $DEVICE"
sudo smartctl -a "$DEVICE"
