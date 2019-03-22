package net.postchain.rell.parser

import net.postchain.rell.model.*
import java.util.*

enum class S_BinaryOpCode(val op: S_BinaryOp) {
    EQ(S_BinaryOp_Eq),
    NE(S_BinaryOp_Ne),
    LE(S_BinaryOp_Le),
    GE(S_BinaryOp_Ge),
    LT(S_BinaryOp_Lt),
    GT(S_BinaryOp_Gt),
    EQ_REF(S_BinaryOp_EqRef),
    NE_REF(S_BinaryOp_NeRef),
    PLUS(S_BinaryOp_Plus),
    MINUS(S_BinaryOp_Minus),
    MUL(S_BinaryOp_Mul),
    DIV(S_BinaryOp_Div),
    MOD(S_BinaryOp_Mod),
    AND(S_BinaryOp_And),
    OR(S_BinaryOp_Or),
    IN(S_BinaryOp_In),
    ELVIS(S_BinaryOp_Elvis),
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

        private val PRECEDENCE_MAP: Map<S_BinaryOpCode, Int>

        init {
            val m = mutableMapOf<S_BinaryOpCode, Int>()

            for ((level, ops) in PRECEDENCE_LEVELS.withIndex()) {
                for (op in ops) {
                    check(op !in m)
                    m[op] = level
                }
            }
            check(m.keys.containsAll(S_BinaryOpCode.values().toSet())) {
                "Forgot to add new operator to the precedence table?"
            }

            PRECEDENCE_MAP = m.toMap()
        }
    }

    fun precedence(): Int = PRECEDENCE_MAP.getValue(this)
}

enum class S_AssignOpCode(val op: S_AssignOp) {
    EQ(S_AssignOp_Eq),
    PLUS(S_AssignOp_Op(S_BinaryOp_Plus)),
    MINUS(S_AssignOp_Op(S_BinaryOp_Minus)),
    MUL(S_AssignOp_Op(S_BinaryOp_Mul)),
    DIV(S_AssignOp_Op(S_BinaryOp_Div)),
    MOD(S_AssignOp_Op(S_BinaryOp_Mod)),
    ;
}

sealed class S_AssignOp {
    abstract fun compile(pos: S_Pos, dstExpr: C_Destination, expr: R_Expr): R_Statement
    abstract fun compileDbUpdate(pos: S_Pos, attr: R_Attrib, expr: Db_Expr): R_UpdateStatementWhat
}

object S_AssignOp_Eq: S_AssignOp() {
    override fun compile(pos: S_Pos, dstExpr: C_Destination, expr: R_Expr): R_Statement {
        val dstType = dstExpr.type()
        S_Type.match(dstType, expr.type, pos, "stmt_assign_type", "Assignment type missmatch")
        return dstExpr.compileAssignStatement(expr, null)
    }

    override fun compileDbUpdate(pos: S_Pos, attr: R_Attrib, expr: Db_Expr): R_UpdateStatementWhat {
        if (!attr.type.isAssignableFrom(expr.type)) {
            val name = attr.name
            throw C_Error(pos, "stmt_assign_type:$name:${attr.type.toStrictString()}:${expr.type.toStrictString()}",
                    "Type missmatch for '$name': ${expr.type.toStrictString()} instead of ${attr.type.toStrictString()}")
        }
        return R_UpdateStatementWhat(attr, expr, null)
    }
}

class S_AssignOp_Op(val op: S_BinaryOp_Common): S_AssignOp() {
    val code = op.code + "="

    override fun compile(pos: S_Pos, dstExpr: C_Destination, expr: R_Expr): R_Statement {
        val dstType = dstExpr.type()
        val binOp = compileBinOp(pos, dstType, expr.type)
        if (binOp.rOp == null) {
            throw S_BinaryOp.errTypeMissmatch(pos, code, dstType, expr.type)
        }

        val expr2 = S_BinaryOp_Common.convertExpr(pos, code, dstType, expr.type, expr, binOp.rightConv)
        val cOp = C_AssignOp(pos, code, binOp.rOp, binOp.dbOp)
        return dstExpr.compileAssignStatement(expr2, cOp)
    }

