/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.fn.C_MemberFuncCaseCtx
import net.postchain.rell.compiler.base.fn.C_SysMemberProperty
import net.postchain.rell.compiler.base.namespace.C_Namespace
import net.postchain.rell.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.compiler.base.namespace.C_NamespaceProperty_RtValue
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.toImmMap

object C_Lib_Type_Enum {
    private val PROPERTIES = mapOf(
            "name" to C_SysMemberProperty(R_TextType, EnumFns.Name, pure = true),
            "value" to C_SysMemberProperty(R_IntegerType, EnumFns.Value, pure = true)
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
        val attrMembers = PROPERTIES.entries.map { C_TypeValueMember_EnumProperty(it.key, it.value) }
        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    private class C_TypeValueMember_EnumProperty(
        name: R_Name,
        private val prop: C_SysMemberProperty,
    ): C_TypeValueMember(name, prop.type) {
        override fun kindMsg() = "value"

        override fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember {
            val fullName = C_Utils.getFullNameLazy(link.base.type, name)
            val caseCtx = C_MemberFuncCaseCtx(link, name, fullName)

            val pos = caseCtx.linkPos
            val effResType = C_Utils.effectiveMemberType(prop.type, link.safe)

            val body = prop.fn.compileCall(C_SysFunctionCtx(ctx, caseCtx.linkPos))

            val desc = V_SysFunctionTargetDescriptor(prop.type, body.rFn, body.dbFn, fullName, pure = prop.pure, synth = true)
            val callTarget = V_FunctionCallTarget_SysMemberFunction(desc, caseCtx.member)

            var vExpr: V_Expr = V_FullFunctionCallExpr(ctx, pos, pos, effResType, callTarget, V_FunctionCallArgs.EMPTY)

            if (caseCtx.member.base.isAtExprItem()) {
                // Wrap just to add implicit what-expr name.
                vExpr = V_SysMemberPropertyExpr(ctx, vExpr, name)
            }

            val cExpr = C_ValueExpr(vExpr)
            return C_ExprMember(cExpr, IdeSymbolInfo(IdeSymbolKind.MEM_STRUCT_ATTR))
        }
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
