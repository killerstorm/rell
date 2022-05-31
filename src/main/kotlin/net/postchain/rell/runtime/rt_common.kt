/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import mu.KLogging
import net.postchain.common.data.ByteArrayKey
import net.postchain.rell.model.R_MountName
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.utils.toImmSet

class Rt_ChainDependency(val rid: ByteArray)

class Rt_ExternalChain(val chainId: Long, val rid: ByteArray, val height: Long) {
    val sqlMapping = Rt_ChainSqlMapping(chainId)
}

interface Rt_Printer {
    fun print(str: String)
}

object Rt_FailingPrinter: Rt_Printer {
    override fun print(str: String) {
        throw UnsupportedOperationException()
    }
}

object Rt_NopPrinter: Rt_Printer {
    override fun print(str: String) {
        // Do nothing.
    }
}

object Rt_OutPrinter: Rt_Printer {
    override fun print(str: String) {
        println(str)
    }
}

class Rt_LogPrinter(name: String = "net.postchain.Rell"): Rt_Printer {
    private val logger = KLogging().logger(name)

    override fun print(str: String) {
        logger.info(str)
    }
}

interface Rt_PrinterFactory {
    fun newPrinter(): Rt_Printer
}

class Rt_ChainSqlMapping(val chainId: Long) {
    private val prefix = "c$chainId."
    private val systemPrefix = "${prefix}sys."

    val rowidTable = fullName(SqlConstants.ROWID_GEN)
    val rowidFunction = fullName(SqlConstants.MAKE_ROWID)
    val blocksTable = fullName(SqlConstants.BLOCKS_TABLE)
    val transactionsTable = fullName(SqlConstants.TRANSACTIONS_TABLE)
    val metaEntitiesTable = fullName("sys.classes")
    val metaAttributesTable = fullName("sys.attributes")

    val tableSqlFilter = "$prefix%"

    private val baseSystemTables: Set<String> = let {
        val sysTables = setOf(blocksTable, transactionsTable) + SqlConstants.SYSTEM_CHAIN_TABLES.map { fullName(it) }
        sysTables.toImmSet()
    }

    fun fullName(baseName: String): String {
        return prefix + baseName
    }

    fun fullName(mountName: R_MountName): String {
        return prefix + mountName.str()
    }

    fun isChainTable(table: String): Boolean {
        return table.startsWith(prefix) && table != rowidTable && table != rowidFunction
    }

    fun isSystemTable(table: String): Boolean {
        return table.startsWith(systemPrefix) || table in baseSystemTables
    }
}

interface Rt_ChainHeightProvider {
    fun getChainHeight(rid: ByteArrayKey, id: Long): Long?
}

class Rt_ConstantChainHeightProvider(private val height: Long): Rt_ChainHeightProvider {
    override fun getChainHeight(rid: ByteArrayKey, id: Long) = height
}