    override fun compileDbUpdate(pos: S_Pos, attr: R_Attrib, expr: Db_Expr): R_UpdateStatementWhat {
        val binOp = compileBinOp(pos, attr.type, expr.type)
        if (binOp.dbOp == null) {
            throw S_BinaryOp.errTypeMissmatch(pos, code, attr.type, expr.type)
        }

        val expr2 = S_BinaryOp_Common.convertExprDb(pos, code, attr.type, expr.type, expr, binOp.rightConv)
        return R_UpdateStatementWhat(attr, expr2, binOp.dbOp)
    }

    private fun compileBinOp(pos: S_Pos, left: R_Type, right: R_Type): C_BinOpType {
        val binOp = op.compileOp(left, right)
        if (binOp == null || binOp.leftConv != null || binOp.resType != left) {
            throw S_BinaryOp.errTypeMissmatch(pos, code, left, right)
        }
        return binOp
    }
}

sealed class S_BinaryOp(val code: String) {
    abstract fun compile(pos: S_Pos, left: C_Expr, right: C_Expr): C_Expr

    fun errTypeMissmatch(pos: S_Pos, leftType: R_Type, rightType: R_Type): C_Error {
        return errTypeMissmatch(pos, code, leftType, rightType)
    }

    companion object {
        fun errTypeMissmatch(pos: S_Pos, op: String, leftType: R_Type, rightType: R_Type): C_Error {
            return C_Error(pos, "binop_operand_type:$op:$leftType:$rightType",
                    "Wrong operand types for '$op': $leftType, $rightType")
        }
    }
}

sealed class S_BinaryOp_Base(code: String): S_BinaryOp(code) {
    abstract fun compile(pos: S_Pos, left: R_Expr, right: R_Expr): R_Expr
    abstract fun compileDb(pos: S_Pos, left: Db_Expr, right: Db_Expr): Db_Expr

    override fun compile(pos: S_Pos, left: C_Expr, right: C_Expr): C_Expr {
        val leftVal = left.value()
        val rightVal = right.value()
        val leftDb = leftVal.isDb()
        val rightDb = rightVal.isDb()
        val db = leftDb || rightDb

        if (db) {
            val dbLeft = leftVal.toDbExpr()
            val dbRight = rightVal.toDbExpr()
            val dbExpr = compileDb(pos, dbLeft, dbRight)
            return C_DbExpr(left.startPos(), dbExpr)
        } else {
            val rLeft = leftVal.toRExpr()
            val rRight = rightVal.toRExpr()
            val rExpr = compile(pos, rLeft, rRight)
            return C_RExpr(left.startPos(), rExpr)
        }
    }
}

sealed class S_BinaryOp_Common(code: String): S_BinaryOp_Base(code) {
    abstract fun compileOp(left: R_Type, right: R_Type): C_BinOpType?

    override fun compile(pos: S_Pos, left: R_Expr, right: R_Expr): R_Expr {
        val lType = left.type
        val rType = right.type

        val op = compileOp(lType, rType)
        if (op == null || op.rOp == null) {
            throw errTypeMissmatch(pos, lType, rType)
        }

        val left2 = convertExpr(pos, code, lType, rType, left, op.leftConv)
        val right2 = convertExpr(pos, code, lType, rType, right, op.rightConv)

        return R_BinaryExpr(op.resType, op.rOp, left2, right2)
    }

    override fun compileDb(pos: S_Pos, left: Db_Expr, right: Db_Expr): Db_Expr {
        val lType = left.type
        val rType = right.type

        val op = compileOp(lType, rType)
        if (op == null || op.dbOp == null) {
            throw errTypeMissmatch(pos, lType, rType)
        }

        val left2 = convertExprDb(pos, code, lType, rType, left, op.leftConv)
        val right2 = convertExprDb(pos, code, lType, rType, right, op.rightConv)

        return Db_BinaryExpr(op.resType, op.dbOp, left2, right2)
    }

