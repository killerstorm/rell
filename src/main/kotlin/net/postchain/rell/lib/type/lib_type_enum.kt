/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.fn.C_SysMemberProperty
import net.postchain.rell.compiler.base.namespace.C_NamespaceValue
import net.postchain.rell.compiler.base.namespace.C_NamespaceValue_RtValue
import net.postchain.rell.compiler.base.utils.C_GlobalFuncTable
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.toImmMap

object C_Lib_Type_Enum {
    val PROPERTIES = mapOf(
            "name" to C_SysMemberProperty(R_TextType, EnumFns.Name, pure = true),
            "value" to C_SysMemberProperty(R_IntegerType, EnumFns.Value, pure = true)
        )
        .mapKeys { R_Name.of(it.key) }.toImmMap()

    fun getStaticValues(type: R_EnumType): Map<R_Name, C_NamespaceValue> {
        return type.enum.attrs
                .map {
                it.rName to C_NamespaceValue_RtValue(it.ideInfo, Rt_EnumValue(type, it))
            }
            .toMap()
            .toImmMap()
    }

    fun getStaticFns(type: R_EnumType): C_GlobalFuncTable {
        val b = C_LibUtils.typeGlobalFuncBuilder(type)

        b.add("values", R_ListType(type), listOf(), EnumFns.Values(type.enum))
        b.add("value", type, listOf(R_TextType), EnumFns.Value_Text(type.enum))
        b.add("value", type, listOf(R_IntegerType), EnumFns.Value_Int(type.enum))

        return b.build()
    }

    fun getMemberFns(type: R_EnumType): C_MemberFuncTable {
        return C_LibUtils.typeMemFuncBuilder(type)
            .build()
    }
}

private object EnumFns {
    fun Values(enum: R_EnumDefinition): C_SysFunction {
        val listType = R_ListType(enum.type)
        return C_SysFunction.simple0(pure = true) {
            val list = ArrayList(enum.values())
            Rt_ListValue(listType, list)
        }
    }

    fun Value_Text(enum: R_EnumDefinition) = C_SysFunction.simple1(pure = true) { a ->
        val name = a.asString()
        val attr = enum.attr(name)
        if (attr == null) {
            throw Rt_Error("enum_badname:${enum.appLevelName}:$name", "Enum '${enum.simpleName}' has no value '$name'")
        }
        Rt_EnumValue(enum.type, attr)
    }

    fun Value_Int(enum: R_EnumDefinition) = C_SysFunction.simple1(pure = true) { a ->
        val value = a.asInteger()
        val attr = enum.attr(value)
        if (attr == null) {
            throw Rt_Error("enum_badvalue:${enum.appLevelName}:$value", "Enum '${enum.simpleName}' has no value $value")
        }
        Rt_EnumValue(enum.type, attr)
    }

    val Name = C_SysFunction.simple1(pure = true) { a ->
        val attr = a.asEnum()
        Rt_TextValue(attr.name)
    }

    // Effectively a no-op, as enums are represented by their numeric values on SQL level.
    val Value = C_SysFunction.simple1(Db_SysFunction.template("enum_value", 1, "(#0)"), pure = true) { a ->
        val attr = a.asEnum()
        Rt_IntValue(attr.value.toLong())
    }
}
