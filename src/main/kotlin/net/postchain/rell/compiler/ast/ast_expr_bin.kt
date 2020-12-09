/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import java.util.*

enum class S_BinaryOp(private val code: String, val op: C_BinOp) {
    EQ("==", C_BinOp_Eq),
    NE("!=", C_BinOp_Ne),
    LE("<=", C_BinOp_Le),
    GE(">=", C_BinOp_Ge),
    LT("<", C_BinOp_Lt),
    GT(">", C_BinOp_Gt),
    EQ_REF("===", C_BinOp_EqRef),
    NE_REF("!==", C_BinOp_NeRef),
    PLUS("+", C_BinOp_Plus),
    MINUS("-", C_BinOp_Minus),
    MUL("*", C_BinOp_Mul),
    DIV("/", C_BinOp_Div),
    MOD("%", C_BinOp_Mod),
    AND("and", C_BinOp_And),
    OR("or", C_BinOp_Or),
    IN("in", C_BinOp_In),
    ELVIS("?:", C_BinOp_Elvis),
    ;

    companion object {
        private val PRECEDENCE_LEVELS = listOf(
                listOf(OR),
                listOf(AND),
                listOf(EQ, NE, LE, GE, LT, GT, EQ_REF, NE_REF),
                listOf(IN),
                listOf(ELVIS),
                listOf(PLUS, MINUS),
                listOf(MUL, DIV, MOD)
        )

        private val PRECEDENCE_MAP: Map<S_BinaryOp, Int>

        init {
            val m = mutableMapOf<S_BinaryOp, Int>()

            for ((level, ops) in PRECEDENCE_LEVELS.withIndex()) {
                for (op in ops) {
                    check(op !in m)
                    m[op] = level
                }
            }
            check(m.keys.containsAll(values().toSet())) {
                "Forgot to add new operator to the precedence table?"
            }

            PRECEDENCE_MAP = m.toMap()
        }
    }

    fun precedence(): Int = PRECEDENCE_MAP.getValue(this)

    fun compile(ctx: C_ExprContext, pos: S_Pos, left: V_Expr, right: V_Expr): V_Expr {
        val value = op.compile(left, right)
        return if (value != null) {
            value
        } else {
            C_BinOp.errTypeMismatch(ctx.msgCtx, pos, code, left.type(), right.type())
            C_Utils.errorVExpr(pos)
        }
    }
}

enum class S_AssignOpCode(val op: S_AssignOp) {
    EQ(S_AssignOp_Eq),
    PLUS(S_AssignOp_Op("+=", C_BinOp_Plus)),
    MINUS(S_AssignOp_Op("-=", C_BinOp_Minus)),
    MUL(S_AssignOp_Op("*=", C_BinOp_Mul)),
    DIV(S_AssignOp_Op("/=", C_BinOp_Div)),
    MOD(S_AssignOp_Op("%=", C_BinOp_Mod)),
    ;
}

sealed class S_AssignOp {
    abstract fun compile(ctx: C_ExprContext, pos: S_Pos, dstValue: V_Expr, srcValue: V_Expr): C_Statement
    abstract fun compileDbUpdate(ctx: C_ExprContext, pos: S_Pos, attr: R_Attrib, srcValue: V_Expr): R_UpdateStatementWhat?

    protected open fun compileVarFacts(
            dstValue: V_Expr,
            dstExpr: C_Destination,
            srcValue: V_Expr,
            srcType: R_Type
    ): C_VarFacts {
        return dstValue.varFacts().postFacts.and(srcValue.varFacts().postFacts)
    }
}

object S_AssignOp_Eq: S_AssignOp() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos, dstValue: V_Expr, srcValue: V_Expr): C_Statement {
        val dstExpr = dstValue.destination(ctx)
        val dstType = dstExpr.type()
        val rSrcExpr = srcValue.toRExpr()

        val adapter = C_Types.adapt(dstType, rSrcExpr.type, pos, "stmt_assign_type", "Assignment type mismatch")
        val rSrcAdapterExpr = adapter.adaptExpr(rSrcExpr)
        val rStmt = dstExpr.compileAssignStatement(rSrcAdapterExpr, null)

