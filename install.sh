#!/bin/sh
# Convenience entry point:
#   sh /shared/mini-mvn/install.sh [--force]
# Equivalent to:
#   MINI_MVN_LINK_DIR=/bin sh /shared/mini-mvn/scripts/install-cli.sh [--force]
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
MINI_MVN_LINK_DIR=${MINI_MVN_LINK_DIR:-/bin}
export MINI_MVN_LINK_DIR

exec sh "$root/scripts/install-cli.sh" "$@"
