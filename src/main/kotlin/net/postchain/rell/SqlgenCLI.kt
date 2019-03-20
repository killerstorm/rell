package net.postchain.rell

import net.postchain.rell.parser.C_EmptyIncludeDir
import net.postchain.rell.parser.C_IncludeResolver
import net.postchain.rell.parser.C_Parser
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.sql.genSql
import java.io.File

fun main(args: Array<String>) {
    val includeResolver = C_IncludeResolver(C_EmptyIncludeDir)

    val file = File(args[0])
    val text = file.readText(Charsets.UTF_8)
    val module = C_Parser.parse(file.name, text).compile(file.name, includeResolver, true)

    val sqlCtx = Rt_SqlContext.createNoExternalChains(module, Rt_ChainSqlMapping(0))
    val compiled = genSql(sqlCtx, false, true)

    var path = args[0] + ".sql"
    if (args.size > 1) {
        path = args[1]
    }

    File(path).writeText(compiled)
}
