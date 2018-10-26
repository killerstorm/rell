#!/bin/sh

set -e

scriptdir=`dirname "$BASH_SOURCE"`
outputname="$PWD/$1".sql
if [ $1 = "-h" ]
  then
    echo "compiler"
    echo "args[0] = input file (Rell file to compile)"
    echo "args[1] = OPTIONAL field, name of the file in output (SQL file) default is input name"
    exit 1
fi



if [ ! -z "$2" ]
  then
    outputname="$2"
fi

java -cp "$scriptdir/rellr.jar" net.postchain.rell.SqlgenCLIKt "$1" "$outputname"

echo ""
echo ""

echo "Chromaway - RELL"

echo ""

echo Output file "$outputname"
