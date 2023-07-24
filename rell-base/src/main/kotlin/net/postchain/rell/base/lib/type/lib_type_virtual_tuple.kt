/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.ast.S_VirtualType
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.model.R_TupleField
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.model.R_VirtualTupleType
import net.postchain.rell.base.model.expr.R_MemberCalculator_VirtualTupleAttr

object Lib_Type_VirtualTuple {
    fun getValueMembers(type: R_VirtualTupleType): List<C_TypeValueMember> {
        return type.innerType.fields.mapIndexed { idx, field ->
            val virtualType = S_VirtualType.virtualMemberType(field.type)
            val mem = C_TypeValueMember_VirtualTupleAttr(virtualType, idx, field)
            C_TypeValueMember_BasicAttr(mem)
        }
    }

    private class C_TypeValueMember_VirtualTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
        : C_MemberAttr_TupleAttr(type, fieldIndex, field)
    {
        override fun vAttr(exprCtx: C_ExprContext, pos: S_Pos): V_MemberAttr {
            return V_TypeValueMember_VirtualTupleAttr(type, fieldIndex)
        }

        private class V_TypeValueMember_VirtualTupleAttr(type: R_Type, fieldIndex: Int)
            : V_MemberAttr_TupleAttr(type, fieldIndex)
        {
            override fun calculator() = R_MemberCalculator_VirtualTupleAttr(type, fieldIndex)
        }
    }
}
