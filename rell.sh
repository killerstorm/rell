#!/bin/bash
set -eu

D="$(dirname $(readlink -f $0))"

java -cp "$D/target/rellr-1.0-SNAPSHOT-jar-with-dependencies.jar" net.postchain.rell.RellCLIKt "$@"
