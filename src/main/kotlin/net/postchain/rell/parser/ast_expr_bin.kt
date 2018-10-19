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
    PLUS(S_BinaryOp_Plus),
    MINUS(S_BinaryOp_Minus),
    MUL(S_BinaryOp_Mul),
    DIV(S_BinaryOp_Div),
    MOD(S_BinaryOp_Mod),
    AND(S_BinaryOp_And),
    OR(S_BinaryOp_Or),
    ;

    companion object {
        private val PRECEDENCE_LEVELS = listOf(
                listOf(OR),
                listOf(AND),
                listOf(EQ, NE, LE, GE, LT, GT),
                listOf(PLUS, MINUS),
                listOf(MUL, DIV, MOD)
        )

        private val PRECEDENCE_MAP: Map<S_BinaryOpCode, Int>

        init {
            val m = mutableMapOf<S_BinaryOpCode, Int>()

            for ((level, ops) in PRECEDENCE_LEVELS.withIndex()) {
                for (op in ops) {
                    check(!(op in m))
                    m[op] = level
                }
            }
            check(m.keys.containsAll(S_BinaryOpCode.values().toSet()))

            PRECEDENCE_MAP = m.toMap()
        }
    }

    fun precedence(): Int = PRECEDENCE_MAP.getValue(this)
}

class S_BinOpType(val resType: RType, val rOp: RBinaryOp?, val dbOp: DbBinaryOp?)

sealed class S_BinaryOp(val code: String) {
    abstract fun compileOp(left: RType, right: RType): S_BinOpType?

    fun compile(left: RExpr, right: RExpr): RExpr {
        val lType = left.type
        val rType = right.type

        val op = compileOp(lType, rType)
        if (op == null || op.rOp == null) {
            throw errTypeMissmatch(lType, rType)
        }

        return RBinaryExpr(op.resType, op.rOp, left, right)
    }

    fun compileDb(left: DbExpr, right: DbExpr): DbExpr {
        val lType = left.type
        val rType = right.type

        val op = compileOp(lType, rType)
        if (op == null || op.dbOp == null) {
            throw errTypeMissmatch(lType, rType)
        }

        return BinaryDbExpr(op.resType, op.dbOp, left, right)
    }

    private fun errTypeMissmatch(leftType: RType, rightType: RType): CtError {
        return CtError("binop_operand_type:$code:$leftType:$rightType",
                "Wrong operand types for operator '$code': $leftType, $rightType")
    }
}

sealed class S_BinaryOp_Cmp(val cmpOp: RCmpOp, val dbOp: DbBinaryOp): S_BinaryOp(cmpOp.code) {
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

sealed class S_BinaryOp_EqNe(cmpOp: RCmpOp, dbOp: DbBinaryOp): S_BinaryOp_Cmp(cmpOp, dbOp) {
    override fun compileType(type: RType): RCmpType? {
        if (type == RBooleanType) {
            return RCmpType_Boolean
        } else if (type == RIntegerType) {
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

    companion object {
        internal fun checkTypes(left: RType, right: RType): Boolean {
            val op = S_BinaryOp_Eq.compileOp(left, right)
            return op != null && op.dbOp != null
        }
    }
}

object S_BinaryOp_Eq: S_BinaryOp_EqNe(RCmpOp_Eq, DbBinaryOp_Eq)
object S_BinaryOp_Ne: S_BinaryOp_EqNe(RCmpOp_Ne, DbBinaryOp_Ne)

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

object S_BinaryOp_Plus: S_BinaryOp("+") {
    override fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left != right) {
            return null
        } else if (left == RIntegerType) {
            return S_BinOpType(RIntegerType, RBinaryOp_Add, DbBinaryOp_Add)
        } else if (left == RTextType) {
            return S_BinOpType(RTextType, RBinaryOp_Concat_Text, DbBinaryOp_Concat)
        } else if (left == RByteArrayType) {
            return S_BinOpType(RByteArrayType, RBinaryOp_Concat_ByteArray, DbBinaryOp_Concat)
        } else {
            return null
        }
    }
}

sealed class S_BinaryOp_Arith(val rOp: RBinaryOp, val dbOp: DbBinaryOp): S_BinaryOp(rOp.code) {
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

sealed class S_BinaryOp_Logic(val rOp: RBinaryOp, val dbOp: DbBinaryOp): S_BinaryOp(rOp.code) {
    override fun compileOp(left: RType, right: RType): S_BinOpType? {
        if (left != RBooleanType || right != RBooleanType) {
            return null
        }
        return S_BinOpType(RBooleanType, rOp, dbOp)
    }
}

object S_BinaryOp_And: S_BinaryOp_Logic(RBinaryOp_And, DbBinaryOp_And)
object S_BinaryOp_Or: S_BinaryOp_Logic(RBinaryOp_Or, DbBinaryOp_Or)

class S_BinaryExprTail(val op: S_BinaryOpCode, val expr: S_Expression)

class S_BinaryExpr(val head: S_Expression, val tail: List<S_BinaryExprTail>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val tree = buildTree()
        return tree.compile(ctx)
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val tree = buildTree()
        return tree.compileDb(ctx)
    }

    private fun buildTree(): BinaryExprNode {
        val queue = LinkedList(tail)
        val tree = buildOperandNode(head, queue, 0)
        return tree
    }

    private fun buildOperandNode(left: S_Expression, tail: Queue<S_BinaryExprTail>, level: Int): BinaryExprNode {
        if (tail.isEmpty() || tail.peek().op.precedence() < level) {
            return TermBinaryExprNode(left)
        }

        var res = buildOperandNode(left, tail, level + 1)

        while (!tail.isEmpty() && tail.peek().op.precedence() == level) {
            val next = tail.remove()
            val right = buildOperandNode(next.expr, tail, level + 1)
            res = OpBinaryExprNode(next.op.op, res, right)
        }

        return res
    }

    companion object {
        private abstract class BinaryExprNode {
            abstract fun compile(ctx: ExprCompilationContext): RExpr
            abstract fun compileDb(ctx: DbCompilationContext): DbExpr
        }

        private class TermBinaryExprNode(val expr: S_Expression): BinaryExprNode() {
            override fun compile(ctx: ExprCompilationContext): RExpr {
                return expr.compile(ctx)
            }

            override fun compileDb(ctx: DbCompilationContext): DbExpr {
                return expr.compileDb(ctx)
            }
        }

        private class OpBinaryExprNode(val op: S_BinaryOp, val left: BinaryExprNode, val right: BinaryExprNode): BinaryExprNode() {
            override fun compile(ctx: ExprCompilationContext): RExpr {
                val rLeft = left.compile(ctx)
                val rRight = right.compile(ctx)
                return op.compile(rLeft, rRight)
            }

            override fun compileDb(ctx: DbCompilationContext): DbExpr {
                val dbLeft = left.compileDb(ctx)
                val dbRight = right.compileDb(ctx)

                //TODO don't use "is"
                if (dbLeft is InterpretedDbExpr && dbRight is InterpretedDbExpr) {
                    val rExpr = op.compile(dbLeft.expr, dbRight.expr)
                    return InterpretedDbExpr(rExpr)
                } else {
                    return op.compileDb(dbLeft, dbRight)
                }
            }
        }
    }
}
