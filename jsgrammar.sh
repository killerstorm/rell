#!/bin/bash
set -eu
exec ${RELL_JAVA:-java} -cp target/rell-*-jar-with-dependencies.jar net.postchain.rell.tools.grammar.JavascriptGrammarKt "$@"
