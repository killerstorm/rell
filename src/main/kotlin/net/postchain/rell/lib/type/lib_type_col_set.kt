/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.model.R_ListType
import net.postchain.rell.model.R_SetType
import net.postchain.rell.model.R_VirtualSetType

object C_Lib_Type_Set {
    fun getMemberFns(setType: R_SetType): C_MemberFuncTable {
        val listType = R_ListType(setType.elementType)
        val b = C_LibUtils.typeMemFuncBuilder(setType)
        C_Lib_Type_Collection.bindMemberFns(b, listType)
        return b.build()
    }
}

object C_Lib_Type_VirtualSet {
    fun getMemberFns(type: R_VirtualSetType): C_MemberFuncTable {
        val b = C_LibUtils.typeMemFuncBuilder(type)
        C_Lib_Type_VirtualCollection.bindMemberFns(b, type.innerType)
        return b.build()
    }
}