    companion object {
        fun convertExpr(pos: S_Pos, op: String, lType: R_Type, rType: R_Type, expr: R_Expr, conv: C_OperandConversion?): R_Expr {
            if (conv == null) {
                return expr
            }

            val r = conv.r
            if (r == null) {
                throw errTypeMissmatch(pos, op, lType, rType)
            } else {
                return r(expr)
            }
        }

        fun convertExprDb(pos: S_Pos, op: String, lType: R_Type, rType: R_Type, expr: Db_Expr, conv: C_OperandConversion?): Db_Expr {
            if (conv == null) {
                return expr
            }

            val db = conv.db
            if (db == null) {
                throw errTypeMissmatch(pos, op, lType, rType)
            } else {
                return db(expr)
            }
        }
    }
}

sealed class S_BinaryOp_EqNe(val rOp: R_BinaryOp, val dbOp: Db_BinaryOp): S_BinaryOp_Common(rOp.code) {
    override final fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
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

    companion object {
        fun checkTypes(left: R_Type, right: R_Type): Boolean {
            val op = S_BinaryOp_Eq.compileOp(left, right)
            return op != null
        }

        fun checkTypesDb(left: R_Type, right: R_Type): Boolean {
            val op = S_BinaryOp_Eq.compileOp(left, right)
            return op != null && op.dbOp != null
        }

        private fun calcCommonType(left: R_Type, right: R_Type): R_Type? {
            return if (left == R_NullType && right is R_NullableType) {
                right
            } else {
                if (left == right) left else null
            }
        }
    }
}

object S_BinaryOp_Eq: S_BinaryOp_EqNe(R_BinaryOp_Eq, Db_BinaryOp_Eq)
object S_BinaryOp_Ne: S_BinaryOp_EqNe(R_BinaryOp_Ne, Db_BinaryOp_Ne)

sealed class S_BinaryOp_EqNeRef(val rOp: R_BinaryOp): S_BinaryOp_Common(rOp.code) {
    override final fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        val type = calcCommonType(left, right) ?: calcCommonType(right, left)
        return if (type == null || !type.isReference()) null else C_BinOpType(R_BooleanType, rOp, null)
    }

    companion object {
        private fun calcCommonType(left: R_Type, right: R_Type): R_Type? {
            return if (left == R_NullType && right is R_NullableType) {
                right
            } else if (left is R_NullableType && left.valueType == right) {
                left
            } else if (left == right) {
                left
            } else {
                null
            }
        }
    }
}

object S_BinaryOp_EqRef: S_BinaryOp_EqNeRef(R_BinaryOp_EqRef)
object S_BinaryOp_NeRef: S_BinaryOp_EqNeRef(R_BinaryOp_NeRef)

sealed class S_BinaryOp_Cmp(val cmpOp: R_CmpOp, val dbOp: Db_BinaryOp): S_BinaryOp_Common(cmpOp.code) {
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

sealed class S_BinaryOp_LtGt(cmpOp: R_CmpOp, dbOp: Db_BinaryOp): S_BinaryOp_Cmp(cmpOp, dbOp) {
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

object S_BinaryOp_Lt: S_BinaryOp_LtGt(R_CmpOp_Lt, Db_BinaryOp_Lt)
object S_BinaryOp_Gt: S_BinaryOp_LtGt(R_CmpOp_Gt, Db_BinaryOp_Gt)
object S_BinaryOp_Le: S_BinaryOp_LtGt(R_CmpOp_Le, Db_BinaryOp_Le)
object S_BinaryOp_Ge: S_BinaryOp_LtGt(R_CmpOp_Ge, Db_BinaryOp_Ge)

object S_BinaryOp_Plus: S_BinaryOp_Common("+") {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left == right) {
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

        if (left == R_TextType) {
            val conv = convertText(right)
            if (conv != null) return C_BinOpType(R_TextType, R_BinaryOp_Concat_Text, Db_BinaryOp_Concat, null, conv)
        } else if (right == R_TextType) {
            val conv = convertText(left)
            if (conv != null) return C_BinOpType(R_TextType, R_BinaryOp_Concat_Text, Db_BinaryOp_Concat, conv, null)
        }

        return null
    }

    private fun convertText(type: R_Type): C_OperandConversion? {
        var dbConv = if (type == R_BooleanType || type == R_IntegerType || type == R_JSONType || type is R_ClassType) {
            S_BinaryOp_Plus::convToTextDb
        } else {
            null
        }

        return C_OperandConversion(S_BinaryOp_Plus::convToText, dbConv)
    }

