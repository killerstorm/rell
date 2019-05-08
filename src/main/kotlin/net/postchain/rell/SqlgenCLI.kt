package net.postchain.rell

import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.sql.genSql
import java.io.File

fun main(args: Array<String>) {
    val module = RellCliUtils.compileModule(args[0])

    val sqlCtx = Rt_SqlContext.createNoExternalChains(module, Rt_ChainSqlMapping(0))
    val compiled = genSql(sqlCtx, false, true)

    var path = args[0] + ".sql"
    if (args.size > 1) {
        path = args[1]
    }

    File(path).writeText(compiled)
}
