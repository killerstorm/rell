/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.runtime.Rt_Value

class L_Constant(val simpleName: R_Name, val type: M_Type, val value: L_ConstantValue) {
    fun strCode(): String {
        val valueStr = value.strCode()
        return "constant $simpleName: ${type.strCode()} = $valueStr"
    }
}

sealed class L_ConstantValue {
    abstract fun strCode(): String
    abstract fun getValue(type: R_Type): Rt_Value

    companion object {
        fun make(value: Rt_Value): L_ConstantValue = L_ConstantValue_Value(value)
        fun make(getter: (R_Type) -> Rt_Value): L_ConstantValue = L_ConstantValue_Getter(getter)
    }
}

private class L_ConstantValue_Value(private val value: Rt_Value): L_ConstantValue() {
    override fun strCode() = value.strCode()
    override fun getValue(type: R_Type) = value
}

private class L_ConstantValue_Getter(private val getter: (R_Type) -> Rt_Value): L_ConstantValue() {
    override fun strCode() = "?"

    override fun getValue(type: R_Type): Rt_Value {
        return getter(type)
    }
}

class L_NamespaceMember_Constant(
    qualifiedName: R_QualifiedName,
    val constant: L_Constant,
): L_NamespaceMember(qualifiedName) {
    override fun strCode() = constant.strCode()
}

class L_TypeDefMember_Constant(val constant: L_Constant): L_TypeDefMember() {
    override fun strCode() = constant.strCode()
}