    private fun convToText(e: R_Expr): R_Expr = R_SysCallExpr(R_TextType, R_SysFn_ToString, listOf(e))
    private fun convToTextDb(e: Db_Expr): Db_Expr = Db_CallExpr(R_TextType, Db_SysFn_ToString, listOf(e))
}

sealed class S_BinaryOp_Arith(val rOp: R_BinaryOp, val dbOp: Db_BinaryOp): S_BinaryOp_Common(rOp.code) {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left != R_IntegerType || right != R_IntegerType) {
            return null
        }
        return C_BinOpType(R_IntegerType, rOp, dbOp)
    }
}

object S_BinaryOp_Minus: S_BinaryOp_Arith(R_BinaryOp_Sub, Db_BinaryOp_Sub)
object S_BinaryOp_Mul: S_BinaryOp_Arith(R_BinaryOp_Mul, Db_BinaryOp_Mul)
object S_BinaryOp_Div: S_BinaryOp_Arith(R_BinaryOp_Div, Db_BinaryOp_Div)
object S_BinaryOp_Mod: S_BinaryOp_Arith(R_BinaryOp_Mod, Db_BinaryOp_Mod)

sealed class S_BinaryOp_Logic(val rOp: R_BinaryOp, val dbOp: Db_BinaryOp): S_BinaryOp_Common(rOp.code) {
    override fun compileOp(left: R_Type, right: R_Type): C_BinOpType? {
        if (left != R_BooleanType || right != R_BooleanType) {
            return null
        }
        return C_BinOpType(R_BooleanType, rOp, dbOp)
    }
}

object S_BinaryOp_And: S_BinaryOp_Logic(R_BinaryOp_And, Db_BinaryOp_And)
object S_BinaryOp_Or: S_BinaryOp_Logic(R_BinaryOp_Or, Db_BinaryOp_Or)

object S_BinaryOp_In: S_BinaryOp_Common("in") {
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
        if (right is R_CollectionType) {
            return Pair(R_BinaryOp_In_Collection, right.elementType)
        } else if (right is R_MapType) {
            return Pair(R_BinaryOp_In_Map, right.keyType)
        } else if (right is R_RangeType) {
            return Pair(R_BinaryOp_In_Range, R_IntegerType)
        } else {
            return null
        }
    }
}

object S_BinaryOp_Elvis: S_BinaryOp_Base("?:") {
    override fun compile(pos: S_Pos, left: R_Expr, right: R_Expr): R_Expr {
        val leftType = left.type
        if (leftType == R_NullType) {
            return right
        }

        if (leftType !is R_NullableType) {
            throw errTypeMissmatch(pos, leftType, right.type)
        }

        val resType = R_Type.commonTypeOpt(leftType.valueType, right.type)
        if (resType == null) {
            throw errTypeMissmatch(pos, leftType, right.type)
        }

        return R_ElvisExpr(resType, left, right)
    }

    override fun compileDb(pos: S_Pos, left: Db_Expr, right: Db_Expr): Db_Expr {
        throw errTypeMissmatch(pos, left.type, right.type)
    }
}

class S_BinaryExprTail(val op: S_Node<S_BinaryOpCode>, val expr: S_Expr)

class S_BinaryExpr(val head: S_Expr, val tail: List<S_BinaryExprTail>): S_Expr(head.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val queue = LinkedList(tail)
        val expr = buildTree(ctx, head, queue, 0)
        return expr
    }

    private fun buildTree(ctx: C_ExprContext, left: S_Expr, tail: Queue<S_BinaryExprTail>, level: Int): C_Expr {
        if (tail.isEmpty() || tail.peek().op.value.precedence() < level) {
            val res = left.compile(ctx)
            C_Utils.checkUnitType(left.startPos, res.value().type(), "expr_operand_unit", "Operand expression returns nothing")
            return res
        }

        var res = buildTree(ctx, left, tail, level + 1)

        while (!tail.isEmpty() && tail.peek().op.value.precedence() == level) {
            val next = tail.remove()
            val right = buildTree(ctx, next.expr, tail, level + 1)
            res = next.op.value.op.compile(next.op.pos, res, right)
        }

        return res
    }
}
