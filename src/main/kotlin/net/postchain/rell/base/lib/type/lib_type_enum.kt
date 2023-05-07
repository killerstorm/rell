/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.expr.C_MemberAttr
import net.postchain.rell.base.compiler.base.expr.C_MemberAttr_SysProperty
import net.postchain.rell.base.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.base.compiler.base.expr.C_TypeValueMember_BasicAttr
import net.postchain.rell.base.compiler.base.fn.C_SysMemberProperty
import net.postchain.rell.base.compiler.base.namespace.C_Namespace
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty_RtValue
import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncTable
import net.postchain.rell.base.compiler.base.utils.C_LibUtils
import net.postchain.rell.base.compiler.base.utils.C_SysFunction
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.ide.IdeSymbolKind
import net.postchain.rell.base.utils.toImmMap

object C_Lib_Type_Enum {
    private val PROPERTIES = mapOf(
            "name" to EnumFns.Name,
            "value" to EnumFns.Value,
        )
        .mapKeys { R_Name.of(it.key) }.toImmMap()

    fun getStaticNs(type: R_EnumType): C_Namespace {
        val valuesMap = getStaticValues(type)
        val values = valuesMap.entries.map { it.key.str to it.value }.toTypedArray()
        val fns = getStaticFns(type)
        return C_LibUtils.makeNs(type.enum.cDefName.toPath(), fns, *values)
    }

    private fun getStaticValues(type: R_EnumType): Map<R_Name, C_NamespaceProperty> {
        return type.enum.attrs
            .map {
                it.rName to C_NamespaceProperty_RtValue(it.ideInfo, Rt_EnumValue(type, it))
            }
            .toMap()
            .toImmMap()
    }

    private fun getStaticFns(type: R_EnumType): C_GlobalFuncTable {
        val b = C_LibUtils.typeGlobalFuncBuilder(type)

        b.add("values", R_ListType(type), listOf(), EnumFns.Values(type.enum))
        b.add("value", type, listOf(R_TextType), EnumFns.Value_Text(type.enum))
        b.add("value", type, listOf(R_IntegerType), EnumFns.Value_Int(type.enum))

        return b.build()
    }

    fun getValueMembers(type: R_EnumType): List<C_TypeValueMember> {
        val fns = C_LibUtils.typeMemFuncBuilder(type).build()
        val attrMembers = PROPERTIES.entries.map {
            val ideName = C_LibUtils.ideName(it.key, IdeSymbolKind.MEM_STRUCT_ATTR)
            val attr: C_MemberAttr = C_MemberAttr_SysProperty(ideName, it.value)
            C_TypeValueMember_BasicAttr(attr)
        }
        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
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
            throw Rt_Exception.common("enum_badname:${enum.appLevelName}:$name", "Enum '${enum.simpleName}' has no value '$name'")
        }
        Rt_EnumValue(enum.type, attr)
    }

    fun Value_Int(enum: R_EnumDefinition) = C_SysFunction.simple1(pure = true) { a ->
        val value = a.asInteger()
        val attr = enum.attr(value)
        if (attr == null) {
            throw Rt_Exception.common("enum_badvalue:${enum.appLevelName}:$value", "Enum '${enum.simpleName}' has no value $value")
        }
        Rt_EnumValue(enum.type, attr)
    }

    val Name = C_SysMemberProperty.simple(R_TextType, pure = true) { a ->
        val attr = a.asEnum()
        Rt_TextValue(attr.name)
    }

    // Effectively a no-op, as enums are represented by their numeric values on SQL level.
    val Value = C_SysMemberProperty.simple(R_IntegerType, Db_SysFunction.template("enum_value", 1, "(#0)"), pure = true) { a ->
        val attr = a.asEnum()
        Rt_IntValue(attr.value.toLong())
    }
}
