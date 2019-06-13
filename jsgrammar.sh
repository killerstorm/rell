#!/bin/bash
set -eu
${RELL_JAVA:-java} -cp target/rellr-*-jar-with-dependencies.jar net.postchain.rell.tools.grammar.JavascriptGrammarKt "$@"
