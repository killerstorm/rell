/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.expr

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.C_Name
import net.postchain.rell.base.compiler.base.core.C_QualifiedName
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.def.C_GenericType
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.model.R_EnumType
import net.postchain.rell.base.model.R_StructType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.utils.LazyPosString

class C_SpecificTypeExpr(private val pos: S_Pos, private val type: R_Type): C_NoValueExpr() {
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        val tags = exprHint.memberTags()
        val memberElem = type.getStaticNamespace().getElement(memberName.rName, tags)

        if (memberElem == null) {
            C_Errors.errUnknownName(ctx.msgCtx, type, memberName)
            return C_ExprUtils.errorMember(ctx, memberName.pos)
        }

        val qName = C_QualifiedName(memberName)
        return memberElem.toExprMember(ctx, qName)
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
        val vExpr = fn.compileCall(ctx, lazyName, args, resTypeHint).vExpr()
        return C_ValueExpr(vExpr)
    }

    override fun errKindName(): Pair<String, String> {
        val kind = when (type) {
            is R_EnumType -> "enum"
            is R_StructType -> "struct"
            else -> "type"
        }
        return kind to type.defName.appLevelName
    }
}

class C_RawGenericTypeExpr(private val pos: S_Pos, private val type: C_GenericType): C_NoValueExpr() {
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        val nameCode = "[${type.defName.appLevelName}]:$memberName"
        val nameMsg = "${type.name}.$memberName"
        C_Errors.errUnknownName(ctx.msgCtx, pos, nameCode, nameMsg)
        return C_ExprUtils.errorMember(ctx, memberName.pos)
    }

    override fun isCallable(): Boolean {
        return type.rawConstructorFn != null
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val fn = type.rawConstructorFn
        // Handle no-constructor case: super throws error; TODO better handling
        fn ?: return super.call(ctx, pos, args, resTypeHint)

        val lazyName = LazyPosString.of(this.pos) { type.name }
        val vExpr = fn.compileCall(ctx, lazyName, args, resTypeHint).vExpr()
        return C_ValueExpr(vExpr)
    }

    override fun errKindName() = "type" to type.defName.appLevelName
}
