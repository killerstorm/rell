/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.gtx

import net.postchain.StorageBuilder
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.config.app.AppConfig
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.base.runtime.utils.Rt_SqlManager
import net.postchain.rell.base.sql.ConnectionSqlManager
import net.postchain.rell.base.sql.NoConnSqlManager
import net.postchain.rell.base.sql.SqlManager
import org.apache.http.client.utils.URLEncodedUtils
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

object RellApiGtxUtils {
    fun extractDatabaseSchema(url: String): String? {
        val uri = URI(url)
        check(uri.scheme == "jdbc") { "Invalid scheme: '${uri.scheme}'" }

        val uri2 = URI(uri.schemeSpecificPart)
        val query = uri2.query
        val pairs = URLEncodedUtils.parse(query, Charsets.UTF_8)

        for (pair in pairs) {
            if (pair.name == "currentSchema") {
                return pair.value
            }
        }

        return null
    }

    fun prepareSchema(con: Connection, schema: String) {
        con.createStatement().use { stmt ->
            stmt.execute("""CREATE SCHEMA IF NOT EXISTS "$schema";""")
        }
    }

    fun <T> runWithSqlManager(
        dbUrl: String?,
        dbProperties: String?,
        sqlLog: Boolean,
        sqlErrorLog: Boolean,
        code: (SqlManager) -> T,
    ): T {
        return if (dbUrl != null) {
            val schema = extractDatabaseSchema(dbUrl)
            val jdbcProperties = Properties()
            jdbcProperties.setProperty("binaryTransfer", "false")
            DriverManager.getConnection(dbUrl, jdbcProperties).use { con ->
                con.autoCommit = true
                PostgreSQLDatabaseAccess().checkCollation(con, suppressError = false)
                val sqlMgr = ConnectionSqlManager(con, sqlLog)
                runWithSqlManager(schema, sqlMgr, sqlErrorLog, code)
            }
        } else if (dbProperties != null) {
            val appCfg = AppConfig.fromPropertiesFile(dbProperties)
            val storage = StorageBuilder.buildStorage(appCfg)
            val sqlMgr = PostchainStorageSqlManager(storage, sqlLog)
            runWithSqlManager(appCfg.databaseSchema, sqlMgr, sqlErrorLog, code)
        } else {
            code(NoConnSqlManager)
        }
    }

    private fun <T> runWithSqlManager(
        schema: String?,
        sqlMgr: SqlManager,
        logSqlErrors: Boolean,
        code: (SqlManager) -> T,
    ): T {
        val sqlMgr2 = Rt_SqlManager(sqlMgr, logSqlErrors)
        if (schema != null) {
            sqlMgr2.transaction { sqlExec ->
                sqlExec.connection { con ->
                    RellApiGtxUtils.prepareSchema(con, schema)
                }
            }
        }
        return code(sqlMgr2)
    }

    fun genBlockchainConfigTemplateNoRell(pubKey: ByteArray): Gtv {
        return GtvFactory.gtv(
            "blockstrategy" to GtvFactory.gtv("name" to GtvFactory.gtv("net.postchain.base.BaseBlockBuildingStrategy")),
            "configurationfactory" to GtvFactory.gtv("net.postchain.gtx.GTXBlockchainConfigurationFactory"),
            "signers" to GtvFactory.gtv(listOf(GtvFactory.gtv(pubKey))),
            "gtx" to GtvFactory.gtv(
                "modules" to GtvFactory.gtv(
                    GtvFactory.gtv("net.postchain.rell.module.RellPostchainModuleFactory"),
                    GtvFactory.gtv("net.postchain.gtx.StandardOpsGTXModule"),
                ),
            )
        )
    }
}
