package net.postchain.rell.parser

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
            check(m.keys.containsAll(S_BinaryOp.values().toSet())) {
                "Forgot to add new operator to the precedence table?"
            }

            PRECEDENCE_MAP = m.toMap()
        }
    }

    fun precedence(): Int = PRECEDENCE_MAP.getValue(this)

    fun compile(pos: S_Pos, left: C_Value, right: C_Value): C_Value {
        val value = op.compile(pos, left, right)
        if (value != null) {
            return value
        }
        throw C_BinOp.errTypeMismatch(pos, code, left.type(), right.type())
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
    abstract fun compile(ctx: C_ExprContext, pos: S_Pos, dstValue: C_Value, srcValue: C_Value): C_Statement
    abstract fun compileDbUpdate(pos: S_Pos, attr: R_Attrib, srcValue: C_Value): R_UpdateStatementWhat

    protected open fun compileVarFacts(
            dstValue: C_Value,
            dstExpr: C_Destination,
            srcValue: C_Value,
            srcType: R_Type
    ): C_VarFacts {
        return dstValue.varFacts().postFacts.and(srcValue.varFacts().postFacts)
    }
}

object S_AssignOp_Eq: S_AssignOp() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos, dstValue: C_Value, srcValue: C_Value): C_Statement {
        val dstExpr = dstValue.destination(ctx)
        val dstType = dstExpr.type()
        val rSrcExpr = srcValue.toRExpr()
        S_Type.match(dstType, rSrcExpr.type, pos, "stmt_assign_type", "Assignment type mismatch")

        val rStmt = dstExpr.compileAssignStatement(rSrcExpr, null)

        val varFacts = compileVarFacts(dstValue, dstExpr, srcValue, rSrcExpr.type)
        return C_Statement(rStmt, false, varFacts)
    }

    override fun compileVarFacts(dstValue: C_Value, dstExpr: C_Destination, srcValue: C_Value, srcType: R_Type): C_VarFacts {
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

    override fun compileDbUpdate(pos: S_Pos, attr: R_Attrib, srcValue: C_Value): R_UpdateStatementWhat {
        val expr = srcValue.toDbExpr()
        if (!attr.type.isAssignableFrom(expr.type)) {
            val name = attr.name
            throw C_Error(pos, "stmt_assign_type:$name:${attr.type.toStrictString()}:${expr.type.toStrictString()}",
                    "Type mismatch for '$name': ${expr.type.toStrictString()} instead of ${attr.type.toStrictString()}")
        }
        return R_UpdateStatementWhat(attr, expr, null)
    }
}

class S_AssignOp_Op(val code: String, val op: C_BinOp_Common): S_AssignOp() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos, dstValue: C_Value, srcValue: C_Value): C_Statement {
        val dstExpr = dstValue.destination(ctx)
        val dstType = dstExpr.effectiveType()

        val srcValue2 = op.adaptRight(dstType, srcValue)
        val rSrcExpr = srcValue2.toRExpr()
        val srcType = rSrcExpr.type

        val binOp = compileBinOp(pos, dstType, srcType)
        if (binOp?.rOp == null) {
            throw C_BinOp.errTypeMismatch(pos, code, dstType, srcType)
        }

        val cOp = C_AssignOp(pos, code, binOp.rOp, binOp.dbOp)
        val rStmt = dstExpr.compileAssignStatement(rSrcExpr, cOp)

        val varFacts = compileVarFacts(dstValue, dstExpr, srcValue, rSrcExpr.type)
        return C_Statement(rStmt, false, varFacts)
    }

    override fun compileDbUpdate(pos: S_Pos, attr: R_Attrib, srcValue: C_Value): R_UpdateStatementWhat {
        val srcValue2 = op.adaptRight(attr.type, srcValue)
        val dbSrcExpr = srcValue2.toDbExpr()
        val srcType = dbSrcExpr.type

        val binOp = compileBinOp(pos, attr.type, srcType)
        if (binOp.dbOp == null) {
            throw C_BinOp.errTypeMismatch(pos, code, attr.type, srcType)
        }

        return R_UpdateStatementWhat(attr, dbSrcExpr, binOp.dbOp)
    }

    private fun compileBinOp(pos: S_Pos, left: R_Type, right: R_Type): C_BinOpType {
        val binOp = op.compileOp(left, right)
        if (binOp == null || binOp.resType != left) {
            throw C_BinOp.errTypeMismatch(pos, code, left, right)
        }
        return binOp
    }
}

