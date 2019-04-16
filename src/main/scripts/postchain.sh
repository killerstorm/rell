#!/bin/bash

scriptdir=`dirname $0`

java -cp $scriptdir/postchain-base-${postchain.version}-jar-with-dependencies.jar:$APPCP net.postchain.AppKt $@

