/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.ast.S_PosValue
import net.postchain.rell.base.compiler.ast.S_VirtualType
import net.postchain.rell.base.compiler.base.core.C_DefinitionContext
import net.postchain.rell.base.compiler.base.core.C_ForIterator
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.core.C_Types
import net.postchain.rell.base.compiler.base.def.C_GenericType
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.fn.*
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.*
import net.postchain.rell.base.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.base.compiler.vexpr.*
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.R_TypeAdapter
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.LazyString
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.lib.type.C_Lib_Type_Any as AnyFns

private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher =
    C_ArgTypeMatcher_MapSub(R_MapKeyValueTypes(keyType, valueType))

object C_Lib_Type_Map {
    const val TYPE_NAME = "map"
    val DEF_NAME = C_LibUtils.defName(TYPE_NAME)

    fun getConstructorFn(mapType: R_MapType): C_GlobalFunction {
        return C_MapConstructorFunction(mapType.keyValueTypes)
    }

    fun getValueMembers(mapType: R_MapType): List<C_TypeValueMember> {
        val fns = getMemberFns(mapType)
        return C_LibUtils.makeValueMembers(mapType, fns)
    }

    private fun getMemberFns(mapType: R_MapType): C_MemberFuncTable {
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(valueType)

        val b = C_LibUtils.typeMemFuncBuilder(mapType)
        getCommonMemberFns(b, mapType, keySetType, valueListType)

        b.add("clear", R_UnitType, listOf(), MapFns.Clear)
        b.add("put", R_UnitType, listOf(keyType, valueType), MapFns.Put)
        b.addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), MapFns.PutAll, depError("put_all"))
        b.addEx("put_all", R_UnitType, listOf(matcherMapSub(keyType, valueType)), MapFns.PutAll)
        b.add("remove", valueType, listOf(keyType), MapFns.Remove)
        b.add("remove_or_null", C_Types.toNullable(valueType), listOf(keyType), MapFns.RemoveOrNull)

        return b.build()
    }

    fun getCommonMemberFns(b: C_MemberFuncBuilder, mapType: R_MapType, keySetType: R_Type, valueListType: R_Type) {
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        b.add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("empty", R_BooleanType, listOf(), MapFns.Empty)
        b.add("size", R_IntegerType, listOf(), MapFns.Size)
        b.add("len", R_IntegerType, listOf(), MapFns.Size, depError("size"))
        b.add("get", valueType, listOf(keyType), MapFns.Get)
        b.add("get_or_null", C_Types.toNullable(valueType), listOf(keyType), MapFns.GetOrNull)
        b.add("get_or_default", C_SysFn_Map_GetOrDefault(mapType))
        b.add("contains", R_BooleanType, listOf(keyType), MapFns.Contains)
        b.add("keys", keySetType, listOf(), MapFns.Keys(keySetType))
        b.add("values", valueListType, listOf(), MapFns.Values(valueListType))
    }

    fun bind(b: C_SysNsProtoBuilder) {
        b.addType(TYPE_NAME, C_GenericType_Map)
    }

    private class C_SysFn_Map_GetOrDefault(private val mapType: R_MapType): C_MemberSpecialFuncCase() {
        override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_MemberFuncCaseMatch? {
            if (args.size != 2) {
                return null
            }

            val keyAdapter = mapType.keyType.getTypeAdapter(args[0].type)
            keyAdapter ?: return null

            val defType = args[1].type
            val resType = C_Types.commonTypeOpt(defType, mapType.valueType)
            resType ?: return null

            val rKeyAdapter = keyAdapter.toRAdapter()

            val body = C_SysMemberFormalParamsFuncBody(resType, makeFn(rKeyAdapter))
            return C_FormalParamsFuncCaseMatch(body, args)
        }

        private fun makeFn(rKeyAdapter: R_TypeAdapter) = C_SysFunction.simple { args ->
            checkEquals(args.size, 3)
            val m = args[0].asMap()
            val k = rKeyAdapter.adaptValue(args[1])
            val v = args[2]
            val res = m[k] ?: v
            res
        }
    }
}

private object C_GenericType_Map: C_GenericType(C_Lib_Type_Map.TYPE_NAME, C_Lib_Type_Map.DEF_NAME, 2) {
    override val rawConstructorFn: C_GlobalFunction = C_MapConstructorFunction(null)

