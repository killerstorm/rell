#!/bin/bash
set -eu
$(dirname $0)/query.sh '{"type":"error_q","e":"'$1'"}' | jq -r '.error'
