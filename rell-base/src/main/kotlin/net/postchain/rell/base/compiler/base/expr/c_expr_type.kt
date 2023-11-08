/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.expr

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.lib.C_TypeMember
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.model.R_EntityType
import net.postchain.rell.base.model.R_EnumType
import net.postchain.rell.base.model.R_StructType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.Nullable

class C_SpecificTypeExpr(
    private val pos: S_Pos,
    private val type: R_Type,
    private val ideInfoPtr: C_UniqueDefaultIdeInfoPtr = C_UniqueDefaultIdeInfoPtr(),
) : C_NoValueExpr() {
    override fun startPos() = pos

    override fun getDefMeta(): C_ExprDefMeta? {
        return when (type) {
            is R_EntityType -> {
                val entity = type.rEntity
                C_ExprDefMeta(mountName = entity.mountName, externalChain = Nullable.of(entity.external?.chain?.name))
            }
            else -> null
        }
    }

    override fun value(): V_Expr {
        ideInfoPtr.setDefault()
        return super.value()
    }

    override fun member(ctx: C_ExprContext, memberNameHand: C_NameHandle, exprHint: C_ExprHint): C_Expr {
        ideInfoPtr.setDefault()

        val memberName = memberNameHand.name
        val members = ctx.typeMgr.getStaticMembers(type, memberName.rName)

        val member = C_TypeMember.getMember(ctx.msgCtx, members, exprHint, memberName, type, "type_static_member")
        if (member == null) {
            memberNameHand.setIdeInfo(C_IdeSymbolInfo.UNKNOWN)
            return C_ExprUtils.errorExpr(ctx, memberName.pos)
        }

        val qName = C_QualifiedName(memberName)
        return member.toExprMember(ctx, qName, type, memberNameHand)
    }

    override fun isCallable(): Boolean {
        return type.libType.hasConstructor()
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        if (type.isError()) {
            ideInfoPtr.setDefault()
            // Do not report redundant errors on error types.
            return C_ExprUtils.errorExpr(ctx, pos)
        }

        val fn = ctx.typeMgr.getConstructor(type)
        if (fn == null) {
            ideInfoPtr.setDefault()
            // Handle no-constructor case: super throws error; TODO better handling
            return super.call(ctx, pos, args, resTypeHint)
        }

        val lazyName = LazyPosString.of(this.pos) { type.name }

        val vCall = fn.compileCall(ctx, lazyName, args, resTypeHint)
        ideInfoPtr.setIdeInfoOrDefault(vCall.ideInfo)

        val vExpr = vCall.vExpr()
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
