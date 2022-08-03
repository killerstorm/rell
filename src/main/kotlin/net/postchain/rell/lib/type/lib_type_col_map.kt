/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.core.C_ForIterator
import net.postchain.rell.compiler.base.core.C_NamespaceContext
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.def.C_GenericType
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_CallTypeHints
import net.postchain.rell.compiler.base.expr.C_CallTypeHints_None
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.vexpr.V_EmptyMapConstructorExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_IteratorCopyMapConstructorExpr
import net.postchain.rell.compiler.vexpr.V_MapCopyMapConstructorExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.lib.type.C_Lib_Type_Any as AnyFns

private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher =
    C_ArgTypeMatcher_MapSub(R_MapKeyValueTypes(keyType, valueType))

object C_Lib_Type_Map {
    const val TYPE_NAME = "map"

    fun getConstructorFn(mapType: R_MapType): C_GlobalFunction {
        return C_MapConstructorFunction(mapType.keyValueTypes)
    }

    fun getMemberFns(mapType: R_MapType): C_MemberFuncTable {
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(valueType)
        return C_LibUtils.typeMemFuncBuilder(mapType)
            .add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("empty", R_BooleanType, listOf(), MapFns.Empty)
            .add("size", R_IntegerType, listOf(), MapFns.Size)
            .add("len", R_IntegerType, listOf(), MapFns.Size, depError("size"))
            .add("get", valueType, listOf(keyType), MapFns.Get)
            .add("contains", R_BooleanType, listOf(keyType), MapFns.Contains)
            .add("clear", R_UnitType, listOf(), MapFns.Clear)
            .add("put", R_UnitType, listOf(keyType, valueType), MapFns.Put)
            .addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), MapFns.PutAll, depError("put_all"))
            .addEx("put_all", R_UnitType, listOf(matcherMapSub(keyType, valueType)), MapFns.PutAll)
            .add("remove", valueType, listOf(keyType), MapFns.Remove)
            .add("keys", keySetType, listOf(), MapFns.Keys(keySetType))
            .add("values", valueListType, listOf(), MapFns.Values(valueListType))
            .build()
    }

    fun bind(b: C_SysNsProtoBuilder) {
        b.addType(TYPE_NAME, C_GenericType_Map)
    }
}

private object C_GenericType_Map: C_GenericType(C_Lib_Type_Map.TYPE_NAME, 2) {
    override val rawConstructorFn: C_GlobalFunction = C_MapConstructorFunction(null)

    override fun compileType0(ctx: C_NamespaceContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
        checkEquals(args.size, 2)
        val keyEntry = args[0]
        val valueEntry = args[1]
        val keyType = checkElementType(ctx, keyEntry)
        val valueType = checkElementType(ctx, valueEntry)
        return if (keyType.isError() || valueType.isError()) R_CtErrorType else {
            C_Utils.checkMapKeyType(ctx, keyEntry.pos, keyType)
            R_MapType(keyType, valueType)
        }
    }

    private fun checkElementType(ctx: C_NamespaceContext, type: S_PosValue<R_Type>): R_Type {
        return C_Types.checkNotUnit(ctx.msgCtx, type.pos, type.value, null) { "map_elem" toCodeMsg "map element" }
    }
}

