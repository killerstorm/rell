/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.lmodel.L_Constant
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.runtime.Rt_Value

class Ld_Constant(private val type: Ld_Type, private val value: Ld_ConstantValue) {
    fun finish(ctx: Ld_TypeFinishContext, simpleName: R_Name): L_Constant {
        val mType = type.finish(ctx)
        val rValue = value.getValue(mType)
        return L_Constant(simpleName, mType, rValue)
    }
}

sealed class Ld_ConstantValue {
    abstract fun strCode(): String
    abstract fun getValue(type: M_Type): Rt_Value

    companion object {
        fun make(value: Rt_Value): Ld_ConstantValue = Ld_ConstantValue_Value(value)
        fun make(getter: (R_Type) -> Rt_Value): Ld_ConstantValue = Ld_ConstantValue_Getter(getter)
    }
}

private class Ld_ConstantValue_Value(private val value: Rt_Value): Ld_ConstantValue() {
    override fun strCode() = value.strCode()
    override fun getValue(type: M_Type) = value
}

private class Ld_ConstantValue_Getter(private val getter: (R_Type) -> Rt_Value): Ld_ConstantValue() {
    override fun strCode() = "?"

    override fun getValue(type: M_Type): Rt_Value {
        val rType = L_TypeUtils.getRTypeNotNull(type)
        return getter(rType)
    }
}
