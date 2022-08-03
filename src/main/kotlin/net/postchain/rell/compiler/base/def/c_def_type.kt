package net.postchain.rell.compiler.base.def

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_NamespaceContext
import net.postchain.rell.compiler.base.expr.C_Expr
import net.postchain.rell.compiler.base.expr.C_RawGenericTypeExpr
import net.postchain.rell.compiler.base.expr.C_SpecificTypeExpr
import net.postchain.rell.model.R_CtErrorType
import net.postchain.rell.model.R_Type

sealed class C_TypeDef {
    abstract fun toRType(msgCtx: C_MessageContext, pos: S_Pos): R_Type
    abstract fun toExpr(pos: S_Pos): C_Expr
}

class C_TypeDef_Normal(val type: R_Type): C_TypeDef() {
    override fun toRType(msgCtx: C_MessageContext, pos: S_Pos) = type
    override fun toExpr(pos: S_Pos): C_Expr = C_SpecificTypeExpr(pos, type)
}

class C_TypeDef_Generic(val type: C_GenericType): C_TypeDef() {
    override fun toRType(msgCtx: C_MessageContext, pos: S_Pos): R_Type {
        msgCtx.error(pos, "type:generic:no_args:${type.name}", "Type arguments not specified for generic type '${type.name}'")
        return R_CtErrorType
    }

    override fun toExpr(pos: S_Pos): C_Expr = C_RawGenericTypeExpr(pos, type)
}

abstract class C_GenericType(val name: String, val paramCount: Int) {
    open val rawConstructorFn: C_GlobalFunction? = null

    abstract fun compileType0(ctx: C_NamespaceContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type

    fun compileType(ctx: C_NamespaceContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
        if (args.size != paramCount) {
            ctx.msgCtx.error(pos, "type:generic:wrong_arg_count:$name:$paramCount:${args.size}",
                "Wrong number of type arguments for type '$name': ${args.size} instead of $paramCount")
            return R_CtErrorType
        }
        return compileType0(ctx, pos, args)
    }
}
