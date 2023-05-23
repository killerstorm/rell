#!/bin/bash
#
# Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
#

exec "$(dirname $0)"/../rell-tools/target/rell-tools-*.*.*-dist/postchain-node/multigen.sh "$@"
