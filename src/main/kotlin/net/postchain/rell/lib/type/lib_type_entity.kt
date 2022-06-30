/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_MemberLink
import net.postchain.rell.compiler.base.fn.C_FuncMatchUtils
import net.postchain.rell.compiler.base.fn.C_MemberFuncCaseCtx
import net.postchain.rell.compiler.base.fn.C_PartialCallArguments
import net.postchain.rell.compiler.base.fn.C_SpecialSysMemberFunction
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.compiler.vexpr.V_EntityToStructExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ObjectToStructExpr
import net.postchain.rell.model.R_EntityType
import net.postchain.rell.model.R_FunctionType
import net.postchain.rell.model.R_ObjectType

object C_Lib_Type_Entity {
    fun getMemberFns(type: R_EntityType): C_MemberFuncTable {
        return C_LibUtils.typeMemFuncBuilder(type)
            .add("to_struct", C_Fn_ToStruct(type, false))
            .add("to_mutable_struct", C_Fn_ToStruct(type, true))
            .build()
    }

    private class C_Fn_ToStruct(
        private val entityType: R_EntityType,
        private val mutable: Boolean
    ): C_SysFn_Common_ToStruct() {
        override fun compile0(ctx: C_ExprContext, member: C_MemberLink): V_Expr {
            return V_EntityToStructExpr(ctx, member, entityType, mutable)
        }
    }
}

object C_Lib_Type_Object {
    fun getMemberFns(type: R_ObjectType): C_MemberFuncTable {
        return C_MemberFuncBuilder(type.name)
            .add("to_struct", C_Fn_ToStruct(type, false))
            .add("to_mutable_struct", C_Fn_ToStruct(type, true))
            .build()
    }

    private class C_Fn_ToStruct(
        private val objectType: R_ObjectType,
        private val mutable: Boolean
    ): C_SysFn_Common_ToStruct() {
        override fun compile0(ctx: C_ExprContext, member: C_MemberLink): V_Expr {
            return V_ObjectToStructExpr(ctx, member.base.pos, objectType, mutable)
        }
    }
}

private abstract class C_SysFn_Common_ToStruct: C_SpecialSysMemberFunction() {
    protected abstract fun compile0(ctx: C_ExprContext, member: C_MemberLink): V_Expr

    final override fun compileCallFull(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): V_Expr {
        if (args.isNotEmpty()) {
            val member = callCtx.member
            val argTypes = args.map { it.type }
            C_FuncMatchUtils.errNoMatch(ctx, member.linkPos, callCtx.qualifiedNameMsg(), argTypes)
        }
        return compile0(ctx, callCtx.member)
    }

    final override fun compileCallPartial(
        ctx: C_ExprContext,
        caseCtx: C_MemberFuncCaseCtx,
        args: C_PartialCallArguments,
        resTypeHint: R_FunctionType?
    ): V_Expr? {
        args.errPartialNotSupported(caseCtx.qualifiedNameMsg())
        return null
    }
}
