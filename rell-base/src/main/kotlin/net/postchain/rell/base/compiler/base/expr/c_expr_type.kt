/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.expr

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.C_Name
import net.postchain.rell.base.compiler.base.core.C_QualifiedName
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.lib.C_TypeMember
import net.postchain.rell.base.model.R_EnumType
import net.postchain.rell.base.model.R_StructType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.utils.LazyPosString

class C_SpecificTypeExpr(private val pos: S_Pos, private val type: R_Type): C_NoValueExpr() {
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        val members = ctx.typeMgr.getStaticMembers(type, memberName.rName)
        val member = C_TypeMember.getMember(ctx.msgCtx, members, exprHint, memberName, type, "type_static_member")
        member ?: return C_ExprUtils.errorMember(ctx, memberName.pos)

        val qName = C_QualifiedName(memberName)
        return member.toExprMember(ctx, qName, type)
    }

    override fun isCallable(): Boolean {
        return type.hasConstructor() || type.libType.hasConstructor()
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        if (type.isError()) {
            // Do not report redundant errors on error types.
            return C_ExprUtils.errorExpr(ctx, pos)
        }

        val fn = ctx.typeMgr.getConstructor(type)
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
