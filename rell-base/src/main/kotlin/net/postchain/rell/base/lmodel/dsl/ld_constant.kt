/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.lmodel.L_Constant
import net.postchain.rell.base.lmodel.L_ConstantValue
import net.postchain.rell.base.model.R_Name

class Ld_Constant(private val simpleName: R_Name, private val type: Ld_Type, private val value: L_ConstantValue) {
    fun finish(ctx: Ld_TypeFinishContext): L_Constant {
        val mType = type.finish(ctx)
        return L_Constant(simpleName, mType, value)
    }
}
