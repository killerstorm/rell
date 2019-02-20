#!/bin/bash
set -eu

D="$(dirname $0)"

java -cp "$D/target/rellr-0.7.0-console.jar" net.postchain.rell.RellCLIKt "$@"
