/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.base.core.C_AppContext
import net.postchain.rell.compiler.base.core.C_AtSummarizationKind
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_ColAtFrom
import net.postchain.rell.compiler.vexpr.V_DbAtWhat
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.model.stmt.R_ForIterator
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmList

class C_AtContext(
        val parent: C_AtContext?,
        val atExprId: R_AtExprId,
        val dbAt: Boolean
)

class C_AtFromContext(val pos: S_Pos, val atExprId: R_AtExprId, val parentAtCtx: C_AtContext?)

abstract class C_AtFrom(
        protected val outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext
) {
    val atExprId = fromCtx.atExprId

    protected val parentAtCtx = fromCtx.parentAtCtx
    protected val innerBlkCtx = outerExprCtx.blkCtx.createSubContext("@", atFrom = this)

    val innerAtCtx = C_AtContext(fromCtx.parentAtCtx, atExprId, this is C_AtFrom_Entities)

    abstract fun innerExprCtx(): C_ExprContext
    abstract fun makeDefaultWhat(): V_DbAtWhat
    abstract fun findAttributesByName(name: String): List<C_AtFromContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_AtFromContextAttr>

    abstract fun compile(details: C_AtDetails): V_Expr
}

sealed class C_AtFromItem(val pos: S_Pos)

class C_AtFromItem_Entity(pos: S_Pos, val alias: S_Name, val entity: R_EntityDefinition): C_AtFromItem(pos)

class C_AtFromItem_Iterable(
        pos: S_Pos,
        val vExpr: V_Expr,
        val elemType: R_Type,
        val rIterator: R_ForIterator
): C_AtFromItem(pos) {
    fun compile(): V_ColAtFrom {
        return V_ColAtFrom(rIterator, vExpr)
    }
}

class C_AtExprBase(
        val what: V_DbAtWhat,
        val where: V_Expr?
)

class C_AtExprResult(
        val recordType: R_Type,
        val resultType: R_Type,
        val rowDecoder: R_AtExprRowDecoder,
        selectedFields: List<Int>,
        groupFields: List<Int>,
        val hasAggregateFields: Boolean
) {
    val selectedFields = selectedFields.toImmList()
    val groupFields = groupFields.toImmList()

    companion object {
        fun calcResultType(recordType: R_Type, cardinality: R_AtCardinality): R_Type {
            return if (cardinality.many) {
                R_ListType(recordType)
            } else if (cardinality.zero) {
                C_Types.toNullable(recordType)
            } else {
                recordType
            }
        }
    }
}

class C_AtDetails(
        val startPos: S_Pos,
        val cardinality: S_PosValue<R_AtCardinality>,
        val base: C_AtExprBase,
        val limit: V_Expr?,
        val offset: V_Expr?,
        val res: C_AtExprResult,
        val exprFacts: C_ExprVarFacts
)

class C_AtSummarizationPos(val exprPos: S_Pos, val ann: C_AtSummarizationKind)

sealed class C_AtSummarization(protected val pos: C_AtSummarizationPos, protected val valueType: R_Type) {
    abstract fun isGroup(): Boolean
    abstract fun getResultType(hasGroup: Boolean): R_Type
    abstract fun compileR(appCtx: C_AppContext): R_ColAtFieldSummarization
    abstract fun compileDb(appCtx: C_AppContext, dbExpr: Db_Expr): Db_Expr

    companion object {
        fun typeError(msgCtx: C_MessageContext, type: R_Type, pos: C_AtSummarizationPos) {
            val code = "at:what:aggr:bad_type:${pos.ann}:${type.toStrictString()}"
            val msg = "Invalid type of @${pos.ann.annotation} expression: ${type.toStrictString()}"
            msgCtx.error(pos.exprPos, code, msg)
        }
    }
}

class C_AtSummarization_Group(pos: C_AtSummarizationPos, valueType: R_Type): C_AtSummarization(pos, valueType) {
    override fun isGroup() = true
    override fun getResultType(hasGroup: Boolean) = valueType

    override fun compileR(appCtx: C_AppContext): R_ColAtFieldSummarization {
        C_Utils.checkGroupValueType(appCtx, pos.exprPos, valueType)
        return R_ColAtFieldSummarization_Group()
    }

    override fun compileDb(appCtx: C_AppContext, dbExpr: Db_Expr) = dbExpr
}

sealed class C_AtSummarization_Aggregate(
        pos: C_AtSummarizationPos,
        valueType: R_Type
): C_AtSummarization(pos, valueType) {
    protected abstract fun compileDb0(): Db_SysFunction?

    final override fun isGroup() = false

    final override fun compileDb(appCtx: C_AppContext, dbExpr: Db_Expr): Db_Expr {
        val dbFn = compileDb0()
        if (dbFn == null) {
            typeError(appCtx.msgCtx, valueType, pos)
            return dbExpr
        }
        return Db_CallExpr(dbExpr.type, dbFn, listOf(dbExpr))
    }
}

class C_AtSummarization_Aggregate_Sum(
        pos: C_AtSummarizationPos,
        valueType: R_Type,
        private val rOp: R_BinaryOp,
        private val zeroValue: Rt_Value
): C_AtSummarization_Aggregate(pos, valueType) {
    override fun getResultType(hasGroup: Boolean) = valueType
    override fun compileR(appCtx: C_AppContext) = R_ColAtFieldSummarization_Aggregate_Sum(rOp, zeroValue)
    override fun compileDb0() = Db_SysFn_Aggregation_Sum
}

class C_AtSummarization_Aggregate_MinMax(
        pos: C_AtSummarizationPos,
        valueType: R_Type,
        private val rCmpOp: R_CmpOp,
        private val rCmpType: R_CmpType?,
        private val rComparator: Comparator<Rt_Value>?,
        private val dbFn: Db_SysFunction
): C_AtSummarization_Aggregate(pos, valueType) {
    override fun getResultType(hasGroup: Boolean): R_Type {
        return if (hasGroup) valueType else C_Types.toNullable(valueType)
    }

    override fun compileR(appCtx: C_AppContext): R_ColAtFieldSummarization {
        return if (rComparator == null) {
            typeError(appCtx.msgCtx, valueType, pos)
            R_ColAtFieldSummarization_None
        } else {
            R_ColAtFieldSummarization_Aggregate_MinMax(rCmpOp, rComparator)
        }
    }

    override fun compileDb0(): Db_SysFunction? {
        // Postgres doesn't support MIN/MAX for BOOLEAN and BYTEA.
        return if (rCmpType == null || valueType == R_BooleanType || valueType == R_ByteArrayType) null else dbFn
    }
}

class C_ExprContextAttr(private val fromAttr: C_AtFromContextAttr, private val outerAtExpr: Boolean) {
    val type = fromAttr.type

    fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        if (outerAtExpr) {
            val attrName = attrNameMsg(false)
            val ownerTypeName = fromAttr.ownerTypeName()
            ctx.msgCtx.error(pos, "at_expr:attr:belongs_to_outer:${attrName.code}:$ownerTypeName",
                    "Attribute '${attrName.msg}' belongs to an outer at-expression, fully qualified name is required")
        }
        return fromAttr.compile(ctx, pos)
    }

    fun attrNameMsg(qualified: Boolean): C_CodeMsg = fromAttr.attrNameMsg(qualified)
    override fun toString() = attrNameMsg(true).code
}

abstract class C_AtFromContextAttr(val type: R_Type) {
    abstract fun attrNameMsg(qualified: Boolean): C_CodeMsg
    abstract fun ownerTypeName(): String
    abstract fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr

    final override fun toString() = attrNameMsg(true).code
}
