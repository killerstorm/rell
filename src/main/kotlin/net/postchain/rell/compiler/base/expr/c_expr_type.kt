package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.def.C_GenericType
import net.postchain.rell.compiler.base.namespace.C_NamespaceValueContext
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.model.R_EnumType
import net.postchain.rell.model.R_Type
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.immListOf

class C_SpecificTypeExpr(private val pos: S_Pos, private val type: R_Type): C_Expr() {
    override fun kind() = when (type) {
        is R_EnumType -> C_ExprKind.ENUM
        else -> C_ExprKind.TYPE
    }

    override fun startPos() = pos

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        val valueMember = memberValue(ctx, memberName)
        val fnMember = memberFunction(memberName)

        val res = C_ExprUtils.valueFunctionExprMember(valueMember, fnMember, exprHint)

        if (res == null) {
            C_Errors.errUnknownName(ctx.msgCtx, type, memberName)
            return C_ExprUtils.errorMember(ctx, memberName.pos)
        }

        return res
    }

    private fun memberValue(ctx: C_ExprContext, memberName: C_Name): C_ExprMember? {
        val value = type.getStaticValues()[memberName.rName]
        value ?: return null

        val memCtx = C_NamespaceValueContext(ctx)
        val qName = C_QualifiedName(immListOf(memberName))
        val cExpr = value.toExpr(memCtx, qName, null)
        return C_ExprMember(cExpr, value.ideInfo)
    }

    private fun memberFunction(memberName: C_Name): C_ExprMember? {
        val fn = type.getStaticFunctions().get(memberName.rName)
        fn ?: return null

        val lazyMemberName = LazyPosString.of(memberName)
        val fnExpr = C_FunctionExpr(lazyMemberName, fn)
        return C_ExprMember(fnExpr, fn.ideInfo)
    }

    override fun isCallable(): Boolean {
        return type.getConstructorFunction() != null
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        if (type.isError()) {
            // Do not report redundant errors on error types.
            return C_ExprUtils.errorExpr(ctx, pos)
        }

        val fn = type.getConstructorFunction()
        // Handle no-constructor case: super throws error; TODO better handling
        fn ?: return super.call(ctx, pos, args, resTypeHint)

        val lazyName = LazyPosString.of(this.pos) { type.name }
        val vExpr = fn.compileCall(ctx, lazyName, args, resTypeHint)
        return C_VExpr(vExpr)
    }
}

class C_RawGenericTypeExpr(private val pos: S_Pos, private val type: C_GenericType): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        TODO()
    }

    override fun isCallable(): Boolean {
        return type.rawConstructorFn != null
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val fn = type.rawConstructorFn
        // Handle no-constructor case: super throws error; TODO better handling
        fn ?: return super.call(ctx, pos, args, resTypeHint)

        val lazyName = LazyPosString.of(this.pos) { type.name }
        val vExpr = fn.compileCall(ctx, lazyName, args, resTypeHint)
        return C_VExpr(vExpr)
    }
}
