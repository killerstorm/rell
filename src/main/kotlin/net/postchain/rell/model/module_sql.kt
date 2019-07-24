package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.sql.ROWID_COLUMN

abstract class R_ClassSqlMapping {
    abstract fun rowidColumn(): String
    abstract fun autoCreateTable(): Boolean
    abstract fun table(sqlCtx: Rt_SqlContext): String
    open fun tempTable(sqlCtx: Rt_SqlContext, subName: String): String = throw UnsupportedOperationException()
    abstract fun table(chainMapping: Rt_ChainSqlMapping): String
    abstract fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias)
    abstract fun extraWhereExpr(atCls: R_AtClass): Db_Expr?
    abstract fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String): String

    companion object {
        fun makeTransactionBlockHeightExpr(txCls: R_Class, txExpr: Db_TableExpr, chain: R_ExternalChain): Db_Expr {
            val blockAttr = txCls.attribute("block")
            val blockCls = (blockAttr.type as R_ClassType).rClass
            val blockExpr = Db_RelExpr(txExpr, blockAttr, blockCls)
            return makeBlockHeightExpr(blockCls, blockExpr, chain)
        }

        fun makeBlockHeightExpr(blockCls: R_Class, blockExpr: Db_TableExpr, chain: R_ExternalChain): Db_Expr {
            val heightAttr = blockCls.attribute("block_height")
            val blockHeightExpr = Db_AttrExpr(blockExpr, heightAttr)
            val chainHeightExpr = Db_InterpretedExpr(R_ChainHeightExpr(chain))
            return Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Le, blockHeightExpr, chainHeightExpr)
        }
    }
}

class R_ClassSqlMapping_Regular(private val tableBaseName: String): R_ClassSqlMapping() {
    override fun rowidColumn() = ROWID_COLUMN
    override fun autoCreateTable() = true

    override fun table(sqlCtx: Rt_SqlContext): String {
        return table(sqlCtx.mainChainMapping)
    }

    override fun table(chainMapping: Rt_ChainSqlMapping): String {
        return chainMapping.fullName(tableBaseName)
    }

    override fun tempTable(sqlCtx: Rt_SqlContext, subName: String): String {
        return sqlCtx.mainChainMapping.tempName(subName + "." + tableBaseName)
    }

    override fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias) {}
    override fun extraWhereExpr(atCls: R_AtClass) = null

    override fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String): String {
        val tbl = table(sqlCtx)
        val rowid = rowidColumn()
        return """SELECT "$rowid" FROM "$tbl" WHERE $where"""
    }
}

class R_ClassSqlMapping_External(private val tableBaseName: String, private val chain: R_ExternalChain): R_ClassSqlMapping() {
    override fun rowidColumn() = ROWID_COLUMN
    override fun autoCreateTable() = false

    override fun table(sqlCtx: Rt_SqlContext): String {
        val chainMapping = sqlCtx.linkedChain(chain).sqlMapping
        return table(chainMapping)
    }

    override fun table(chainMapping: Rt_ChainSqlMapping): String {
        val res = chainMapping.fullName(tableBaseName)
        return res
    }

    override fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias) {}

    override fun extraWhereExpr(atCls: R_AtClass): Db_Expr? {
        check(atCls.rClass.sqlMapping == this)
        val txAttr = atCls.rClass.attribute("transaction")
        val txCls = (txAttr.type as R_ClassType).rClass
        val clsExpr = Db_ClassExpr(atCls)
        val txExpr = Db_RelExpr(clsExpr, txAttr, txCls)
        return makeTransactionBlockHeightExpr(txCls, txExpr, chain)
    }

    override fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String): String {
        val tbl = table(sqlCtx)
        val rowid = rowidColumn()

        val height = sqlCtx.linkedChain(chain).height
        return """SELECT A."$rowid"
            | FROM "$tbl" A JOIN "transactions" T ON T.tx_iid = A.transaction JOIN "blocks" B ON B.block_iid = T.block_iid
            | WHERE $where AND B.block_height <= $height"""
                .trimMargin()
    }
}

abstract class R_ClassSqlMapping_TxBlk(
        private val table: String,
        private val rowid: String,
        private val chain: R_ExternalChain?
): R_ClassSqlMapping() {
    override fun rowidColumn() = rowid
    override fun autoCreateTable() = false

    override fun table(sqlCtx: Rt_SqlContext) = table
    override fun table(chainMapping: Rt_ChainSqlMapping) = table

    override fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias) {
        val chainMapping = sqlCtx.chainMapping(chain)
        b.appendSep(" AND ")
        b.append("(")
        b.appendColumn(alias, "chain_iid")
        b.append(" = ")
        b.append(R_IntegerType, Rt_IntValue(chainMapping.chainId))
        b.append(")")
    }

    abstract fun extraWhereExpr0(cls: R_Class, clsExpr: Db_ClassExpr, chain: R_ExternalChain): Db_Expr

    final override fun extraWhereExpr(atCls: R_AtClass): Db_Expr? {
        check(atCls.rClass.sqlMapping == this)

        // Extra WHERE with block height check is needed only for external block/transaction classes.
        if (chain == null) return null

        val cls = atCls.rClass
        val clsExpr = Db_ClassExpr(atCls)
        return extraWhereExpr0(cls, clsExpr, chain)
    }

    override fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String) = throw UnsupportedOperationException()
}

class R_ClassSqlMapping_Transaction(chain: R_ExternalChain?): R_ClassSqlMapping_TxBlk("transactions", "tx_iid", chain) {
    override fun extraWhereExpr0(cls: R_Class, clsExpr: Db_ClassExpr, chain: R_ExternalChain): Db_Expr {
        return makeTransactionBlockHeightExpr(cls, clsExpr, chain)
    }
}

class R_ClassSqlMapping_Block(chain: R_ExternalChain?): R_ClassSqlMapping_TxBlk("blocks", "block_iid", chain) {
    override fun extraWhereExpr0(cls: R_Class, clsExpr: Db_ClassExpr, chain: R_ExternalChain): Db_Expr {
        return makeBlockHeightExpr(cls, clsExpr, chain)
    }
}
