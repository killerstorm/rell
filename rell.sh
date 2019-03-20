#!/bin/bash
set -eu

D="$(dirname $0)"

J="$D/target/rellr-0.7.0-console.jar"

if [[ ! -f "$J" ]]; then
    >&2 echo "File not found: '$J'"
    exit 1
fi

java -cp "$J" net.postchain.rell.RellCLIKt "$@"
