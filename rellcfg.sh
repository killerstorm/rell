#!/bin/bash
exec "$(dirname $0)"/target/rell-*.*.*-dist/postchain-node/rellcfg.sh "$@"
