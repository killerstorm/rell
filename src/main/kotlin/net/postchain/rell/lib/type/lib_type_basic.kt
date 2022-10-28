/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_UnitValue
import net.postchain.rell.utils.immListOf

object C_Lib_Types {
    private val TYPES = immListOf(
        C_Lib_Type_Unit,
        C_Lib_Type_Boolean,
        C_Lib_Type_Integer,
        C_Lib_Type_Decimal,
        C_Lib_Type_Text,
        C_Lib_Type_ByteArray,
        C_Lib_Type_Rowid,
        C_Lib_Type_Json,
        C_Lib_Type_Range,
        C_Lib_Type_Gtv,
        C_Lib_Type_Signer,
        C_Lib_Type_Guid,
    )

    fun bind(b: C_SysNsProtoBuilder) {
        for (type in TYPES) {
            type.bind(b)
        }

        C_Lib_Type_List.bind(b)
        C_Lib_Type_Set.bind(b)
        C_Lib_Type_Map.bind(b)
    }
}

object C_Lib_Type_Boolean: C_Lib_Type("boolean", R_BooleanType)

object C_Lib_Type_Rowid: C_Lib_Type("rowid", R_RowidType)

object C_Lib_Type_Signer: C_Lib_Type("signer", R_SignerType, defaultMemberFns = false)

object C_Lib_Type_Guid: C_Lib_Type("guid", R_GUIDType, defaultMemberFns = false)

object C_Lib_Type_Unit: C_Lib_Type("unit", R_UnitType, defaultMemberFns = false) {
    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, R_UnitType, listOf(), Fn_Unit)
    }

    private val Fn_Unit = C_SysFunction.simple0(pure = true) {
        Rt_UnitValue
    }
}

object C_Lib_Type_Null {
    val valueMembers: List<C_TypeValueMember> = let {
        val b = C_LibUtils.typeMemFuncBuilder(R_NullType, default = false)
        b.add("to_gtv", R_GtvType, listOf(), C_Lib_Type_Any.ToGtv(R_NullType, false, "to_gtv"))
        C_LibUtils.makeValueMembers(R_NullType, b.build())
    }
}