sealed class C_BinOp {
    protected abstract fun compileR(pos: S_Pos, left: R_Expr, right: R_Expr): R_Expr?
    protected abstract fun compileDb(pos: S_Pos, left: Db_Expr, right: Db_Expr): Db_Expr?

    fun compile(pos: S_Pos, left: C_Value, right: C_Value): C_Value? {
        val (left2, right2) = adaptLeftRight(left, right)
        return compile0(pos, left2, right2)
    }

    private fun compile0(pos: S_Pos, left: C_Value, right: C_Value): C_Value? {
        val exprVarFacts = compileExprVarFacts(left, right)

        val leftDb = left.isDb()
        val rightDb = right.isDb()
        val db = leftDb || rightDb

        if (db) {
            val dbLeft = left.toDbExpr()
            val dbRight = right.toDbExpr()
            val dbExpr = compileDb(pos, dbLeft, dbRight)
            dbExpr ?: return null
            return C_DbValue(left.pos, dbExpr, exprVarFacts)
        } else {
            val rLeft = left.toRExpr()
            val rRight = right.toRExpr()
            val rExpr = compileR(pos, rLeft, rRight)
            rExpr ?: return null
            return C_RValue(left.pos, rExpr, exprVarFacts)
        }
    }

    open fun rightVarFacts(left: C_Value): C_VarFacts = C_VarFacts.EMPTY

    open fun adaptLeftRight(left: C_Value, right: C_Value) = Pair(left, right)
    open fun adaptRight(leftType: R_Type, right: C_Value) = right

    protected open fun compileExprVarFacts(left: C_Value, right: C_Value): C_ExprVarFacts {
        val postFacts = left.varFacts().postFacts.and(right.varFacts().postFacts)
        return C_ExprVarFacts.of(postFacts = postFacts)
    }

    companion object {
        fun errTypeMismatch(pos: S_Pos, op: String, leftType: R_Type, rightType: R_Type): C_Error {
            return C_Error(pos, "binop_operand_type:$op:$leftType:$rightType",
                    "Wrong operand types for '$op': $leftType, $rightType")
        }
    }
}

sealed class C_BinOp_Common: C_BinOp() {
    abstract fun compileOp(left: R_Type, right: R_Type): C_BinOpType?

    override fun compileR(pos: S_Pos, left: R_Expr, right: R_Expr): R_Expr? {
        val op = compileOp(left.type, right.type)
        if (op?.rOp == null) {
            return null
        }
        return R_BinaryExpr(op.resType, op.rOp, left, right)
    }

    override fun compileDb(pos: S_Pos, left: Db_Expr, right: Db_Expr): Db_Expr? {
        val op = compileOp(left.type, right.type)
        if (op?.dbOp == null) {
            return null
        }
        return Db_BinaryExpr(op.resType, op.dbOp, left, right)
    }
}

sealed class C_BinOp_EqNe(private val eq: Boolean): C_BinOp_Common() {
    private val rOp: R_BinaryOp = if (eq) R_BinaryOp_Eq else R_BinaryOp_Ne
    private val dbOp: Db_BinaryOp = if (eq) Db_BinaryOp_Eq else Db_BinaryOp_Ne

    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        val type = calcCommonType(left, right) ?: calcCommonType(right, left)
        if (type == null || type is R_ObjectType) {
            return null
        }

