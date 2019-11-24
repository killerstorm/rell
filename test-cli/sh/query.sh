#!/bin/bash
set -eu
REQ="${1?"Specify request body!"}"
URL="http://localhost:7740/query/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
curl -s "$URL" -X POST -d "$REQ"
