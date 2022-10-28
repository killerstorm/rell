/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_TupleField
import net.postchain.rell.model.R_TupleType
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_VirtualTupleType
import net.postchain.rell.model.expr.R_MemberCalculator_TupleAttr
import net.postchain.rell.model.expr.R_MemberCalculator_VirtualTupleAttr

object C_Lib_Type_Tuple {
    fun getValueMembers(type: R_TupleType): List<C_TypeValueMember> {
        val fns = C_LibUtils.typeMemFuncBuilder(type).build()
        val attrMembers = type.fields.withIndex().mapNotNull { (idx, field) ->
            if (field.name == null) null else {
                val mem = C_MemberAttr_RegularTupleAttr(field.type, idx, field)
                C_TypeValueMember_BasicAttr(field.name, mem, field.ideInfo)
            }
        }
        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    fun getAtImplicitAttrs(type: R_TupleType): List<C_AtTypeImplicitAttr> {
        val attrMembers = type.fields.withIndex().map {
            C_AtTypeImplicitAttr_BasicAttr(C_MemberAttr_RegularTupleAttr(it.value.type, it.index, it.value))
        }
        return attrMembers
    }

    private class C_AtTypeImplicitAttr_BasicAttr(private val attr: C_MemberAttr): C_AtTypeImplicitAttr(attr.attrName(), attr.type) {
        override fun nameMsg() = attr.nameMsg()

        override fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
            val effectiveType = C_Utils.effectiveMemberType(type, link.safe)
            return V_MemberAttrExpr(ctx, link, attr, effectiveType)
        }
    }

    private class C_MemberAttr_RegularTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
        : C_MemberAttr_TupleAttr(type, fieldIndex, field)
    {
        override fun calculator() = R_MemberCalculator_TupleAttr(type, fieldIndex)
    }
}

object C_Lib_Type_VirtualTuple {
    fun getValueMembers(type: R_VirtualTupleType): List<C_TypeValueMember> {
        val fns = C_LibUtils.typeMemFuncBuilder(type)
            .add("to_full", type.innerType, listOf(), C_Lib_Type_Virtual.ToFull)
            .build()

        val attrMembers = type.innerType.fields.withIndex().mapNotNull { (idx, field) ->
            if (field.name == null) null else {
                val virtualType = S_VirtualType.virtualMemberType(field.type)
                val mem = C_TypeValueMember_VirtualTupleAttr(virtualType, idx, field)
                C_TypeValueMember_BasicAttr(field.name, mem, field.ideInfo)
            }
        }

        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    private class C_TypeValueMember_VirtualTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
        : C_MemberAttr_TupleAttr(type, fieldIndex, field)
    {
        override fun calculator() = R_MemberCalculator_VirtualTupleAttr(type, fieldIndex)
    }
}
