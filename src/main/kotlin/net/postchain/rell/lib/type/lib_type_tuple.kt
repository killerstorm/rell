/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.model.R_TupleType
import net.postchain.rell.model.R_VirtualTupleType

object C_Lib_Type_Tuple {
    fun getMemberFns(type: R_TupleType): C_MemberFuncTable {
        return C_LibUtils.typeMemFuncBuilder(type)
            .build()
    }
}

object C_Lib_Type_VirtualTuple {
    fun getMemberFns(type: R_VirtualTupleType): C_MemberFuncTable {
        return C_LibUtils.typeMemFuncBuilder(type)
            .add("to_full", type.innerType, listOf(), C_Lib_Type_Virtual.ToFull)
            .build()
    }
}
