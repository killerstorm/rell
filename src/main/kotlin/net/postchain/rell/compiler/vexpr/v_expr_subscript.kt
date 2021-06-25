package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.model.*

sealed class V_CommonSubscriptKind(val resType: R_Type) {
    abstract fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr): R_Expr
    open fun compileDb(pos: S_Pos, dbBase: Db_Expr, dbKey: Db_Expr): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)
    open fun compileDestination(pos: S_Pos, rBase: R_Expr, rKey: R_Expr): R_DestinationExpr? = null
}

object V_CommonSubscriptKind_Text: V_CommonSubscriptKind(R_TextType) {
    override fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_TextSubscriptExpr(rBase, rKey)

    override fun compileDb(pos: S_Pos, dbBase: Db_Expr, dbKey: Db_Expr): Db_Expr {
        return Db_CallExpr(R_TextType, Db_SysFn_Text_Subscript, listOf(dbBase, dbKey))
    }
}

object V_CommonSubscriptKind_ByteArray: V_CommonSubscriptKind(R_IntegerType) {
    override fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_ByteArraySubscriptExpr(rBase, rKey)

    override fun compileDb(pos: S_Pos, dbBase: Db_Expr, dbKey: Db_Expr): Db_Expr {
        return Db_CallExpr(R_IntegerType, Db_SysFn_ByteArray_Subscript, listOf(dbBase, dbKey))
    }
}

class V_CommonSubscriptKind_List(elementType: R_Type): V_CommonSubscriptKind(elementType) {
    override fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_ListSubscriptExpr(resType, rBase, rKey)
    override fun compileDestination(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_ListSubscriptExpr(resType, rBase, rKey)
}

class V_CommonSubscriptKind_VirtualList(resType: R_Type): V_CommonSubscriptKind(resType) {
    override fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_VirtualListSubscriptExpr(resType, rBase, rKey)
}

class V_CommonSubscriptKind_Map(valueType: R_Type): V_CommonSubscriptKind(valueType) {
    override fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_MapSubscriptExpr(resType, rBase, rKey)
    override fun compileDestination(pos: S_Pos, rBase: R_Expr, rKey: R_Expr) = R_MapSubscriptExpr(resType, rBase, rKey)
}

class V_CommonSubscriptKind_VirtualMap(valueType: R_Type): V_CommonSubscriptKind(valueType) {
    override fun compileR(pos: S_Pos, rBase: R_Expr, rKey: R_Expr): R_Expr {
        val virtualValueType = S_VirtualType.virtualMemberType(resType)
        return R_VirtualMapSubscriptExpr(virtualValueType, rBase, rKey)
    }
}

sealed class V_TupleSubscriptKind {
    abstract fun compile(resType: R_Type, index: Int): R_MemberCalculator
}

object V_TupleSubscriptKind_Simple: V_TupleSubscriptKind() {
    override fun compile(resType: R_Type, index: Int) = R_MemberCalculator_TupleAttr(resType, index)
}

object V_TupleSubscriptKind_Virtual: V_TupleSubscriptKind() {
    override fun compile(resType: R_Type, index: Int) = R_MemberCalculator_VirtualTupleAttr(resType, index)
}

sealed class V_SubscriptExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        protected val baseExpr: V_Expr,
        protected val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    final override fun varFacts() = varFacts
}

class V_CommonSubscriptExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        baseExpr: V_Expr,
        varFacts: C_ExprVarFacts,
        private val keyExpr: V_Expr,
        private val kind: V_CommonSubscriptKind
): V_SubscriptExpr(exprCtx, pos, baseExpr, varFacts) {
    override val exprInfo = V_ExprInfo.make(listOf(baseExpr, keyExpr))

    override fun type() = kind.resType

    override fun toRExpr0(): R_Expr {
        val rBase = baseExpr.toRExpr()
        val rKey = keyExpr.toRExpr()
        return kind.compileR(pos, rBase, rKey)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbBase = baseExpr.toDbExpr()
        val dbKey = keyExpr.toDbExpr()
        return kind.compileDb(pos, dbBase, dbKey)
    }

    override fun destination(): C_Destination {
        val rBase = baseExpr.toRExpr()
        val rKey = keyExpr.toRExpr()
        val dstExpr = kind.compileDestination(pos, rBase, rKey)
        if (dstExpr == null) {
            val baseType = baseExpr.type()
            val type = baseType.toStrictString()
            throw C_Error.stop(pos, "expr_immutable:$type", "Value of type '$type' cannot be modified")
        }
        return C_SimpleDestination(dstExpr)
    }
}

class V_TupleSubscriptExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        baseExpr: V_Expr,
        varFacts: C_ExprVarFacts,
        private val kind: V_TupleSubscriptKind,
        private val resType: R_Type,
        private val index: Int
): V_SubscriptExpr(exprCtx, pos, baseExpr, varFacts) {
    override val exprInfo = V_ExprInfo.make(baseExpr)

    override fun type() = resType

    override fun toRExpr0(): R_Expr {
        val rBase = baseExpr.toRExpr()
        val calculator = kind.compile(resType, index)
        return R_MemberExpr(rBase, false, calculator)
    }
}
