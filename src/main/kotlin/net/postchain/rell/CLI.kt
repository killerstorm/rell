package net.postchain.rell

import net.postchain.rell.model.makeModule
import net.postchain.rell.parser.parseRellCode
import net.postchain.rell.sqlgen.gensql
import java.io.File

fun main(args: Array<String>) {
    val text = File(args[0]).readText(Charsets.UTF_8)

    val compiled = gensql(makeModule(parseRellCode(text)))

    var path = args[0] + ".sql"
    if (args.size > 1) {
        path = args[1]
    }

    File(path).writeText(compiled)
}