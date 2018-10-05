package net.postchain.rell.parser

import net.postchain.rell.model.*

internal class DbClassAttr(val cls: RAtClass, val name: String, val type: RType)

internal class DbCompilationContext(
        val parent: DbCompilationContext?,
        val exprCtx: ExprCompilationContext,
        val classes: List<RAtClass>
) {
    fun findAttributesByName(name: String): List<DbClassAttr> {
        return findAttrsInChain({ it.name == name })
    }

    fun findAttributesByType(type: RType): List<DbClassAttr> {
        return findAttrsInChain({ it.type == type })
    }

    private fun findAttrsInChain(matcher: (RAttrib) -> Boolean): List<DbClassAttr> {
        val attrs = mutableListOf<DbClassAttr>()
        var ctx: DbCompilationContext? = this
        while (ctx != null) {
            ctx.findAttrInternal(matcher, attrs)
            ctx = ctx.parent
        }
        return attrs.toList()
    }

    private fun findAttrInternal(matcher: (RAttrib) -> Boolean, attrs: MutableList<DbClassAttr>): DbClassAttr? {
        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (cls in classes) {
            for (attr in cls.rClass.attributes) {
                if (matcher(attr)) {
                    attrs.add(DbClassAttr(cls, attr.name, attr.type))
                }
            }
        }
        return null
    }

    fun findClassByAlias(alias: String): RAtClass? {
        var ctx: DbCompilationContext? = this
        while (ctx != null) {
            //TODO use a lookup table
            for (cls in ctx.classes) {
                if (cls.alias == alias) {
                    return cls
                }
            }
            ctx = ctx.parent
        }
        return null
    }
}

abstract class S_Expression {
    internal abstract fun compile(ctx: ExprCompilationContext): RExpr
    internal abstract fun compileDb(ctx: DbCompilationContext): DbExpr;
    internal open fun compileDbWhere(ctx: DbCompilationContext, idx: Int): DbExpr = compileDb(ctx);
    internal open fun iteratePathExpr(path: MutableList<String>): Boolean = false

    internal fun delegateCompileDb(ctx: DbCompilationContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))
}

class S_StringLiteralExpr(val literal: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RStringLiteralExpr(RTextType, literal)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_ByteALiteralExpr(val bytes: ByteArray): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RByteArrayLiteralExpr(RByteArrayType, bytes)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_IntLiteralExpr(val value: Long): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RIntegerLiteralExpr(RIntegerType, value)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

sealed class S_BinaryOperator {
    abstract fun compile(left: RExpr, right: RExpr): RExpr
    abstract fun compileDb(left: DbExpr, right: DbExpr): DbExpr
}

sealed class S_BinaryOperatorEqNe(val dbOp: DbBinaryOp): S_BinaryOperator() {
    abstract fun compileOpText(): RBinaryOp

    override fun compile(left: RExpr, right: RExpr): RExpr {
        val leftType = left.type
        val rightType = right.type

        var op: RBinaryOp
        if (leftType == RTextType && rightType == RTextType) {
            op = compileOpText()
        } else {
            TODO("${leftType.toStrictString()},${rightType.toStrictString()}")
        }

        return RBinaryExpr(RBooleanType, op, left, right)
    }

    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr {
        if (!checkTypes(left.type, right.type)) { //TODO distinguish db- and rt-expressions
            throw CtError("binop_operand_type:${dbOp.code}:${left.type}:${right.type}",
                    "Wrong operand types for operator '${dbOp.code}': ${left.type}, ${right.type}")
        }

        return BinaryDbExpr(RBooleanType, left, right, dbOp)
    }

    companion object {
        internal fun checkTypes(left: RType, right: RType): Boolean {
            return left == right
        }
    }
}

object S_BinaryOperatorEq: S_BinaryOperatorEqNe(DbBinaryOpEq) {
    override fun compileOpText(): RBinaryOp = RBinaryOp_Eq_Text
}

object S_BinaryOperatorNe: S_BinaryOperatorEqNe(DbBinaryOpNe) {
    override fun compileOpText(): RBinaryOp = TODO("TODO")
}

sealed class S_BinaryOperatorCmp: S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = TODO("TODO")
}

object S_BinaryOperatorLt: S_BinaryOperatorCmp()
object S_BinaryOperatorGt: S_BinaryOperatorCmp()
object S_BinaryOperatorLe: S_BinaryOperatorCmp()
object S_BinaryOperatorGe: S_BinaryOperatorCmp()

sealed class S_BinaryOperatorArith: S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = TODO("TODO")
}

object S_BinaryOperatorPlus: S_BinaryOperatorArith()
object S_BinaryOperatorMinus: S_BinaryOperatorArith()
object S_BinaryOperatorMul: S_BinaryOperatorArith()
object S_BinaryOperatorDiv: S_BinaryOperatorArith()
object S_BinaryOperatorMod: S_BinaryOperatorArith()

sealed class S_BinaryOperatorLogic: S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = TODO("TODO")
}

object S_BinaryOperatorAnd: S_BinaryOperatorLogic()
object S_BinaryOperatorOr: S_BinaryOperatorLogic()

class S_BinaryExpr(val left: S_Expression, val right: S_Expression, val op: S_BinaryOperator): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")

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

class S_UnaryExpr(val op: String, val expr: S_Expression): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}
