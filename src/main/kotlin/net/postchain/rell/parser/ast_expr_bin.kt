package net.postchain.rell.parser

import net.postchain.rell.model.*
import java.util.*

enum class S_BinaryOpCode(val op: S_BinaryOp) {
    SINGLE_EQ(S_BinaryOp_SingleEq),
    EQ(S_BinaryOp_Eq),
    NE(S_BinaryOp_Ne),
    LE(S_BinaryOp_Le),
    GE(S_BinaryOp_Ge),
    LT(S_BinaryOp_Lt),
    GT(S_BinaryOp_Gt),
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
                listOf(SINGLE_EQ, EQ, NE, LE, GE, LT, GT),
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

internal sealed class S_AssignOp {
    abstract fun compile(pos: S_Pos, dstExpr: RDestinationExpr, expr: RExpr): RStatement
    abstract fun compileDbUpdate(pos: S_Pos, attr: RAttrib, expr: DbExpr): RUpdateStatementWhat
}

internal object S_AssignOp_Eq: S_AssignOp() {
    override fun compile(pos: S_Pos, dstExpr: RDestinationExpr, expr: RExpr): RStatement {
        S_Type.match(dstExpr.type, expr.type, pos, "stmt_assign_type", "Assignment type missmatch")
        return RAssignStatement(dstExpr, expr, null)
    }

    override fun compileDbUpdate(pos: S_Pos, attr: RAttrib, expr: DbExpr): RUpdateStatementWhat {
        if (!attr.type.isAssignableFrom(expr.type)) {
            val name = attr.name
            throw CtError(pos, "stmt_assign_type:$name:${attr.type.toStrictString()}:${expr.type.toStrictString()}",
                    "Type missmatch for '$name': ${expr.type.toStrictString()} instead of ${attr.type.toStrictString()}")
        }
        return RUpdateStatementWhat(attr, expr, null)
    }
}

internal class S_AssignOp_Op(val op: S_BinaryOp_Common): S_AssignOp() {
    val code = op.code + "="

    override fun compile(pos: S_Pos, dstExpr: RDestinationExpr, expr: RExpr): RStatement {
        val binOp = compileBinOp(pos, dstExpr.type, expr.type)
        if (binOp.rOp == null) {
            throw S_BinaryOp.errTypeMissmatch(pos, code, dstExpr.type, expr.type)
        }

        val expr2 = S_BinaryOp_Common.convertExpr(pos, code, dstExpr.type, expr.type, expr, binOp.rightConv)
        return RAssignStatement(dstExpr, expr2, binOp.rOp)
    }

    override fun compileDbUpdate(pos: S_Pos, attr: RAttrib, expr: DbExpr): RUpdateStatementWhat {
        val binOp = compileBinOp(pos, attr.type, expr.type)
        if (binOp.dbOp == null) {
            throw S_BinaryOp.errTypeMissmatch(pos, code, attr.type, expr.type)
        }

        val expr2 = S_BinaryOp_Common.convertExprDb(pos, code, attr.type, expr.type, expr, binOp.rightConv)
        return RUpdateStatementWhat(attr, expr2, binOp.dbOp)
    }

    private fun compileBinOp(pos: S_Pos, left: RType, right: RType): S_BinOpType {
        val binOp = op.compileOp(left, right)
        if (binOp == null || binOp.leftConv != null || binOp.resType != left) {
            throw S_BinaryOp.errTypeMissmatch(pos, code, left, right)
        }
        return binOp
    }
}

typealias S_OperandConversion_R = (RExpr) -> RExpr
typealias S_OperandConversion_Db = (DbExpr) -> DbExpr

class S_OperandConversion(val r: S_OperandConversion_R?, val db: S_OperandConversion_Db?)

class S_BinOpType(
        val resType: RType,
        val rOp: RBinaryOp?,
        val dbOp: DbBinaryOp?,
        val leftConv: S_OperandConversion? = null,
        val rightConv: S_OperandConversion? = null
)

sealed class S_BinaryOp(val code: String) {
    internal abstract fun compile(pos: S_Pos, ctx: CtExprContext, left: C_BinaryExprNode, right: C_BinaryExprNode): RExpr
    internal abstract fun compileDb(pos: S_Pos, ctx: CtDbExprContext, left: C_BinaryExprNode, right: C_BinaryExprNode): DbExpr

    fun errTypeMissmatch(pos: S_Pos, leftType: RType, rightType: RType): CtError {
        return errTypeMissmatch(pos, code, leftType, rightType)
    }

