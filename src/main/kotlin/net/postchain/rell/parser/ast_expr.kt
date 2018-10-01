package net.postchain.rell.parser

import com.google.common.base.Preconditions
import net.postchain.rell.model.*

internal class DbClassAttr(val cls: RAtClass, val index: Int, val type: RType)

internal class DbCompilationContext(val exprCtx: ExprCompilationContext, val classes: List<RAtClass>) {
    fun findAttributeClass(name: String): DbClassAttr? {
        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (cls in classes) {
            for (i in 0 until cls.rClass.attributes.size) {
                val attr = cls.rClass.attributes[i]
                if (attr.name == name) {
                    return DbClassAttr(cls, i, attr.type)
                }
            }
        }
        return null
    }
}

sealed class S_Expression {
    internal abstract fun compile(ctx: ExprCompilationContext): RExpr
    internal abstract fun compileDb(ctx: DbCompilationContext): DbExpr;

    internal fun delegateCompileDb(ctx: DbCompilationContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))
}

class S_NameExpr(val name: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val entry = ctx.lookup(name)
        return RVarExpr(entry.attr.type, entry.offset, entry.attr)
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val clsAttr = ctx.findAttributeClass(name)
        val localAttr = ctx.exprCtx.lookupOpt(name)
        Preconditions.checkState(clsAttr == null || localAttr == null, "Ambiguous name: [%s]", name)
        Preconditions.checkState(clsAttr != null || localAttr != null, "Unknown name: [%s]", name)

        if (clsAttr != null) {
            val clsType = RInstanceRefType(clsAttr.cls.rClass)
            val clsExpr = ClassDbExpr(clsType, clsAttr.cls)
            return AttributeDbExpr(clsAttr.type, clsExpr, clsAttr.index, name)
        } else {
            TODO("TODO")
        }
    }
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
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")

    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr {
        if (left.type != right.type) {
            throw CtOperandTypeError("Wrong operand types for operator ${dbOp}: ${left.type}, ${right.type}")
        }

        return BinaryDbExpr(RBooleanType, left, right, dbOp)
    }
}

object S_BinaryOperatorEq: S_BinaryOperatorEqNe(DbBinaryOpEq)
object S_BinaryOperatorNe: S_BinaryOperatorEqNe(DbBinaryOpNe)

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

class S_AtExpr(val className: String, val exprs: List<S_Expression>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val cls = ctx.modCtx.classes[className]
        if (cls == null) {
            throw IllegalStateException("Unknown class: [$className]")
        }

        val selClass = RAtClass(cls, 0)
        val classes = listOf(selClass)
        val dbCtx = DbCompilationContext(ctx, classes)
        val compiledExprs = exprs.map { it.compileDb(dbCtx) }
        val where = makeWhere(compiledExprs)

        val clsType = RInstanceRefType(cls)
        val listType = RListType(clsType)
        return RAtExpr(listType, selClass, where)
    }

    private fun makeWhere(compiledExprs: List<DbExpr>): DbExpr? {
        for (i in compiledExprs.indices) {
            val type = compiledExprs[i].type
            if (type != RBooleanType) {
                throw CtOperandTypeError("Wrong type of where-expression $i: $type")
            }
        }

        val dbExprs = compiledExprs.filter { !(it is InterpretedDbExpr) }
        val rExprs = compiledExprs.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }

        val dbTree = exprsToTree(dbExprs)
        val rTree = exprsToTree(rExprs)

        if (dbTree != null && rTree != null) {
            return BinaryDbExpr(RBooleanType, dbTree, InterpretedDbExpr(rTree), DbBinaryOpAnd)
        } else if (dbTree != null) {
            return dbTree
        } else if (rTree != null) {
            return InterpretedDbExpr(rTree)
        } else {
            return null
        }
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")

    private fun exprsToTree(exprs: List<DbExpr>): DbExpr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            left = BinaryDbExpr(RBooleanType, left, right, DbBinaryOpAnd)
        }
        return left
    }

    private fun exprsToTree(exprs: List<RExpr>): RExpr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            TODO("TODO")
        }
        return left
    }
}

class S_AttributeExpr(val base: S_Expression, val name: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}
