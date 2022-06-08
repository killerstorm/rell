#!/bin/bash
exec "$(dirname $0)"/target/rell-*.*.*-dist/postchain-node/multirun.sh "$@"