        val varFacts = compileVarFacts(dstValue, dstExpr, srcValue, rSrcExpr.type)
        return C_Statement(rStmt, false, varFacts)
    }

    override fun compileVarFacts(dstValue: V_Expr, dstExpr: C_Destination, srcValue: V_Expr, srcType: R_Type): C_VarFacts {
        var varFacts = super.compileVarFacts(dstValue, dstExpr, srcValue, srcType)
        val cVarId = dstValue.varId()
        if (cVarId != null) {
            val inited = mapOf(cVarId to C_VarFact.YES)
            val dstType = dstExpr.type()
            val nulled = C_VarFacts.varTypeToNulled(cVarId, dstType, srcType)
            varFacts = varFacts.put(C_VarFacts.of(inited = inited, nulled = nulled))
        }
        return varFacts
    }

    override fun compileDbUpdate(ctx: C_ExprContext, pos: S_Pos, attr: R_Attrib, srcValue: V_Expr): R_UpdateStatementWhat {
        val expr = srcValue.toDbExpr(ctx.msgCtx)
        val adapter = attr.type.getTypeAdapter(expr.type)
        val expr2 = if (adapter == null) {
            val name = attr.name
            C_Errors.errTypeMismatch(ctx.msgCtx, pos, expr.type, attr.type, "stmt_assign_type", "Type mismatch for '$name'")
            C_Utils.errorDbExpr(attr.type)
        } else {
            adapter.adaptExpr(expr)
        }
        return R_UpdateStatementWhat(attr, expr2, null)
    }
}

class S_AssignOp_Op(val code: String, val op: C_BinOp_Common): S_AssignOp() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos, dstValue: V_Expr, srcValue: V_Expr): C_Statement {
        val dstExpr = dstValue.destination(ctx)
        val dstType = dstExpr.effectiveType()

        val srcValue2 = op.adaptRight(dstType, srcValue)
        val rSrcExpr = srcValue2.toRExpr()
        val srcType = rSrcExpr.type

        val binOp = compileBinOp(ctx, pos, dstType, srcType)
        val cOp = C_AssignOp(pos, code, binOp.rOp, binOp.dbOp)
        val rStmt = dstExpr.compileAssignStatement(rSrcExpr, cOp)

        val varFacts = compileVarFacts(dstValue, dstExpr, srcValue, rSrcExpr.type)
        return C_Statement(rStmt, false, varFacts)
    }

    override fun compileDbUpdate(ctx: C_ExprContext, pos: S_Pos, attr: R_Attrib, srcValue: V_Expr): R_UpdateStatementWhat {
        val srcValue2 = op.adaptRight(attr.type, srcValue)
        val dbSrcExpr = srcValue2.toDbExpr(ctx.msgCtx)
        val srcType = dbSrcExpr.type

        val binOp = compileBinOp(ctx, pos, attr.type, srcType)
        if (binOp.dbOp == null) {
            C_BinOp.errTypeMismatch(ctx.msgCtx, pos, code, attr.type, srcType)
        }

        return R_UpdateStatementWhat(attr, dbSrcExpr, binOp.dbOp)
    }

    private fun compileBinOp(ctx: C_ExprContext, pos: S_Pos, left: R_Type, right: R_Type): V_BinaryOp {
        val binOp = op.compileOp(left, right)
        return if (binOp != null && binOp.resType == left) binOp else {
            C_BinOp.errTypeMismatch(ctx.msgCtx, pos, code, left, right)
            V_BinaryOp.of(left, R_BinaryOp_Add_Integer, Db_BinaryOp_Add_Integer) // Using fake ops for error recovery.
        }
    }
}

sealed class C_BinOp {
    abstract fun compile(left: V_Expr, right: V_Expr): V_Expr?
    open fun rightVarFacts(left: V_Expr): C_VarFacts = C_VarFacts.EMPTY

    protected open fun compileExprVarFacts(left: V_Expr, right: V_Expr): C_ExprVarFacts {
        val postFacts = left.varFacts().postFacts.and(right.varFacts().postFacts)
        return C_ExprVarFacts.of(postFacts = postFacts)
    }

    companion object {
        fun errTypeMismatch(msgCtx: C_MessageContext, pos: S_Pos, op: String, leftType: R_Type, rightType: R_Type) {
            if (leftType != R_CtErrorType && rightType != R_CtErrorType) {
                msgCtx.error(pos, "binop_operand_type:$op:[$leftType]:[$rightType]",
                        "Wrong operand types for '$op': $leftType, $rightType")
            }
        }
    }
}

