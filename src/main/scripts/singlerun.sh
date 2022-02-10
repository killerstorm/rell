#!/bin/bash
set -eu
D=`dirname "${BASH_SOURCE[0]}"`
exec "$D/javarun.sh" net.postchain.rell.tools.PostchainAppLaunchKt "$@"
