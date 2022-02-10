#!/bin/bash
set -eu

MAIN_CLASS=${1?"Specify main class!"}
shift

D=`dirname "${BASH_SOURCE[0]}"`

JAR_FILE=`echo "$D"/lib/rell-*.*.*.jar`
CP="$JAR_FILE:$D/extra/*"

exec ${RELL_JAVA:-java} -cp "$CP" "$MAIN_CLASS" "$@"