    override fun compileType0(ctx: C_DefinitionContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
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

    private fun checkElementType(ctx: C_DefinitionContext, type: S_PosValue<R_Type>): R_Type {
        return C_Types.checkNotUnit(ctx.msgCtx, type.pos, type.value, null) { "map_elem" toCodeMsg "map element" }
    }
}

private class C_MapConstructorFunction(
    private val rExplicitKeyValueTypes: R_MapKeyValueTypes?,
): C_GlobalFunction() {
    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint
    ): V_GlobalFunctionCall {
        val target = C_FunctionCallTarget_MapConstructorFunction(ctx, name, resTypeHint)
        val vCall = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, target)
        return vCall ?: C_ExprUtils.errorVGlobalCall(ctx, name.pos)
    }

    private inner class C_FunctionCallTarget_MapConstructorFunction(
        private val ctx: C_ExprContext,
        private val name: LazyPosString,
        private val resTypeHint: C_TypeHint,
    ): C_FunctionCallTarget() {
        override fun retType() = null
        override fun typeHints(): C_CallTypeHints = C_CallTypeHints_None
        override fun getParameter(name: R_Name) = null

        override fun compileFull(args: C_FullCallArguments): V_GlobalFunctionCall {
            val vArgs = args.compileSimpleArgs(LazyString.of(C_Lib_Type_Map.TYPE_NAME))

            val rKeyValueTypes = rExplicitKeyValueTypes

            return if (vArgs.isEmpty()) {
                compileNoArgs(ctx, rKeyValueTypes)
            } else if (vArgs.size == 1) {
                val vArg = vArgs[0]
                compileOneArg(ctx, rKeyValueTypes, vArg)
            } else {
                ctx.msgCtx.error(name.pos, "expr_map_argcnt:${vArgs.size}", "Wrong number of arguments for map<>: ${vArgs.size}")
                C_ExprUtils.errorVGlobalCall(ctx, name.pos)
            }
        }

        private fun compileNoArgs(ctx: C_ExprContext, rKeyValueType: R_MapKeyValueTypes?): V_GlobalFunctionCall {
            val hintKeyValueTypes = resTypeHint.getMapKeyValueTypes()
            val rKeyValueTypes = requireTypes(rKeyValueType ?: hintKeyValueTypes)
            val rMapType = R_MapType(rKeyValueTypes)
            val vExpr = V_EmptyMapConstructorExpr(ctx, name.pos, rMapType)
            return V_GlobalFunctionCall(vExpr)
        }

        private fun compileOneArg(ctx: C_ExprContext, rKeyValueTypes: R_MapKeyValueTypes?, vArg: V_Expr): V_GlobalFunctionCall {
            val rArgType = vArg.type
            if (rArgType is R_MapType) {
                return compileOneArgMap(ctx, rKeyValueTypes, vArg, rArgType)
            }

            val cIterator = C_ForIterator.compile(ctx, rArgType, false)
            if (cIterator != null) {
                val vCall = compileOneArgIterator(ctx, rKeyValueTypes, vArg, cIterator)
                if (vCall != null) {
                    return vCall
                }
            }

            ctx.msgCtx.error(name.pos, "expr_map_badtype:${rArgType.strCode()}",
                "Wrong argument type for map<>: ${rArgType.str()}")
            return C_ExprUtils.errorVGlobalCall(ctx, name.pos)
        }

        private fun compileOneArgMap(
            ctx: C_ExprContext,
            rKeyValueTypes: R_MapKeyValueTypes?,
            vArg: V_Expr,
            rMapType: R_MapType
        ): V_GlobalFunctionCall {
            val resTypes = checkKeyValueTypes(name.pos, rKeyValueTypes, rMapType.keyValueTypes)
            val rResMapType = R_MapType(resTypes)
            val vExpr = V_MapCopyMapConstructorExpr(ctx, name.pos, rResMapType, vArg)
            return V_GlobalFunctionCall(vExpr)
        }

        private fun compileOneArgIterator(
            ctx: C_ExprContext,
            rKeyValueTypes: R_MapKeyValueTypes?,
            vArg: V_Expr,
            cIterator: C_ForIterator
        ): V_GlobalFunctionCall? {
            val itemType = cIterator.itemType
            if (itemType !is R_TupleType || itemType.fields.size != 2) {
                return null
            }

            val actualTypes = R_MapKeyValueTypes(itemType.fields[0].type, itemType.fields[1].type)
            val resTypes = checkKeyValueTypes(name.pos, rKeyValueTypes, actualTypes)

            val rResMapType = R_MapType(resTypes)
            val vExpr = V_IteratorCopyMapConstructorExpr(ctx, name.pos, rResMapType, vArg, cIterator)
            return V_GlobalFunctionCall(vExpr)
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

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_GlobalFunctionCall? {
            args.errPartialNotSupported(C_Lib_Type_Map.TYPE_NAME)
            return null
        }
    }
}

object C_Lib_Type_VirtualMap {
    fun getValueMembers(type: R_VirtualMapType): List<C_TypeValueMember> {
        val mapType = type.innerType
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(S_VirtualType.virtualMemberType(valueType))

        val b = C_LibUtils.typeMemFuncBuilder(type)
        C_Lib_Type_Map.getCommonMemberFns(b, mapType, keySetType, valueListType)
        b.add("to_full", mapType, listOf(), C_Lib_Type_Virtual.ToFull)

        val fns = b.build()

        return C_LibUtils.makeValueMembers(type, fns)
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
        val v = map[b]
        v ?: throw Rt_Exception.common("fn:map.get:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
    }

    val GetOrNull = C_SysFunction.simple2(pure = true) { a, b ->
        val map = a.asMap()
        val r = map[b]
        r ?: Rt_NullValue
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
        v ?: throw Rt_Exception.common("fn:map.remove:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
    }

    val RemoveOrNull = C_SysFunction.simple2 { a, b ->
        val map = a.asMutableMap()
        val v = map.remove(b)
        v ?: Rt_NullValue
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