private class C_MapConstructorFunction(
    private val rExplicitKeyValueTypes: R_MapKeyValueTypes?,
): C_GlobalFunction(IdeSymbolInfo.DEF_TYPE) {
    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint
    ): V_Expr {
        val target = C_FunctionCallTarget_MapConstructorFunction(ctx, name, resTypeHint)
        val vExpr = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, target)
        return vExpr ?: C_ExprUtils.errorVExpr(ctx, name.pos)
    }

    private inner class C_FunctionCallTarget_MapConstructorFunction(
        private val ctx: C_ExprContext,
        private val name: LazyPosString,
        private val resTypeHint: C_TypeHint,
    ): C_FunctionCallTarget() {
        override fun retType() = null
        override fun typeHints(): C_CallTypeHints = C_CallTypeHints_None
        override fun hasParameter(name: R_Name) = false

        override fun compileFull(args: C_FullCallArguments): V_Expr {
            val vArgs = args.compileSimpleArgs(LazyString.of(C_Lib_Type_Map.TYPE_NAME))

            val rKeyValueTypes = rExplicitKeyValueTypes

            return if (vArgs.isEmpty()) {
                compileNoArgs(ctx, rKeyValueTypes)
            } else if (vArgs.size == 1) {
                val vArg = vArgs[0]
                compileOneArg(ctx, rKeyValueTypes, vArg)
            } else {
                ctx.msgCtx.error(name.pos, "expr_map_argcnt:${vArgs.size}", "Wrong number of arguments for map<>: ${vArgs.size}")
                C_ExprUtils.errorVExpr(ctx, name.pos)
            }
        }

        private fun compileNoArgs(ctx: C_ExprContext, rKeyValueType: R_MapKeyValueTypes?): V_Expr {
            val hintKeyValueTypes = resTypeHint.getMapKeyValueTypes()
            val rKeyValueTypes = requireTypes(rKeyValueType ?: hintKeyValueTypes)
            val rMapType = R_MapType(rKeyValueTypes)
            return V_EmptyMapConstructorExpr(ctx, name.pos, rMapType)
        }

        private fun compileOneArg(ctx: C_ExprContext, rKeyValueTypes: R_MapKeyValueTypes?, vArg: V_Expr): V_Expr {
            val rArgType = vArg.type
            if (rArgType is R_MapType) {
                return compileOneArgMap(ctx, rKeyValueTypes, vArg, rArgType)
            }

            val cIterator = C_ForIterator.compile(ctx, rArgType, false)
            if (cIterator != null) {
                val vExpr = compileOneArgIterator(ctx, rKeyValueTypes, vArg, cIterator)
                if (vExpr != null) {
                    return vExpr
                }
            }

            ctx.msgCtx.error(name.pos, "expr_map_badtype:${rArgType.strCode()}",
                "Wrong argument type for map<>: ${rArgType.str()}")
            return C_ExprUtils.errorVExpr(ctx, name.pos)
        }

        private fun compileOneArgMap(
            ctx: C_ExprContext,
            rKeyValueTypes: R_MapKeyValueTypes?,
            vArg: V_Expr,
            rMapType: R_MapType
        ): V_Expr {
            val resTypes = checkKeyValueTypes(name.pos, rKeyValueTypes, rMapType.keyValueTypes)
            val rResMapType = R_MapType(resTypes)
            return V_MapCopyMapConstructorExpr(ctx, name.pos, rResMapType, vArg)
        }

        private fun compileOneArgIterator(
            ctx: C_ExprContext,
            rKeyValueTypes: R_MapKeyValueTypes?,
            vArg: V_Expr,
            cIterator: C_ForIterator
        ): V_Expr? {
            val itemType = cIterator.itemType
            if (itemType !is R_TupleType || itemType.fields.size != 2) {
                return null
            }

            val actualTypes = R_MapKeyValueTypes(itemType.fields[0].type, itemType.fields[1].type)
            val resTypes = checkKeyValueTypes(name.pos, rKeyValueTypes, actualTypes)

            val rResMapType = R_MapType(resTypes)
            return V_IteratorCopyMapConstructorExpr(ctx, name.pos, rResMapType, vArg, cIterator)
        }

        private fun requireTypes(rKeyValueTypes: R_MapKeyValueTypes?): R_MapKeyValueTypes {
            return C_Errors.checkNotNull(rKeyValueTypes, name.pos) {
                "expr_map_notype" toCodeMsg "Key/value types not specified for map"
            }
        }

        private fun checkKeyValueTypes(pos: S_Pos, formalTypes: R_MapKeyValueTypes?, actualTypes: R_MapKeyValueTypes): R_MapKeyValueTypes {
            val rKeyType = C_Lib_Type_Collection.checkElementType(
                pos,
                formalTypes?.key,
                actualTypes.key,
                "expr_map_key_typemiss",
                "Key type mismatch for map<>"
            )

            val rValueType = C_Lib_Type_Collection.checkElementType(
                pos,
                formalTypes?.value,
                actualTypes.value,
                "expr_map_value_typemiss",
                "Value type mismatch for map<>"
            )

            return R_MapKeyValueTypes(rKeyType, rValueType)
        }

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_Expr? {
            args.errPartialNotSupported(C_Lib_Type_Map.TYPE_NAME)
            return null
        }
    }
}

object C_Lib_Type_VirtualMap {
    fun getMemberFns(type: R_VirtualMapType): C_MemberFuncTable {
        val mapType = type.innerType
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(S_VirtualType.virtualMemberType(valueType))
        return C_LibUtils.typeMemFuncBuilder(type)
            .add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("empty", R_BooleanType, listOf(), MapFns.Empty)
            .add("size", R_IntegerType, listOf(), MapFns.Size)
            .add("get", valueType, listOf(keyType), MapFns.Get)
            .add("contains", R_BooleanType, listOf(keyType), MapFns.Contains)
            .add("keys", keySetType, listOf(), MapFns.Keys(keySetType))
            .add("values", valueListType, listOf(), MapFns.Values(valueListType))
            .add("to_full", mapType, listOf(), C_Lib_Type_Virtual.ToFull)
            .build()
    }
}

private object MapFns {
    val Empty = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        Rt_BooleanValue(map.isEmpty())
    }

    val Size = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        Rt_IntValue(map.size.toLong())
    }

    val Get = C_SysFunction.simple2(pure = true) { a, b ->
        val map = a.asMap()
        val r = map[b]
        if (r == null) {
            throw Rt_Error("fn:map.get:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
        }
        r
    }

    val Contains = C_SysFunction.simple2(pure = true) { a, b ->
        val map = a.asMap()
        Rt_BooleanValue(b in map)
    }

    val Clear = C_SysFunction.simple1 { a ->
        val map = a.asMutableMap()
        map.clear()
        Rt_UnitValue
    }

    val Put = C_SysFunction.simple3 { a, b, c ->
        val map = a.asMutableMap()
        map.put(b, c)
        Rt_UnitValue
    }

    val PutAll = C_SysFunction.simple2 { a, b ->
        val map1 = a.asMutableMap()
        val map2 = b.asMap()
        map1.putAll(map2)
        Rt_UnitValue
    }

    val Remove = C_SysFunction.simple2 { a, b ->
        val map = a.asMutableMap()
        val v = map.remove(b)
        if (v == null) {
            throw Rt_Error("fn:map.remove:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
        }
        v
    }

    fun Keys(type: R_Type) = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        val r = mutableSetOf<Rt_Value>()
        r.addAll(map.keys)
        Rt_SetValue(type, r)
    }

    fun Values(type: R_Type) = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        val r = mutableListOf<Rt_Value>()
        r.addAll(map.values)
        Rt_ListValue(type, r)
    }
}
