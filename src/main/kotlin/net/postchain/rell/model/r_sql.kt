package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.sql.SqlConstants

abstract class R_EntitySqlMapping {
    abstract fun rowidColumn(): String
    abstract fun autoCreateTable(): Boolean
    abstract fun isSystemEntity(): Boolean
    abstract fun table(sqlCtx: Rt_SqlContext): String
    abstract fun table(chainMapping: Rt_ChainSqlMapping): String
    abstract fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias)
    abstract fun extraWhereExpr(atEntity: R_AtEntity): Db_Expr?
    abstract fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String): String

    companion object {
        fun makeTransactionBlockHeightExpr(txEntity: R_Entity, txExpr: Db_TableExpr, chain: R_ExternalChainRef): Db_Expr {
            val blockAttr = txEntity.attribute("block")
            val blockEntity = (blockAttr.type as R_EntityType).rEntity
            val blockExpr = Db_RelExpr(txExpr, blockAttr, blockEntity)
            return makeBlockHeightExpr(blockEntity, blockExpr, chain)
        }

        fun makeBlockHeightExpr(blockEntity: R_Entity, blockExpr: Db_TableExpr, chain: R_ExternalChainRef): Db_Expr {
            val heightAttr = blockEntity.attribute("block_height")
            val blockHeightExpr = Db_AttrExpr(blockExpr, heightAttr)
            val chainHeightExpr = Db_InterpretedExpr(R_ChainHeightExpr(chain))
            return Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Le, blockHeightExpr, chainHeightExpr)
        }
    }
}

class R_EntitySqlMapping_Regular(private val mountName: R_MountName): R_EntitySqlMapping() {
    override fun rowidColumn() = SqlConstants.ROWID_COLUMN
    override fun autoCreateTable() = true
    override fun isSystemEntity() = false

    override fun table(sqlCtx: Rt_SqlContext): String {
        return table(sqlCtx.mainChainMapping)
    }

    override fun table(chainMapping: Rt_ChainSqlMapping): String {
        return chainMapping.fullName(mountName)
    }

    override fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias) {}
    override fun extraWhereExpr(atEntity: R_AtEntity) = null

    override fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String): String {
        val tbl = table(sqlCtx)
        val rowid = rowidColumn()
        return """SELECT "$rowid" FROM "$tbl" WHERE $where"""
    }
}

class R_EntitySqlMapping_External(private val mountName: R_MountName, private val chain: R_ExternalChainRef): R_EntitySqlMapping() {
    override fun rowidColumn() = SqlConstants.ROWID_COLUMN
    override fun autoCreateTable() = false
    override fun isSystemEntity() = false

    override fun table(sqlCtx: Rt_SqlContext): String {
        val chainMapping = sqlCtx.linkedChain(chain).sqlMapping
        return table(chainMapping)
    }

    override fun table(chainMapping: Rt_ChainSqlMapping): String {
        val res = chainMapping.fullName(mountName)
        return res
    }

    override fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias) {}

    override fun extraWhereExpr(atEntity: R_AtEntity): Db_Expr? {
        check(atEntity.rEntity.sqlMapping == this)
        val txAttr = atEntity.rEntity.attribute("transaction")
        val txEntity = (txAttr.type as R_EntityType).rEntity
        val entityExpr = Db_EntityExpr(atEntity)
        val txExpr = Db_RelExpr(entityExpr, txAttr, txEntity)
        return makeTransactionBlockHeightExpr(txEntity, txExpr, chain)
    }

    override fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String): String {
        val tbl = table(sqlCtx)
        val rowid = rowidColumn()

        val height = sqlCtx.linkedChain(chain).height
        return """SELECT A."$rowid"
            | FROM "$tbl" A JOIN "${SqlConstants.TRANSACTIONS_TABLE}" T ON T.tx_iid = A.transaction 
            | JOIN "${SqlConstants.BLOCKS_TABLE}" B ON B.block_iid = T.block_iid
            | WHERE $where AND B.block_height <= $height"""
                .trimMargin()
    }
}

abstract class R_EntitySqlMapping_TxBlk(
        private val table: String,
        private val rowid: String,
        private val chain: R_ExternalChainRef?
): R_EntitySqlMapping() {
    final override fun rowidColumn() = rowid
    final override fun autoCreateTable() = false
    final override fun isSystemEntity() = true

    final override fun table(sqlCtx: Rt_SqlContext) = table
    override fun table(chainMapping: Rt_ChainSqlMapping) = table

    final override fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, alias: SqlTableAlias) {
        val chainMapping = sqlCtx.chainMapping(chain)
        b.appendSep(" AND ")
        b.append("(")
        b.appendColumn(alias, "chain_iid")
        b.append(" = ")
        b.append(R_IntegerType, Rt_IntValue(chainMapping.chainId))
        b.append(")")
    }

    abstract fun extraWhereExpr0(entity: R_Entity, entityExpr: Db_EntityExpr, chain: R_ExternalChainRef): Db_Expr

    final override fun extraWhereExpr(atEntity: R_AtEntity): Db_Expr? {
        check(atEntity.rEntity.sqlMapping == this)

        // Extra WHERE with block height check is needed only for external block/transaction entities.
        if (chain == null) return null

        val entity = atEntity.rEntity
        val entityExpr = Db_EntityExpr(atEntity)
        return extraWhereExpr0(entity, entityExpr, chain)
    }

    final override fun selectExistingObjects(sqlCtx: Rt_SqlContext, where: String) = throw UnsupportedOperationException()
}

class R_EntitySqlMapping_Transaction(chain: R_ExternalChainRef?): R_EntitySqlMapping_TxBlk(SqlConstants.TRANSACTIONS_TABLE, "tx_iid", chain) {
    override fun extraWhereExpr0(entity: R_Entity, entityExpr: Db_EntityExpr, chain: R_ExternalChainRef): Db_Expr {
        return makeTransactionBlockHeightExpr(entity, entityExpr, chain)
    }
}

class R_EntitySqlMapping_Block(chain: R_ExternalChainRef?): R_EntitySqlMapping_TxBlk(SqlConstants.BLOCKS_TABLE, "block_iid", chain) {
    override fun extraWhereExpr0(entity: R_Entity, entityExpr: Db_EntityExpr, chain: R_ExternalChainRef): Db_Expr {
        return makeBlockHeightExpr(entity, entityExpr, chain)
    }
}