        val actDbOp = if (dbOpSupported(type)) dbOp else null
        return C_BinOpType(R_BooleanType, rOp, actDbOp)
    }

    private fun dbOpSupported(type: R_Type): Boolean {
        return type == R_BooleanType
                || type == R_IntegerType
                || type == R_TextType
                || type == R_ByteArrayType
                || type is R_ClassType
                || type is R_EnumType
    }

    override fun compileExprVarFacts(left: C_Value, right: C_Value) = compileExprVarFacts(eq, left, right)
    override fun adaptLeftRight(left: C_Value, right: C_Value) = adaptOperands(left, right)

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

        fun compileExprVarFacts(eq: Boolean, left: C_Value, right: C_Value): C_ExprVarFacts {
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

        private fun compileExprVarFacts0(eq: Boolean, left: C_Value, right: C_Value): C_ExprVarFacts? {
            val rightType = right.type()
            val facts = if (rightType == R_NullType) C_ExprVarFacts.forNullCheck(left, eq) else C_ExprVarFacts.EMPTY
            return if (facts.isEmpty()) null else facts
        }

        fun adaptOperands(left: C_Value, right: C_Value): Pair<C_Value, C_Value> {
            var left2 = left
            var right2 = right

            val leftType = left.type()
            val rightType = right.type()
            if (leftType == R_NullType) {
                right2 = right.asNullable()
            } else if (rightType == R_NullType) {
                left2 = left.asNullable()
            }

            return Pair(left2, right2)
        }
    }
}

object C_BinOp_Eq: C_BinOp_EqNe(true)
object C_BinOp_Ne: C_BinOp_EqNe(false)

sealed class C_BinOp_EqNeRef(val eq: Boolean): C_BinOp_Common() {
    private val rOp: R_BinaryOp = if (eq) R_BinaryOp_EqRef else R_BinaryOp_NeRef

    final override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        val type = if (left.isAssignableFrom(right)) left else if (right.isAssignableFrom(left)) right else null
        return if (type == null || !type.isReference()) null else C_BinOpType(R_BooleanType, rOp, null)
    }

    override fun compileExprVarFacts(left: C_Value, right: C_Value)= C_BinOp_EqNe.compileExprVarFacts(eq, left, right)
    override fun adaptLeftRight(left: C_Value, right: C_Value) = C_BinOp_EqNe.adaptOperands(left, right)
}

object C_BinOp_EqRef: C_BinOp_EqNeRef(true)
object C_BinOp_NeRef: C_BinOp_EqNeRef(false)

sealed class C_BinOp_Cmp(val cmpOp: R_CmpOp, val dbOp: Db_BinaryOp): C_BinOp_Common() {
    abstract fun compileType(type: R_Type): R_CmpType?

    override final fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left != right) {
            return null
        }

        val rCmpType = compileType(left)
        if (rCmpType == null) {
            return null
        }

        return C_BinOpType(R_BooleanType, R_BinaryOp_Cmp(cmpOp, rCmpType), dbOp)
    }
}

sealed class C_BinOp_LtGt(cmpOp: R_CmpOp, dbOp: Db_BinaryOp): C_BinOp_Cmp(cmpOp, dbOp) {
    override fun compileType(type: R_Type): R_CmpType? {
        if (type == R_IntegerType) {
            return R_CmpType_Integer
        } else if (type == R_TextType) {
            return R_CmpType_Text
        } else if (type == R_ByteArrayType) {
            return R_CmpType_ByteArray
        } else if (type is R_ClassType) {
            return R_CmpType_Class
        } else if (type is R_EnumType) {
            return R_CmpType_Enum
        } else {
            return null
        }
    }
}

object C_BinOp_Lt: C_BinOp_LtGt(R_CmpOp_Lt, Db_BinaryOp_Lt)
object C_BinOp_Gt: C_BinOp_LtGt(R_CmpOp_Gt, Db_BinaryOp_Gt)
object C_BinOp_Le: C_BinOp_LtGt(R_CmpOp_Le, Db_BinaryOp_Le)
object C_BinOp_Ge: C_BinOp_LtGt(R_CmpOp_Ge, Db_BinaryOp_Ge)

