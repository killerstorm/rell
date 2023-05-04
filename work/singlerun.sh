#!/bin/bash
#
# Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
#

exec "$(dirname $0)"/../target/rell-*.*.*-dist/postchain-node/singlerun.sh "$@"
