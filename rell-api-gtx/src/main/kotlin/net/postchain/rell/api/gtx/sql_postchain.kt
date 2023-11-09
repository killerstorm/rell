/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.gtx

import net.postchain.base.BaseEContext
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.base.withWriteConnection
import net.postchain.common.BlockchainRid
import net.postchain.core.EContext
import net.postchain.core.Storage
import net.postchain.rell.base.runtime.Rt_ExecutionContext
import net.postchain.rell.base.sql.*
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.gtx.PostchainBaseUtils
import java.sql.Connection

class PostchainStorageSqlManager(private val storage: Storage, logging: Boolean): SqlManager() {
    override val hasConnection = true

    private val conLogger = SqlConnectionLogger(logging)

    init {
        storage.withReadConnection { ctx ->
            DatabaseAccess.of(ctx).checkCollation(ctx.conn, suppressError = false)
        }
    }

    override fun <T> execute0(tx: Boolean, code: (SqlExecutor) -> T): T {
        val res = if (tx) {
            storage.withWriteConnection { ctx ->
                executeWithConnection(ctx.conn, false, code)
            }
        } else {
            storage.withReadConnection { ctx ->
                executeWithConnection(ctx.conn, ctx.conn.autoCommit, code)
            }
        }
        return res
    }

    private fun <T> executeWithConnection(con: Connection, expectedAutoCommit: Boolean, code: (SqlExecutor) -> T): T {
        checkEquals(con.autoCommit, expectedAutoCommit)
        val sqlExec = ConnectionSqlExecutor(con, conLogger)
        val res = code(sqlExec)
        checkEquals(con.autoCommit, expectedAutoCommit)
        return res
    }
}

object PostchainSqlInitProjExt: SqlInitProjExt() {
    override fun initExtra(exeCtx: Rt_ExecutionContext) {
        val chainId = exeCtx.sqlCtx.mainChainMapping().chainId
        val bcRid = BlockchainRid(exeCtx.appCtx.chainCtx.blockchainRid.toByteArray())

        val sqlAccess: SQLDatabaseAccess = PostgreSQLDatabaseAccess()
        exeCtx.sqlExec.connection { con ->
            sqlAccess.initializeApp(con, PostchainBaseUtils.DATABASE_VERSION)
            val eCtx: EContext = BaseEContext(con, chainId, sqlAccess)
            sqlAccess.initializeBlockchain(eCtx, bcRid)
        }
    }
}
