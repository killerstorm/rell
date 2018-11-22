package net.postchain.rell

import net.postchain.rell.parser.CtUtils
import net.postchain.rell.sql.gensql
import java.io.File

fun main(args: Array<String>) {
    val text = File(args[0]).readText(Charsets.UTF_8)

    val compiled = gensql(CtUtils.parse(text).compile(), true)

    var path = args[0] + ".sql"
    if (args.size > 1) {
        path = args[1]
    }

    File(path).writeText(compiled)
}