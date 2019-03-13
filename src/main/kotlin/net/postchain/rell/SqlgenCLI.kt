package net.postchain.rell

import net.postchain.rell.parser.C_Utils
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.sql.genSql
import java.io.File

fun main(args: Array<String>) {
    val text = File(args[0]).readText(Charsets.UTF_8)
    val module = C_Utils.parse(text).compile(true)
    val sqlCtx = Rt_SqlContext.createNoExternalChains(module, Rt_ChainSqlMapping(0))
    val compiled = genSql(sqlCtx, false, true)

    var path = args[0] + ".sql"
    if (args.size > 1) {
        path = args[1]
    }

    File(path).writeText(compiled)
}
