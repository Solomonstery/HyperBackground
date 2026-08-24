#!/usr/bin/env bash
set -euo pipefail

# Test releases use the exact same package id, private key and Release build chain as
# stable releases. The `-test` versionName is what makes GitHub Actions publish a
# Pre-release; there is intentionally no applicationIdSuffix or debug-signed canary.
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$PROJECT_DIR/build.sh"
