/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.fn.C_FuncMatchUtils
import net.postchain.rell.compiler.base.fn.C_MemberFuncCaseCtx
import net.postchain.rell.compiler.base.fn.C_PartialCallArguments
import net.postchain.rell.compiler.base.fn.C_SpecialSysMemberFunction
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*

object C_Lib_Type_Entity {
    fun getValueMembers(type: R_EntityType): List<C_TypeValueMember> {
        val memberFns = C_LibUtils.typeMemFuncBuilder(type)
            .add("to_struct", C_Fn_ToStruct(type, false))
            .add("to_mutable_struct", C_Fn_ToStruct(type, true))
            .build()

        val attrMembers = C_EntityAttrRef.getEntityAttrs(type.rEntity).map { C_TypeValueMember_EntityAttr(it) }
        return C_LibUtils.makeValueMembers(type, memberFns, attrMembers)
    }

    private class C_TypeValueMember_EntityAttr(private val attr: C_EntityAttrRef): C_TypeValueMember(attr.attrName, attr.type) {
        override fun kindMsg() = "attribute"

        override fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember {
            val resultType = C_Utils.effectiveMemberType(attr.type, link.safe)
            val vExpr: V_Expr = V_EntityAttrExpr(ctx, link, attr, resultType)
            return C_ExprMember(C_ValueExpr(vExpr), attr.ideSymbolInfo())
        }
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
    fun getMemberValues(type: R_ObjectType): List<C_TypeValueMember> {
        val fns = C_MemberFuncBuilder(type.defName)
            .add("to_struct", C_Fn_ToStruct(type, false))
            .add("to_mutable_struct", C_Fn_ToStruct(type, true))
            .build()

        val rObject = type.rObject
        val attrMembers = rObject.rEntity.attributes.values.map { C_TypeValueMember_ObjectAttr(rObject, it) }
        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    private class C_TypeValueMember_ObjectAttr(
        private val rObject: R_ObjectDefinition,
        private val attr: R_Attribute,
    ): C_TypeValueMember(attr.rName, attr.type) {
        override fun kindMsg() = "attribute"

        override fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember {
            val attrExpr = C_ValueExpr(V_ObjectAttrExpr(ctx, link.linkPos, rObject, attr))
            return C_ExprMember(attrExpr, attr.ideInfo)
        }
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
