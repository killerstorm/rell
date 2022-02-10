#!/bin/bash
exec "$(dirname $0)"/target/rell-*.*.*-dist/postchain-node/migrate-v0.10.sh "$@"
