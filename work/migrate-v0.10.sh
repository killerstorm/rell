#!/bin/bash
#
# Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
#

exec "$(dirname $0)"/../target/rell-*.*.*-dist/postchain-node/migrate-v0.10.sh "$@"
