package net.postchain.rell.parser

import net.postchain.rell.model.*

object S_LibFunctions {
    private val GLOBAL_FNS = listOf(
            stdFn("unit", RUnitType, listOf(), RSysFunction_Unit),

            stdFn("abs", RIntegerType, listOf(RIntegerType), RSysFunction_Abs, DbSysFunction_Abs),
            stdFn("min", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Min, DbSysFunction_Min),
            stdFn("max", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Max, DbSysFunction_Max),
            stdFn("json", RJSONType, listOf(RTextType), RSysFunction_Json, DbSysFunction_Json),

            S_SysFunction_Range,
            S_SysFunction_Print("print", RSysFunction_Print(false)),
            S_SysFunction_Print("log", RSysFunction_Print(true))
    )

    private val INT_FNS = makeFnMap(
            overMemFn("str", RTextType,
                    overCase(listOf(), RSysFunction_Int_Str, DbSysFunction_Int_Str),
                    overCase(listOf(RIntegerType), RSysFunction_Int_Str)
            ),
            stdMemFn("signum", RIntegerType, listOf(), RSysFunction_Int_Signum)
    )

    private val TEXT_FNS = makeFnMap(
            stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Text_Empty),
            stdMemFn("size", RIntegerType, listOf(), RSysFunction_Text_Size, DbSysFunction_Text_Size),
            stdMemFn("len", RIntegerType, listOf(), RSysFunction_Text_Size, DbSysFunction_Text_Size),
            stdMemFn("upperCase", RTextType, listOf(), RSysFunction_Text_UpperCase),
            stdMemFn("lowerCase", RTextType, listOf(), RSysFunction_Text_LowerCase),
            stdMemFn("compareTo", RIntegerType, listOf(RTextType), RSysFunction_Text_CompareTo),
            stdMemFn("startsWith", RBooleanType, listOf(RTextType), RSysFunction_Text_StartsWith),
            stdMemFn("endsWith", RBooleanType, listOf(RTextType), RSysFunction_Text_EndsWith),
            stdMemFn("contains", RBooleanType, listOf(RTextType), RSysFunction_Text_Contains),
            stdMemFn("replace", RTextType, listOf(RTextType, RTextType), RSysFunction_Text_Replace),
            stdMemFn("split", RTextType, listOf(RTextType), RSysFunction_Text_Split),
            stdMemFn("trim", RTextType, listOf(), RSysFunction_Text_Trim),
            stdMemFn("matches", RBooleanType, listOf(RTextType), RSysFunction_Text_Matches),
            stdMemFn("encode", RByteArrayType, listOf(), RSysFunction_Text_Encode),
            stdMemFn("chatAt", RIntegerType, listOf(RIntegerType), RSysFunction_Text_CharAt),
            overMemFn("indexOf", RTextType,
                    overCase(listOf(RTextType), RSysFunction_Text_IndexOf),
                    overCase(listOf(RTextType, RIntegerType), RSysFunction_Text_IndexOf)
            ),
            overMemFn("lastIndexOf", RTextType,
                    overCase(listOf(RTextType), RSysFunction_Text_LastIndexOf),
                    overCase(listOf(RTextType, RIntegerType), RSysFunction_Text_LastIndexOf)
            ),
            overMemFn("sub", RTextType,
                    overCase(listOf(RIntegerType), RSysFunction_Text_Sub),
                    overCase(listOf(RIntegerType, RIntegerType), RSysFunction_Text_Sub)
            )
    )

    private val BYTEARRAY_FNS = makeFnMap(
            stdMemFn("empty", RBooleanType, listOf(), RSysFunction_ByteArray_Empty),
            stdMemFn("size", RIntegerType, listOf(), RSysFunction_ByteArray_Size, DbSysFunction_ByteArray_Size),
            stdMemFn("len", RIntegerType, listOf(), RSysFunction_ByteArray_Size, DbSysFunction_ByteArray_Size),
            stdMemFn("decode", RTextType, listOf(), RSysFunction_ByteArray_Decode),
            overMemFn("sub", RByteArrayType,
                    overCase(listOf(RIntegerType), RSysFunction_ByteArray_Sub),
                    overCase(listOf(RIntegerType, RIntegerType), RSysFunction_ByteArray_Sub)
            )
    )

    private val JSON_FNS = makeFnMap(
            stdMemFn("str", RTextType, listOf(), RSysFunction_Json_Str, DbSysFunction_Json_Str)
    )

    fun getGlobalFunctions() = GLOBAL_FNS

    fun getMemberFunction(type: RType, name: String): S_SysMemberFunction {
        val map = getTypeMemberFunctions(type)
        val fn = map[name]
        if (fn == null) {
            throw CtError("expr_call_unknown:${type.toStrictString()}:$name",
                    "Unknown function '$name' for type ${type.toStrictString()}")
        }
        return fn
    }

    private fun getTypeMemberFunctions(type: RType): Map<String, S_SysMemberFunction> {
        if (type == RIntegerType) {
            return INT_FNS
        } else if (type == RTextType) {
            return TEXT_FNS
        } else if (type == RByteArrayType) {
            return BYTEARRAY_FNS
        } else if (type == RJSONType) {
            return JSON_FNS
        } else if (type is RListType) {
            return getListFns(type.elementType)
        } else if (type is RSetType) {
            return getSetFns(type.elementType)
        } else if (type is RMapType) {
            return getMapFns(type.keyType, type.valueType)
        } else {
            return mapOf()
        }
    }

    private fun getListFns(elemType: RType): Map<String, S_SysMemberFunction> {
        val listType = RListType(elemType)
        val setType = RSetType(elemType)
        return makeFnMap(
                stdMemFn("str", RTextType, listOf(), RSysFunction_ToString),
                stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Collection_Empty),
                stdMemFn("size", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("len", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("get", elemType, listOf(RIntegerType), RSysFunction_List_Get),
                stdMemFn("contains", RBooleanType, listOf(elemType), RSysFunction_Collection_Contains),
                stdMemFn("indexOf", RIntegerType, listOf(elemType), RSysFunction_List_IndexOf),
                stdMemFn("sub", listType, listOf(RIntegerType, RIntegerType), RSysFunction_List_Sub),
                stdMemFn("clear", RUnitType, listOf(), RSysFunction_Collection_Clear),
                stdMemFn("remove", RBooleanType, listOf(elemType), RSysFunction_Collection_Remove),
                stdMemFn("removeAt", elemType, listOf(RIntegerType), RSysFunction_List_RemoveAt),
                stdMemFn("_set", elemType, listOf(RIntegerType, elemType), RSysFunction_List_Set),
                overMemFn("removeAll", RBooleanType,
                        overCase(listOf(listType), RSysFunction_Collection_RemoveAll),
                        overCase(listOf(setType), RSysFunction_Collection_RemoveAll)
                ),
                overMemFn("containsAll", RBooleanType,
                        overCase(listOf(listType), RSysFunction_Collection_ContainsAll),
                        overCase(listOf(setType), RSysFunction_Collection_ContainsAll)
                ),
                overMemFn("add", RBooleanType,
                        overCase(listOf(elemType), RSysFunction_Collection_Add),
                        overCase(listOf(RIntegerType, elemType), RSysFunction_List_Add)
                ),
                overMemFn("addAll", RBooleanType,
                        overCase(listOf(listType), RSysFunction_Collection_AddAll),
                        overCase(listOf(setType), RSysFunction_Collection_AddAll),
                        overCase(listOf(RIntegerType, listType), RSysFunction_List_AddAll),
                        overCase(listOf(RIntegerType, setType), RSysFunction_List_AddAll)
                )
        )
    }

    private fun getSetFns(elemType: RType): Map<String, S_SysMemberFunction> {
        val listType = RListType(elemType)
        val setType = RSetType(elemType)
        return makeFnMap(
                stdMemFn("str", RTextType, listOf(), RSysFunction_ToString),
                stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Collection_Empty),
                stdMemFn("size", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("len", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("contains", RBooleanType, listOf(elemType), RSysFunction_Collection_Contains),
                stdMemFn("clear", RUnitType, listOf(), RSysFunction_Collection_Clear),
                stdMemFn("remove", RBooleanType, listOf(elemType), RSysFunction_Collection_Remove),
                stdMemFn("add", RBooleanType, listOf(elemType), RSysFunction_Collection_Add),
                overMemFn("removeAll", RBooleanType,
                        overCase(listOf(listType), RSysFunction_Collection_RemoveAll),
                        overCase(listOf(setType), RSysFunction_Collection_RemoveAll)
                ),
                overMemFn("containsAll", RBooleanType,
                        overCase(listOf(listType), RSysFunction_Collection_ContainsAll),
                        overCase(listOf(setType), RSysFunction_Collection_ContainsAll)
                ),
                overMemFn("addAll", RBooleanType,
                        overCase(listOf(listType), RSysFunction_Collection_AddAll),
                        overCase(listOf(setType), RSysFunction_Collection_AddAll)
                )
        )
    }

    private fun getMapFns(keyType: RType, valueType: RType): Map<String, S_SysMemberFunction> {
        val mapType = RMapType(keyType, valueType)
        val keySetType = RSetType(keyType)
        val valueListType = RListType(valueType)
        return makeFnMap(
                stdMemFn("str", RTextType, listOf(), RSysFunction_ToString),
                stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Map_Empty),
                stdMemFn("size", RIntegerType, listOf(), RSysFunction_Map_Size),
                stdMemFn("len", RIntegerType, listOf(), RSysFunction_Map_Size),
                stdMemFn("get", valueType, listOf(keyType), RSysFunction_Map_Get),
                stdMemFn("contains", RBooleanType, listOf(keyType), RSysFunction_Map_Contains),
                stdMemFn("clear", RUnitType, listOf(), RSysFunction_Map_Clear),
                stdMemFn("put", RUnitType, listOf(keyType, valueType), RSysFunction_Map_Put),
                stdMemFn("putAll", RUnitType, listOf(mapType), RSysFunction_Map_PutAll),
                stdMemFn("remove", valueType, listOf(keyType), RSysFunction_Map_Remove),
                stdMemFn("keys", keySetType, listOf(), RSysFunction_Map_Keys(keySetType)),
                stdMemFn("values", valueListType, listOf(), RSysFunction_Map_Values(valueListType))
        )
    }

    private fun makeFnMap(vararg fns: S_SysMemberFunction): Map<String, S_SysMemberFunction> {
        val map = mutableMapOf<String, S_SysMemberFunction>()
        for (fn in fns) {
            check(!(fn.name in map))
            map[fn.name] = fn
        }
        return map.toMap()
    }
}