object C_BinOp_Plus: C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left != right) {
            return null
        }

        if (left == R_IntegerType) {
            return C_BinOpType(R_IntegerType, R_BinaryOp_Add, Db_BinaryOp_Add)
        } else if (left == R_TextType) {
            return C_BinOpType(R_TextType, R_BinaryOp_Concat_Text, Db_BinaryOp_Concat)
        } else if (left == R_ByteArrayType) {
            return C_BinOpType(R_ByteArrayType, R_BinaryOp_Concat_ByteArray, Db_BinaryOp_Concat)
        } else {
            return null
        }
    }

    override fun adaptLeftRight(left: C_Value, right: C_Value): Pair<C_Value, C_Value> {
        var resLeft = left
        var resRight = right

        val leftType = left.type()
        val rightType = right.type()

        if (leftType == R_TextType && rightType != R_TextType) {
            resRight = convertToText(right, rightType)
        } else if (leftType != R_TextType && rightType == R_TextType) {
            resLeft = convertToText(left, leftType)
        }

        return Pair(resLeft, resRight)
    }

    override fun adaptRight(leftType: R_Type, right: C_Value): C_Value {
        val rightType = right.type()
        return if (leftType == R_TextType && rightType != R_TextType) convertToText(right, rightType) else right
    }

    private fun convertToText(value: C_Value, type: R_Type): C_Value {
        val db = value.isDb()
        if (!db) {
            val rExpr = value.toRExpr()
            val rResExpr = R_SysCallExpr(R_TextType, R_SysFn_ToString, listOf(rExpr))
            return C_RValue(value.pos, rResExpr, value.varFacts().copyPostFacts())
        } else if (type == R_BooleanType || type == R_IntegerType || type == R_JsonType || type is R_ClassType) {
            val dbExpr = value.toDbExpr()
            val dbResExpr = Db_CallExpr(R_TextType, Db_SysFn_ToString, listOf(dbExpr))
            return C_DbValue(value.pos, dbResExpr, value.varFacts().copyPostFacts())
        } else {
            return value
        }
    }
}

sealed class C_BinOp_Arith(val rOp: R_BinaryOp, val dbOp: Db_BinaryOp): C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left != R_IntegerType || right != R_IntegerType) {
            return null
        }
        return C_BinOpType(R_IntegerType, rOp, dbOp)
    }
}

object C_BinOp_Minus: C_BinOp_Arith(R_BinaryOp_Sub, Db_BinaryOp_Sub)
object C_BinOp_Mul: C_BinOp_Arith(R_BinaryOp_Mul, Db_BinaryOp_Mul)
object C_BinOp_Div: C_BinOp_Arith(R_BinaryOp_Div, Db_BinaryOp_Div)
object C_BinOp_Mod: C_BinOp_Arith(R_BinaryOp_Mod, Db_BinaryOp_Mod)

sealed class C_BinOp_Logic(val rOp: R_BinaryOp, val dbOp: Db_BinaryOp): C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left != R_BooleanType || right != R_BooleanType) {
            return null
        }
        return C_BinOpType(R_BooleanType, rOp, dbOp)
    }
}

object C_BinOp_And: C_BinOp_Logic(R_BinaryOp_And, Db_BinaryOp_And) {
    override fun compileExprVarFacts(left: C_Value, right: C_Value): C_ExprVarFacts {
        val leftFacts = left.varFacts()
        val rightFacts = right.varFacts()

        val trueFacts = leftFacts.trueFacts.and(rightFacts.trueFacts)
                .and(leftFacts.postFacts).and(rightFacts.postFacts)
        val falseFacts = leftFacts.postFacts

        return C_ExprVarFacts.of(trueFacts = trueFacts, falseFacts = falseFacts, postFacts = falseFacts)
    }