sealed class C_BinOp_Common: C_BinOp() {
    abstract fun compileOp(left: R_Type, right: R_Type): V_BinaryOp?

    final override fun compile(left: V_Expr, right: V_Expr): V_Expr? {
        val (left2, right2) = adaptLeftRight(left, right)
        return compile0(left2, right2)
    }

    private fun compile0(left: V_Expr, right: V_Expr): V_Expr? {
        val leftType = left.type()
        val rightType = right.type()

        val op = compileOp(leftType, rightType)
        if (op == null) {
            return null
        }

        val exprVarFacts = compileExprVarFacts(left, right)
        return V_BinaryExpr(left.pos, op, left, right, exprVarFacts)
    }

    open fun adaptLeftRight(left: V_Expr, right: V_Expr): Pair<V_Expr, V_Expr> {
        return promoteNumeric(left, right)
    }

    open fun adaptRight(leftType: R_Type, right: V_Expr): V_Expr {
        val rightType = right.type()
        return if (leftType == R_DecimalType && rightType == R_IntegerType) {
            promoteNumeric(right)
        } else {
            right
        }
    }

    companion object {
        fun promoteNumeric(left: V_Expr, right: V_Expr): Pair<V_Expr, V_Expr> {
            val leftType = left.type()
            val rightType = right.type()

            if (leftType == R_IntegerType && rightType == R_DecimalType) {
                return Pair(promoteNumeric(left), right)
            } else if (leftType == R_DecimalType && rightType == R_IntegerType) {
                return Pair(left, promoteNumeric(right))
            } else {
                return Pair(left, right)
            }
        }

        fun promoteNumeric(values: List<V_Expr>): List<V_Expr> {
            val hasOther = values.any { it.type() != R_IntegerType && it.type() != R_DecimalType }
            val hasInteger = values.any { it.type() == R_IntegerType }
            val hasDecimal = values.any { it.type() == R_DecimalType }

            return if (hasInteger && hasDecimal && !hasOther) {
                values.map { promoteNumeric(it) }
            } else {
                values
            }
        }

        private fun promoteNumeric(value: V_Expr): V_Expr {
            val type = value.type()
            return if (type != R_IntegerType) {
                value
            } else {
                C_Utils.integerToDecimalPromotion(value)
            }
        }
    }
}

sealed class C_BinOp_EqNe(private val eq: Boolean): C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        val type = calcCommonType(left, right)
                ?: calcCommonType(right, left)
        return if (type == null || type is R_ObjectType) null else createVOp(eq, type)
    }

    override fun compileExprVarFacts(left: V_Expr, right: V_Expr) = compileExprVarFacts(eq, left, right)

    override fun adaptLeftRight(left: V_Expr, right: V_Expr): Pair<V_Expr, V_Expr> {
        val p = adaptOperands(left, right)
        return p ?: super.adaptLeftRight(left, right)
    }

    companion object {
        fun checkTypes(left: R_Type, right: R_Type): Boolean {
            val op = C_BinOp_Eq.compileOp(left, right)
            return op != null
        }

        fun checkTypesDb(left: R_Type, right: R_Type): Boolean {
            val op = C_BinOp_Eq.compileOp(left, right)
            return op?.dbOp != null
        }

        private fun calcCommonType(left: R_Type, right: R_Type): R_Type? {
            return if (left is R_NullableType && right !is R_NullableType && right != R_NullType) {
                null
            } else if (left.isAssignableFrom(right)) {
                left
            } else {
                null
            }
        }

        fun compileExprVarFacts(eq: Boolean, left: V_Expr, right: V_Expr): C_ExprVarFacts {
            val postFacts = left.varFacts().postFacts.and(right.varFacts().postFacts)

            val boolFacts = compileExprVarFacts0(eq, left, right)
                    ?: compileExprVarFacts0(eq, right, left)
                    ?: C_ExprVarFacts.EMPTY

            return C_ExprVarFacts.of(
                    trueFacts = boolFacts.trueFacts,
                    falseFacts = boolFacts.falseFacts,
                    postFacts = postFacts
            )
        }

        private fun compileExprVarFacts0(eq: Boolean, left: V_Expr, right: V_Expr): C_ExprVarFacts? {
            val rightType = right.type()
            val facts = if (rightType == R_NullType) C_ExprVarFacts.forNullCheck(left, eq) else C_ExprVarFacts.EMPTY
            return if (facts.isEmpty()) null else facts
        }

        fun adaptOperands(left: V_Expr, right: V_Expr): Pair<V_Expr, V_Expr>? {
            val leftType = left.type()
            val rightType = right.type()

            if (leftType == R_NullType) {
                val right2 = right.asNullable()
                return Pair(left, right2)
            } else if (rightType == R_NullType) {
                val left2 = left.asNullable()
                return Pair(left2, right)
            } else {
                return null
            }
        }

        fun createVOp(eq: Boolean, type: R_Type): V_BinaryOp {
            val rOp: R_BinaryOp = if (eq) R_BinaryOp_Eq else R_BinaryOp_Ne
            val dbOp: Db_BinaryOp = if (eq) Db_BinaryOp_Eq else Db_BinaryOp_Ne
            val actDbOp = if (dbOpSupported(type)) dbOp else null
            return V_BinaryOp.of(R_BooleanType, rOp, actDbOp)
        }

        private fun dbOpSupported(type: R_Type): Boolean {
            return type == R_BooleanType
                    || type == R_IntegerType
                    || type == R_DecimalType
                    || type == R_TextType
                    || type == R_ByteArrayType
                    || type == R_RowidType
                    || type is R_EntityType
                    || type is R_EnumType
        }
    }
}