    companion object {
        fun errTypeMissmatch(pos: S_Pos, op: String, leftType: RType, rightType: RType): CtError {
            return CtError(pos, "binop_operand_type:$op:$leftType:$rightType",
                    "Wrong operand types for '$op': $leftType, $rightType")
        }
    }
}

sealed class S_BinaryOp_Base(code: String): S_BinaryOp(code) {
    abstract fun compile(pos: S_Pos, left: RExpr, right: RExpr): RExpr
    abstract fun compileDb(pos: S_Pos, left: DbExpr, right: DbExpr): DbExpr

    final override fun compile(pos: S_Pos, ctx: CtExprContext, left: C_BinaryExprNode, right: C_BinaryExprNode): RExpr {
        val rLeft = left.compile(ctx)
        val rRight = right.compile(ctx)
        return compile(pos, rLeft, rRight)
    }

    final override fun compileDb(pos: S_Pos, ctx: CtDbExprContext, left: C_BinaryExprNode, right: C_BinaryExprNode): DbExpr {
        val dbLeft = left.compileDb(ctx)
        val dbRight = right.compileDb(ctx)

        //TODO don't use "is"
        if (dbLeft is InterpretedDbExpr && dbRight is InterpretedDbExpr) {
            val rExpr = compile(pos, dbLeft.expr, dbRight.expr)
            return InterpretedDbExpr(rExpr)
        } else {
            return compileDb(pos, dbLeft, dbRight)
        }
    }
}

sealed class S_BinaryOp_Common(code: String): S_BinaryOp_Base(code) {
    abstract fun compileOp(left: RType, right: RType): S_BinOpType?

    override fun compile(pos: S_Pos, left: RExpr, right: RExpr): RExpr {
        val lType = left.type
        val rType = right.type

        val op = compileOp(lType, rType)
        if (op == null || op.rOp == null) {
            throw errTypeMissmatch(pos, lType, rType)
        }

        val left2 = convertExpr(pos, code, lType, rType, left, op.leftConv)
        val right2 = convertExpr(pos, code, lType, rType, right, op.rightConv)

        return RBinaryExpr(op.resType, op.rOp, left2, right2)
    }

    override fun compileDb(pos: S_Pos, left: DbExpr, right: DbExpr): DbExpr {
        val lType = left.type
        val rType = right.type

        val op = compileOp(lType, rType)
        if (op == null || op.dbOp == null) {
            throw errTypeMissmatch(pos, lType, rType)
        }

        val left2 = convertExprDb(pos, code, lType, rType, left, op.leftConv)
        val right2 = convertExprDb(pos, code, lType, rType, right, op.rightConv)

        return BinaryDbExpr(op.resType, op.dbOp, left2, right2)
    }