private class S_SysFunction_Print(name: String, val rFn: RSysFunction): S_SysFunction(name) {
    override fun compileCall(args: List<RExpr>): RExpr {
        // Print supports any number of arguments and any types.
        return RSysCallExpr(RUnitType, rFn, args)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr {
        TODO()
    }
}

private object S_SysFunction_Range: S_SysFunction("range") {
    override fun compileCall(args: List<RExpr>): RExpr {
        val allParams = listOf(RIntegerType, RIntegerType, RIntegerType)
        val n = Math.min(Math.max(args.size, 1), 3)
        val params = allParams.subList(0, n)

        val args2 = matchArgs(name, params, args)

        val allArgs = if (args2.size == 1) {
            listOf(RIntegerLiteralExpr(0), args2[0], RIntegerLiteralExpr(1))
        } else if (args2.size == 2) {
            listOf(args2[0], args2[1], RIntegerLiteralExpr(1))
        } else {
            args2
        }

        return RSysCallExpr(RRangeType, RSysFunction_Range, allArgs)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr = TODO()
}

private fun stdFn(name: String, type: RType, params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null)
        : S_SysFunction = S_StdSysFunction(name, type, listOf(overCase(params, rFn, dbFn)))

private fun stdMemFn(name: String, type: RType, params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null)
        : S_SysMemberFunction = S_StdSysMemberFunction(name, type, listOf(overCase(params, rFn, dbFn)))

private fun overMemFn(name: String, type: RType, vararg cases: S_OverloadFnCase): S_SysMemberFunction =
        S_StdSysMemberFunction(name, type, cases.toList())

private fun overCase(params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null): S_OverloadFnCase =
        S_OverloadFnCase(params, rFn, dbFn)