object C_BinOp_Eq: C_BinOp_EqNe(true)
object C_BinOp_Ne: C_BinOp_EqNe(false)

sealed class C_BinOp_EqNeRef(val eq: Boolean): C_BinOp_Common() {
    private val rOp: R_BinaryOp = if (eq) R_BinaryOp_EqRef else R_BinaryOp_NeRef

    final override fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        val type = if (left.isAssignableFrom(right)) left else if (right.isAssignableFrom(left)) right else null
        return if (type == null || !type.isReference()) null else V_BinaryOp.of(R_BooleanType, rOp, null)
    }

    override fun compileExprVarFacts(left: V_Expr, right: V_Expr)= C_BinOp_EqNe.compileExprVarFacts(eq, left, right)

    override fun adaptLeftRight(left: V_Expr, right: V_Expr): Pair<V_Expr, V_Expr> {
        val p = C_BinOp_EqNe.adaptOperands(left, right)
        return p ?: super.adaptLeftRight(left, right)
    }
}

object C_BinOp_EqRef: C_BinOp_EqNeRef(true)
object C_BinOp_NeRef: C_BinOp_EqNeRef(false)

sealed class C_BinOp_Cmp(val cmpOp: R_CmpOp, val dbOp: Db_BinaryOp): C_BinOp_Common() {
    override final fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        if (left != right) {
            return null
        }

        val rCmpType = R_CmpType.forCmpOpType(left)
        if (rCmpType == null) {
            return null
        }

        return V_BinaryOp.of(R_BooleanType, R_BinaryOp_Cmp(cmpOp, rCmpType), dbOp)
    }
}

object C_BinOp_Lt: C_BinOp_Cmp(R_CmpOp_Lt, Db_BinaryOp_Lt)
object C_BinOp_Gt: C_BinOp_Cmp(R_CmpOp_Gt, Db_BinaryOp_Gt)
object C_BinOp_Le: C_BinOp_Cmp(R_CmpOp_Le, Db_BinaryOp_Le)
object C_BinOp_Ge: C_BinOp_Cmp(R_CmpOp_Ge, Db_BinaryOp_Ge)