    companion object {
        fun convertExpr(pos: S_Pos, op: String, lType: RType, rType: RType, expr: RExpr, conv: S_OperandConversion?): RExpr {
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

        fun convertExprDb(pos: S_Pos, op: String, lType: RType, rType: RType, expr: DbExpr, conv: S_OperandConversion?): DbExpr {
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

sealed class S_BinaryOp_EqNe(val rOp: RBinaryOp, val dbOp: DbBinaryOp): S_BinaryOp_Common(rOp.code) {
    override final fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left == RNullType) {
            return compileOpNull(right)
        } else if (right == RNullType) {
            return compileOpNull(left)
        } else if (left != right) {
            return null
        }

        val actDbOp = if (dbOpSupported(left)) dbOp else null
        return S_BinOpType(RBooleanType, rOp, actDbOp)
    }

    private fun compileOpNull(other: RType): S_BinOpType? {
        if (other == RNullType || other is RNullableType) {
            return S_BinOpType(RBooleanType, rOp, null)
        } else {
            return null
        }
    }

    private fun dbOpSupported(type: RType): Boolean {
        return type == RBooleanType || type == RIntegerType || type == RTextType || type == RByteArrayType
                || type is RInstanceRefType
    }

    companion object {
        internal fun checkTypes(left: RType, right: RType): Boolean {
            val op = S_BinaryOp_Eq.compileOp(left, right)
            return op != null && op.dbOp != null
        }
    }
}

object S_BinaryOp_SingleEq: S_BinaryOp("=") {
    override fun compile(pos: S_Pos, ctx: CtExprContext, left: C_BinaryExprNode, right: C_BinaryExprNode): RExpr {
        throw CtError(pos, "expr_binop_sngeq_nosql",
                "Operator '${code}' can be used only in @-expression; use '${S_BinaryOp_Eq.code}' instead")
    }

    override fun compileDb(pos: S_Pos, ctx: CtDbExprContext, left: C_BinaryExprNode, right: C_BinaryExprNode): DbExpr {
        val dbLeft = left.compileDbAttr(ctx)
        if (dbLeft == null) {
            throw CtError(pos, "expr_binop_sngeq_noattr", "Left operand of '${code}' must be an attribute")
        }

        val dbRight = right.compileDb(ctx)
        return S_BinaryOp_Eq.compileDb(pos, dbLeft, dbRight)
    }
}

object S_BinaryOp_Eq: S_BinaryOp_EqNe(RBinaryOp_Eq, DbBinaryOp_Eq)
object S_BinaryOp_Ne: S_BinaryOp_EqNe(RBinaryOp_Ne, DbBinaryOp_Ne)

sealed class S_BinaryOp_Cmp(val cmpOp: RCmpOp, val dbOp: DbBinaryOp): S_BinaryOp_Common(cmpOp.code) {
    abstract fun compileType(type: RType): RCmpType?

    override final fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left != right) {
            return null
        }

        val rCmpType = compileType(left)
        if (rCmpType == null) {
            return null
        }

        return S_BinOpType(RBooleanType, RBinaryOp_Cmp(cmpOp, rCmpType), dbOp)
    }
}

sealed class S_BinaryOp_LtGt(cmpOp: RCmpOp, dbOp: DbBinaryOp): S_BinaryOp_Cmp(cmpOp, dbOp) {
    override fun compileType(type: RType): RCmpType? {
        if (type == RIntegerType) {
            return RCmpType_Integer
        } else if (type == RTextType) {
            return RCmpType_Text
        } else if (type == RByteArrayType) {
            return RCmpType_ByteArray
        } else if (type is RInstanceRefType) {
            return RCmpType_Object
        } else {
            return null
        }
    }
}

object S_BinaryOp_Lt: S_BinaryOp_LtGt(RCmpOp_Lt, DbBinaryOp_Lt)
object S_BinaryOp_Gt: S_BinaryOp_LtGt(RCmpOp_Gt, DbBinaryOp_Gt)
object S_BinaryOp_Le: S_BinaryOp_LtGt(RCmpOp_Le, DbBinaryOp_Le)
object S_BinaryOp_Ge: S_BinaryOp_LtGt(RCmpOp_Ge, DbBinaryOp_Ge)

object S_BinaryOp_Plus: S_BinaryOp_Common("+") {
    override fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left == right) {
            if (left == RIntegerType) {
                return S_BinOpType(RIntegerType, RBinaryOp_Add, DbBinaryOp_Add)
            } else if (left == RTextType) {
                return S_BinOpType(RTextType, RBinaryOp_Concat_Text, DbBinaryOp_Concat)
            } else if (left == RByteArrayType) {
                return S_BinOpType(RByteArrayType, RBinaryOp_Concat_ByteArray, DbBinaryOp_Concat)
            } else {
                return null
            }
        }

        if (left == RTextType) {
            val conv = convertText(right)
            if (conv != null) return S_BinOpType(RTextType, RBinaryOp_Concat_Text, DbBinaryOp_Concat, null, conv)
        } else if (right == RTextType) {
            val conv = convertText(left)
            if (conv != null) return S_BinOpType(RTextType, RBinaryOp_Concat_Text, DbBinaryOp_Concat, conv, null)
        }

        return null
    }

    private fun convertText(type: RType): S_OperandConversion? {
        var dbConv = if (type == RBooleanType || type == RIntegerType || type == RJSONType || type is RInstanceRefType) {
            S_BinaryOp_Plus::convToTextDb
        } else {
            null
        }

        return S_OperandConversion(S_BinaryOp_Plus::convToText, dbConv)
    }

    private fun convToText(e: RExpr): RExpr = RSysCallExpr(RTextType, RSysFunction_ToString, listOf(e))
    private fun convToTextDb(e: DbExpr): DbExpr = CallDbExpr(RTextType, DbSysFunction_ToString, listOf(e))
}

sealed class S_BinaryOp_Arith(val rOp: RBinaryOp, val dbOp: DbBinaryOp): S_BinaryOp_Common(rOp.code) {
    override fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left != RIntegerType || right != RIntegerType) {
            return null
        }
        return S_BinOpType(RIntegerType, rOp, dbOp)
    }
}

object S_BinaryOp_Minus: S_BinaryOp_Arith(RBinaryOp_Sub, DbBinaryOp_Sub)
object S_BinaryOp_Mul: S_BinaryOp_Arith(RBinaryOp_Mul, DbBinaryOp_Mul)
object S_BinaryOp_Div: S_BinaryOp_Arith(RBinaryOp_Div, DbBinaryOp_Div)
object S_BinaryOp_Mod: S_BinaryOp_Arith(RBinaryOp_Mod, DbBinaryOp_Mod)

