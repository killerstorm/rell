#!/bin/bash
set -eu

cd "$(dirname $0)"
pytest-3 -v -s "$@"