object C_BinOp_Plus: C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        if (left != right) {
            return null
        }

        return when (left) {
            R_IntegerType -> V_BinaryOp.of(R_IntegerType, R_BinaryOp_Add_Integer, Db_BinaryOp_Add_Integer)
            R_DecimalType -> V_BinaryOp.of(R_DecimalType, R_BinaryOp_Add_Decimal, Db_BinaryOp_Add_Decimal)
            R_TextType -> V_BinaryOp.of(R_TextType, R_BinaryOp_Concat_Text, Db_BinaryOp_Concat)
            R_ByteArrayType -> V_BinaryOp.of(R_ByteArrayType, R_BinaryOp_Concat_ByteArray, Db_BinaryOp_Concat)
            else -> null
        }
    }

    override fun adaptLeftRight(left: V_Expr, right: V_Expr): Pair<V_Expr, V_Expr> {
        val leftType = left.type()
        val rightType = right.type()

        if (leftType == R_TextType && rightType != R_TextType) {
            val right2 = adaptToText(right)
            return Pair(left, right2)
        } else if (leftType != R_TextType && rightType == R_TextType) {
            val left2 = adaptToText(left)
            return Pair(left2, right)
        } else {
            return super.adaptLeftRight(left, right)
        }
    }

    override fun adaptRight(leftType: R_Type, right: V_Expr): V_Expr {
        val rightType = right.type()
        return if (leftType == R_TextType && rightType != R_TextType) {
            adaptToText(right)
        } else {
            super.adaptRight(leftType, right)
        }
    }

    private fun adaptToText(vExpr: V_Expr): V_Expr {
        val varFacts = vExpr.varFacts().copyPostFacts()
        return V_ToTextExpr(vExpr, varFacts)
    }
}

sealed class C_BinOp_Arith(
        val rOpInt: R_BinaryOp,
        val dbOpInt: Db_BinaryOp,
        val rOpDec: R_BinaryOp,
        val dbOpDec: Db_BinaryOp
): C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        if (left != right) {
            return null
        }

        return when (left) {
            R_IntegerType -> V_BinaryOp.of(R_IntegerType, rOpInt, dbOpInt)
            R_DecimalType -> V_BinaryOp.of(R_DecimalType, rOpDec, dbOpDec)
            else -> null
        }
    }
}

object C_BinOp_Minus: C_BinOp_Arith(R_BinaryOp_Sub_Integer, Db_BinaryOp_Sub_Integer, R_BinaryOp_Sub_Decimal, Db_BinaryOp_Sub_Decimal)
object C_BinOp_Mul: C_BinOp_Arith(R_BinaryOp_Mul_Integer, Db_BinaryOp_Mul_Integer, R_BinaryOp_Mul_Decimal, Db_BinaryOp_Mul_Decimal)
object C_BinOp_Div: C_BinOp_Arith(R_BinaryOp_Div_Integer, Db_BinaryOp_Div_Integer, R_BinaryOp_Div_Decimal, Db_BinaryOp_Div_Decimal)
object C_BinOp_Mod: C_BinOp_Arith(R_BinaryOp_Mod_Integer, Db_BinaryOp_Mod_Integer, R_BinaryOp_Mod_Decimal, Db_BinaryOp_Mod_Decimal)

sealed class C_BinOp_Logic(val rOp: R_BinaryOp, val dbOp: Db_BinaryOp): C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        if (left != R_BooleanType || right != R_BooleanType) {
            return null
        }
        return V_BinaryOp.of(R_BooleanType, rOp, dbOp)
    }
}

object C_BinOp_And: C_BinOp_Logic(R_BinaryOp_And, Db_BinaryOp_And) {
    override fun compileExprVarFacts(left: V_Expr, right: V_Expr): C_ExprVarFacts {
        val leftFacts = left.varFacts()
        val rightFacts = right.varFacts()

        val trueFacts = leftFacts.trueFacts.and(rightFacts.trueFacts)
                .and(leftFacts.postFacts).and(rightFacts.postFacts)
        val falseFacts = leftFacts.postFacts

        return C_ExprVarFacts.of(trueFacts = trueFacts, falseFacts = falseFacts, postFacts = falseFacts)
    }

    override fun rightVarFacts(left: V_Expr): C_VarFacts {
        return left.varFacts().trueFacts
    }
}

object C_BinOp_Or: C_BinOp_Logic(R_BinaryOp_Or, Db_BinaryOp_Or) {
    override fun compileExprVarFacts(left: V_Expr, right: V_Expr): C_ExprVarFacts {
        val leftFacts = left.varFacts()
        val rightFacts = right.varFacts()

        val trueFacts = leftFacts.postFacts
        val falseFacts = leftFacts.falseFacts.and(rightFacts.falseFacts)
                .and(leftFacts.postFacts).and(rightFacts.postFacts)

        return C_ExprVarFacts.of(trueFacts = trueFacts, falseFacts = falseFacts)
    }

    override fun rightVarFacts(left: V_Expr): C_VarFacts {
        return left.varFacts().falseFacts
    }
}

