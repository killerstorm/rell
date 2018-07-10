package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import java.io.File

fun main(args: Array<String>) {
    val text = File(args[0]).readText(Charsets.UTF_8)

    val compiled = gensql(makeModule(S_Grammar.parseToEnd(text)))

    var path = args[0] + ".sql"
    if (args.size > 1) {
        path = args[1]
    }

    File(path).writeText(compiled)
}