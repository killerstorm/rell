package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value

sealed class Db_BinaryOp(val code: String, val sql: String)
object Db_BinaryOp_Eq: Db_BinaryOp("==", "=")
object Db_BinaryOp_Ne: Db_BinaryOp("!=", "<>")
object Db_BinaryOp_Lt: Db_BinaryOp("<", "<")
object Db_BinaryOp_Gt: Db_BinaryOp(">", ">")
object Db_BinaryOp_Le: Db_BinaryOp("<=", "<=")
object Db_BinaryOp_Ge: Db_BinaryOp(">=", ">=")
object Db_BinaryOp_Add: Db_BinaryOp("+", "+")
object Db_BinaryOp_Sub: Db_BinaryOp("-", "-")
object Db_BinaryOp_Mul: Db_BinaryOp("*", "*")
object Db_BinaryOp_Div: Db_BinaryOp("/", "/")
object Db_BinaryOp_Mod: Db_BinaryOp("%", "%")
object Db_BinaryOp_And: Db_BinaryOp("and", "AND")
object Db_BinaryOp_Or: Db_BinaryOp("or", "OR")
object Db_BinaryOp_Concat: Db_BinaryOp("+", "||")
object Db_BinaryOp_In: Db_BinaryOp("in", "IN")

sealed class Db_UnaryOp(val code: String, val sql: String)
object Db_UnaryOp_Minus: Db_UnaryOp("-", "-")
object Db_UnaryOp_Not: Db_UnaryOp("not", "NOT")

sealed class Db_Expr(val type: R_Type) {
    open fun implicitName(): String? = null
    open fun constantValue(): Rt_Value? = null
    abstract fun toSql(ctx: SqlGenContext, bld: SqlBuilder)
}

class Db_InterpretedExpr(val expr: R_Expr): Db_Expr(expr.type) {
    override fun constantValue() = expr.constantValue()

    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append(expr)
    }
}

class Db_BinaryExpr(type: R_Type, val op: Db_BinaryOp, val left: Db_Expr, val right: Db_Expr): Db_Expr(type) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("(")
        left.toSql(ctx, bld)
        bld.append(" ")
        bld.append(op.sql)
        bld.append(" ")
        right.toSql(ctx, bld)
        bld.append(")")
    }
}

class Db_UnaryExpr(type: R_Type, val op: Db_UnaryOp, val expr: Db_Expr): Db_Expr(type) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("(")
        bld.append(op.sql)
        bld.append(" ")
        expr.toSql(ctx, bld)
        bld.append(")")
    }
}

sealed class Db_TableExpr(val rClass: R_Class): Db_Expr(R_ClassType(rClass)) {
    abstract fun alias(ctx: SqlGenContext): SqlTableAlias

    final override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        val alias = alias(ctx)
        bld.appendColumn(alias, rClass.sqlMapping.rowidColumn())
    }
}

class Db_ClassExpr(val cls: R_AtClass): Db_TableExpr(cls.rClass) {
    override fun alias(ctx: SqlGenContext) = ctx.getClassAlias(cls)
}

class Db_RelExpr(val base: Db_TableExpr, val attr: R_Attrib, targetClass: R_Class): Db_TableExpr(targetClass) {
    override fun implicitName(): String? {
        return if (base is Db_ClassExpr) attr.name else null
    }

    override fun alias(ctx: SqlGenContext): SqlTableAlias {
        val baseAlias = base.alias(ctx)
        return ctx.getRelAlias(baseAlias, attr, rClass)
    }
}

class Db_AttrExpr(val base: Db_TableExpr, val attr: R_Attrib): Db_Expr(attr.type) {
    override fun implicitName(): String? {
        return if (base is Db_ClassExpr) attr.name else null
    }

    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        val alias = base.alias(ctx)
        bld.appendColumn(alias, attr.sqlMapping)
    }
}

class Db_ParameterExpr(type: R_Type, val index: Int): Db_Expr(type) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        val value = ctx.getParameter(index)
        bld.append(type, value)
    }
}

class Db_ArrayParameterExpr(type: R_Type, val elementType: R_Type, val index: Int): Db_Expr(type) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        val value = ctx.getParameter(index)
        bld.append("(")
        bld.append(value.asCollection(), ",") {
            bld.append(elementType, it)
        }
        bld.append(")")
    }
}

class Db_InExpr(val keyExpr: Db_Expr, val exprs: List<Db_Expr>): Db_Expr(R_BooleanType) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("(")
        keyExpr.toSql(ctx, bld)
        bld.append(" IN (")
        bld.append(exprs, ", ") { expr ->
            expr.toSql(ctx, bld)
        }
        bld.append(")")
        bld.append(")")
    }
}

class Db_WhenCase(val cond: Db_Expr, val expr: Db_Expr)

class Db_WhenExpr(type: R_Type, val cases: List<Db_WhenCase>, val elseExpr: Db_Expr?): Db_Expr(type) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("CASE")
        for (case in cases) {
            bld.append(" WHEN ")
            case.cond.toSql(ctx, bld)
            bld.append(" THEN ")
            case.expr.toSql(ctx, bld)
        }
        if (elseExpr != null) {
            bld.append(" ELSE ")
            elseExpr.toSql(ctx, bld)
        }
        bld.append(" END")
    }
}

class Db_ChainHeightExpr(val chain: R_ExternalChain): Db_Expr(R_IntegerType) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        val rtChain = ctx.sqlCtx.linkedChain(chain)
        bld.append(R_IntegerType, Rt_IntValue(rtChain.height))
    }
}

sealed class Db_SysFunction(val name: String) {
    abstract fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<Db_Expr>)
}

sealed class Db_SysFunction_Simple(name: String, val sql: String): Db_SysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<Db_Expr>) {
        bld.append(sql)
        bld.append("(")
        bld.append(args, ", ") {
            it.toSql(ctx, bld)
        }
        bld.append(")")
    }
}

sealed class Db_SysFn_Cast(name: String, val type: String): Db_SysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<Db_Expr>) {
        check(args.size == 1)
        bld.append("((")
        args[0].toSql(ctx, bld)
        bld.append(")::$type)")
    }
}

object Db_SysFn_Int_Str: Db_SysFn_Cast("int.str", "TEXT")
object Db_SysFn_Abs: Db_SysFunction_Simple("abs", "ABS")
object Db_SysFn_Min: Db_SysFunction_Simple("min", "LEAST")
object Db_SysFn_Max: Db_SysFunction_Simple("max", "GREATEST")
object Db_SysFn_Text_Size: Db_SysFunction_Simple("text.len", "LENGTH")
object Db_SysFn_Text_UpperCase: Db_SysFunction_Simple("text.len", "UPPER")
object Db_SysFn_Text_LowerCase: Db_SysFunction_Simple("text.len", "LOWER")
object Db_SysFn_ByteArray_Size: Db_SysFunction_Simple("byte_array.len", "LENGTH")
object Db_SysFn_Json: Db_SysFn_Cast("json", "JSONB")
object Db_SysFn_Json_Str: Db_SysFn_Cast("json.str", "TEXT")
object Db_SysFn_ToString: Db_SysFn_Cast("toString", "TEXT")

class Db_CallExpr(type: R_Type, val fn: Db_SysFunction, val args: List<Db_Expr>): Db_Expr(type) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) = fn.toSql(ctx, bld, args)
}