object C_BinOp_In: C_BinOp() {
    override fun compile(left: V_Expr, right: V_Expr): V_Expr? {
        val leftType = left.type()
        val rightType = right.type()

        val op = compileOp(leftType, rightType)
        if (op == null) {
            return null
        }

        val varFacts = compileExprVarFacts(left, right)
        return V_BinaryExpr(left.pos, op, left, right, varFacts)
    }

    private fun compileOp(left: R_Type, right: R_Type): V_BinaryOp? {
        val pair = matchOp(right)
        if (pair == null) {
            return null
        }

        val (rOp, elemType) = pair
        if (left != elemType) {
            return null
        }

        return V_BinaryOp.of(R_BooleanType, rOp, null)
    }

    private fun matchOp(right: R_Type): Pair<R_BinaryOp, R_Type>? {
        return when (right) {
            is R_CollectionType -> Pair(R_BinaryOp_In_Collection, right.elementType)
            is R_VirtualListType -> Pair(R_BinaryOp_In_VirtualList, R_IntegerType)
            is R_VirtualSetType -> Pair(R_BinaryOp_In_VirtualSet, S_VirtualType.virtualMemberType(right.innerType.elementType))
            is R_MapType -> Pair(R_BinaryOp_In_Map, right.keyType)
            is R_VirtualMapType -> Pair(R_BinaryOp_In_Map, right.innerType.keyType)
            is R_RangeType -> Pair(R_BinaryOp_In_Range, R_IntegerType)
            else -> null
        }
    }
}

object C_BinOp_Elvis: C_BinOp() {
    override fun compile(left: V_Expr, right: V_Expr): V_Expr? {
        val left2 = left.asNullable()
        return compile0(left2, right)
    }

    private fun compile0(left: V_Expr, right: V_Expr): V_Expr? {
        val leftType = left.type()
        if (leftType == R_NullType) {
            return right
        }

        if (leftType !is R_NullableType) {
            return null
        }

        val resType = R_Type.commonTypeOpt(leftType.valueType, right.type())
        if (resType == null) {
            return null
        }

        val resVarFacts = C_ExprVarFacts.of(postFacts = left.varFacts().postFacts)
        return V_ElvisExpr(left.pos, resType, left, right, resVarFacts)
    }
}

class S_BinaryExprTail(val op: S_PosValue<S_BinaryOp>, val expr: S_Expr)

class S_BinaryExpr(val head: S_Expr, val tail: List<S_BinaryExprTail>): S_Expr(head.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val queue = LinkedList(tail)
        val tree = buildTree(head, queue, 0)
        val value = tree.compile(ctx)
        return C_VExpr(value)
    }

    private fun buildTree(left: S_Expr, tail: Queue<S_BinaryExprTail>, level: Int): BinExprNode {
        if (tail.isEmpty() || tail.peek().op.value.precedence() < level) {
            return TermBinExprNode(left)
        }

        var res = buildTree(left, tail, level + 1)

        while (!tail.isEmpty() && tail.peek().op.value.precedence() == level) {
            val next = tail.remove()
            val right = buildTree(next.expr, tail, level + 1)
            res = OpBinExprNode(next.op, res, right)
        }

        return res
    }

    private abstract class BinExprNode {
        abstract fun compile(ctx: C_ExprContext): V_Expr
    }

    private class TermBinExprNode(private val expr: S_Expr): BinExprNode() {
        override fun compile(ctx: C_ExprContext): V_Expr {
            val res = expr.compile(ctx)
            C_Utils.checkUnitType(expr.startPos, res.value().type(), "expr_operand_unit",
                    "Operand expression returns nothing")
            return res.value()
        }
    }

    private class OpBinExprNode(
            private val sOp: S_PosValue<S_BinaryOp>,
            private val left: BinExprNode,
            private val right: BinExprNode
    ): BinExprNode() {
        override fun compile(ctx: C_ExprContext): V_Expr {
            val leftValue = left.compile(ctx)

            val op = sOp.value
            val rightFacts = op.op.rightVarFacts(leftValue)
            val rightCtx = ctx.updateFacts(rightFacts)
            val rightValue = right.compile(rightCtx)

            return op.compile(ctx, sOp.pos, leftValue, rightValue)
        }
    }
}