sealed class S_BinaryOp_Logic(val rOp: RBinaryOp, val dbOp: DbBinaryOp): S_BinaryOp_Common(rOp.code) {
    override fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left != RBooleanType || right != RBooleanType) {
            return null
        }
        return S_BinOpType(RBooleanType, rOp, dbOp)
    }
}

object S_BinaryOp_And: S_BinaryOp_Logic(RBinaryOp_And, DbBinaryOp_And)
object S_BinaryOp_Or: S_BinaryOp_Logic(RBinaryOp_Or, DbBinaryOp_Or)

object S_BinaryOp_In: S_BinaryOp_Common("in") {
    override fun compileOp(left: RType, right: RType): S_BinOpType? {
        val pair = matchOp(right)
        if (pair == null) {
            return null
        }

        val (rOp, elemType) = pair
        if (left != elemType) {
            return null
        }

        return S_BinOpType(RBooleanType, rOp, null)
    }

    private fun matchOp(right: RType): Pair<RBinaryOp, RType>? {
        if (right is RCollectionType) {
            return Pair(RBinaryOp_In_Collection, right.elementType)
        } else if (right is RMapType) {
            return Pair(RBinaryOp_In_Map, right.keyType)
        } else if (right is RRangeType) {
            return Pair(RBinaryOp_In_Range, RIntegerType)
        } else {
            return null
        }
    }
}

object S_BinaryOp_Elvis: S_BinaryOp_Base("?:") {
    override fun compile(pos: S_Pos, left: RExpr, right: RExpr): RExpr {
        val leftType = left.type
        if (leftType == RNullType) {
            return right
        }

        if (leftType !is RNullableType) {
            throw errTypeMissmatch(pos, leftType, right.type)
        }

        val resType = RType.commonTypeOpt(leftType.valueType, right.type)
        if (resType == null) {
            throw errTypeMissmatch(pos, leftType, right.type)
        }

        return RElvisExpr(resType, left, right)
    }

    override fun compileDb(pos: S_Pos, left: DbExpr, right: DbExpr): DbExpr {
        throw errTypeMissmatch(pos, left.type, right.type)
    }
}

class S_BinaryExprTail(val op: S_Node<S_BinaryOpCode>, val expr: S_Expression)

class S_BinaryExpr(val head: S_Expression, val tail: List<S_BinaryExprTail>): S_Expression(head.startPos) {
    override fun compile(ctx: CtExprContext): RExpr {
        val tree = buildTree()
        return tree.compile(ctx)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val tree = buildTree()
        return tree.compileDb(ctx)
    }

    private fun buildTree(): C_BinaryExprNode {
        val queue = LinkedList(tail)
        val tree = buildOperandNode(head, queue, 0)
        return tree
    }

    private fun buildOperandNode(left: S_Expression, tail: Queue<S_BinaryExprTail>, level: Int): C_BinaryExprNode {
        if (tail.isEmpty() || tail.peek().op.value.precedence() < level) {
            return C_TermBinaryExprNode(left)
        }

        var res = buildOperandNode(left, tail, level + 1)

        while (!tail.isEmpty() && tail.peek().op.value.precedence() == level) {
            val next = tail.remove()
            val right = buildOperandNode(next.expr, tail, level + 1)
            res = C_OpBinaryExprNode(next.op.pos, next.op.value.op, res, right)
        }

        return res
    }
}

abstract class C_BinaryExprNode {
    internal abstract fun compile(ctx: CtExprContext): RExpr
    internal abstract fun compileDb(ctx: CtDbExprContext): DbExpr
    internal abstract fun compileDbAttr(ctx: CtDbExprContext): DbExpr?
}

private class C_TermBinaryExprNode(val expr: S_Expression): C_BinaryExprNode() {
    override fun compile(ctx: CtExprContext): RExpr {
        val rExpr = expr.compile(ctx)
        checkUnitType(rExpr.type)
        return rExpr
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbExpr = expr.compileDb(ctx)
        checkUnitType(dbExpr.type)
        return dbExpr
    }

    override fun compileDbAttr(ctx: CtDbExprContext): DbExpr? {
        return expr.compileDbAttr(ctx)
    }

    private fun checkUnitType(type: RType) {
        CtUtils.checkUnitType(expr.startPos, type, "expr_operand_unit", "Operand expression returns nothing")
    }
}

private class C_OpBinaryExprNode(
        val pos: S_Pos,
        val op: S_BinaryOp,
        val left: C_BinaryExprNode,
        val right: C_BinaryExprNode
): C_BinaryExprNode()
{
    override fun compile(ctx: CtExprContext): RExpr {
        return op.compile(pos, ctx, left, right)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        return op.compileDb(pos, ctx, left, right)
    }

    override fun compileDbAttr(ctx: CtDbExprContext): DbExpr? = null
}