    override fun rightVarFacts(left: C_Value): C_VarFacts {
        return left.varFacts().trueFacts
    }
}

object C_BinOp_Or: C_BinOp_Logic(R_BinaryOp_Or, Db_BinaryOp_Or) {
    override fun compileExprVarFacts(left: C_Value, right: C_Value): C_ExprVarFacts {
        val leftFacts = left.varFacts()
        val rightFacts = right.varFacts()

        val trueFacts = leftFacts.postFacts
        val falseFacts = leftFacts.falseFacts.and(rightFacts.falseFacts)
                .and(leftFacts.postFacts).and(rightFacts.postFacts)

        return C_ExprVarFacts.of(trueFacts = trueFacts, falseFacts = falseFacts)
    }

    override fun rightVarFacts(left: C_Value): C_VarFacts {
        return left.varFacts().falseFacts
    }
}

object C_BinOp_In: C_BinOp_Common() {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        val pair = matchOp(right)
        if (pair == null) {
            return null
        }

        val (rOp, elemType) = pair
        if (left != elemType) {
            return null
        }

        return C_BinOpType(R_BooleanType, rOp, null)
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
    override fun compileR(pos: S_Pos, left: R_Expr, right: R_Expr): R_Expr? {
        val leftType = left.type
        if (leftType == R_NullType) {
            return right
        }

        if (leftType !is R_NullableType) {
            return null
        }

        val resType = R_Type.commonTypeOpt(leftType.valueType, right.type)
        if (resType == null) {
            return null
        }

        return R_ElvisExpr(resType, left, right)
    }

    override fun compileDb(pos: S_Pos, left: Db_Expr, right: Db_Expr) = null

    override fun adaptLeftRight(left: C_Value, right: C_Value) = Pair(left.asNullable(), right)

    override fun compileExprVarFacts(left: C_Value, right: C_Value): C_ExprVarFacts {
        return C_ExprVarFacts.of(postFacts = left.varFacts().postFacts)
    }
}

class S_BinaryExprTail(val op: S_Node<S_BinaryOp>, val expr: S_Expr)

class S_BinaryExpr(val head: S_Expr, val tail: List<S_BinaryExprTail>): S_Expr(head.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val queue = LinkedList(tail)
        val tree = buildTree(head, queue, 0)
        val value = tree.compile(ctx)
        return C_ValueExpr(value)
    }

    private fun buildTree(left: S_Expr, tail: Queue<S_BinaryExprTail>, level: Int): C_ExprNode {
        if (tail.isEmpty() || tail.peek().op.value.precedence() < level) {
            return C_TermExprNode(left)
        }

        var res = buildTree(left, tail, level + 1)

        while (!tail.isEmpty() && tail.peek().op.value.precedence() == level) {
            val next = tail.remove()
            val right = buildTree(next.expr, tail, level + 1)
            res = C_OpExprNode(next.op, res, right)
        }

        return res
    }

    private abstract class C_ExprNode {
        abstract fun compile(ctx: C_ExprContext): C_Value
    }

    private class C_TermExprNode(private val expr: S_Expr): C_ExprNode() {
        override fun compile(ctx: C_ExprContext): C_Value {
            val res = expr.compile(ctx)
            C_Utils.checkUnitType(expr.startPos, res.value().type(), "expr_operand_unit",
                    "Operand expression returns nothing")
            return res.value()
        }
    }

    private class C_OpExprNode(
            private val sOp: S_Node<S_BinaryOp>,
            private val left: C_ExprNode,
            private val right: C_ExprNode
    ): C_ExprNode() {
        override fun compile(ctx: C_ExprContext): C_Value {
            val leftValue = left.compile(ctx)

            val op = sOp.value
            val rightFacts = op.op.rightVarFacts(leftValue)
            val rightCtx = ctx.updateFacts(rightFacts)
            val rightValue = right.compile(rightCtx)

            return op.compile(sOp.pos, leftValue, rightValue)
        }
    }
}
