#!/usr/bin/env bash
set -euo pipefail

sudo apt update
sudo apt install -y curl git htop jq smartmontools mosquitto-clients openjdk-21-jdk maven nodejs npm

echo "Bootstrap complete."
