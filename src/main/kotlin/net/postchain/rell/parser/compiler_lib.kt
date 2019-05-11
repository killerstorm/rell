package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_IntValue

object C_LibFunctions {
    private val GLOBAL_FNS = C_GlobalFuncBuilder()
            .add("unit", R_UnitType, listOf(), R_SysFn_Unit)

            .add("abs", R_IntegerType, listOf(R_IntegerType), R_SysFn_Abs, Db_SysFn_Abs)
            .add("min", R_IntegerType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Min, Db_SysFn_Min)
            .add("max", R_IntegerType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Max, Db_SysFn_Max)
            .add("is_signer", R_BooleanType, listOf(R_ByteArrayType), R_SysFn_IsSigner)
            .add("json", R_JsonType, listOf(R_TextType), R_SysFn_Json, Db_SysFn_Json)

            .add("require", C_SysFunction_Require_Boolean)
            .add("require", C_SysFunction_Require_Nullable)
            .add("requireNotEmpty", C_SysFunction_Require_Collection)
            .add("requireNotEmpty", C_SysFunction_Require_Nullable)
            .add("require_not_empty", C_SysFunction_Require_Collection)
            .add("require_not_empty", C_SysFunction_Require_Nullable)
            .add("exists", C_SysFunction_Exists)

            .add("integer", R_IntegerType, listOf(R_TextType), R_SysFn_Int_Parse)
            .add("integer", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Int_Parse)

            .add("byte_array", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray_New_Text)
            .add("byte_array", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), R_SysFn_ByteArray_New_List)

            .add("range", R_RangeType, listOf(R_IntegerType), R_SysFn_Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType, R_IntegerType), R_SysFn_Range)

            .add("print", C_SysFunction_Print(R_SysFn_Print(false)))
            .add("log", C_SysFunction_Print(R_SysFn_Print(true)))

            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), R_SysFn_VerifySignature)

            .add("_type_of", C_SysFunction_TypeOf)
            .add("_nullable", C_SysFunction_Nullable(null))
            .add("_nullable_int", C_SysFunction_Nullable(R_IntegerType))
            .addEx("_strict_str", R_TextType, listOf(C_ArgTypeMatcher_Any), R_SysFn_StrictStr)

            .build()

    private val BOOLEAN_FNS = typeMemFuncBuilder(R_BooleanType)
            .build()

    private val INTEGER_FNS = typeMemFuncBuilder(R_IntegerType)
            .add("str", R_TextType, listOf(), R_SysFn_Int_Str, Db_SysFn_Int_Str)
            .add("str", R_TextType, listOf(R_IntegerType), R_SysFn_Int_Str)
            .add("hex", R_TextType, listOf(), R_SysFn_Int_Hex)
            .add("signum", R_IntegerType, listOf(), R_SysFn_Int_Signum)
            .build()

    private val BOOLEAN_NAMESPACE_FNS = typeGlobalFuncBuilder(R_BooleanType)
            .build()

    private val BOOLEAN_NAMESPACE = makeNamespace(BOOLEAN_NAMESPACE_FNS)

    private val INTEGER_NAMESPACE_FNS = typeGlobalFuncBuilder(R_IntegerType)
            .add("parseHex", R_IntegerType, listOf(R_TextType), R_SysFn_Int_ParseHex)
            .add("parse_hex", R_IntegerType, listOf(R_TextType), R_SysFn_Int_ParseHex)
            .build()

    private val INTEGER_NAMESPACE = makeNamespace(
            INTEGER_NAMESPACE_FNS,
            stdConstValue("MIN_VALUE", Long.MIN_VALUE),
            stdConstValue("MAX_VALUE", Long.MAX_VALUE)
    )

    private val GTV_NAMESPACE_FNS = C_GlobalFuncBuilder()
            .add("fromBytes", R_GtvType, listOf(R_ByteArrayType), R_SysFn_Gtv_FromBytes)
            .add("from_bytes", R_GtvType, listOf(R_ByteArrayType), R_SysFn_Gtv_FromBytes)
            .add("fromJSON", R_GtvType, listOf(R_TextType), R_SysFn_Gtv_FromJson_Text)
            .add("from_json", R_GtvType, listOf(R_TextType), R_SysFn_Gtv_FromJson_Text)
            .add("fromJSON", R_GtvType, listOf(R_JsonType), R_SysFn_Gtv_FromJson_Json)
            .add("from_json", R_GtvType, listOf(R_JsonType), R_SysFn_Gtv_FromJson_Json)
            .build()

    private val GTV_NAMESPACE = makeNamespace(GTV_NAMESPACE_FNS)

    private val CHAIN_CONTEXT_NAMESPACE = makeNamespace(
            C_GlobalFuncTable.EMPTY,
            stdFnValue("raw_config", R_GtvType, R_SysFn_ChainContext_RawConfig),
            Pair("args", C_NsValue_ChainContext_Args)
    )

    private val OP_CONTEXT_NAMESPACE = makeNamespace(
            C_GlobalFuncTable.EMPTY,
            Pair("last_block_time", C_Ns_OpContext.Value_LastBlockTime),
            Pair("transaction", C_Ns_OpContext.Value_Transaction)
    )

    private val TEXT_NAMESPACE_FNS = typeGlobalFuncBuilder(R_TextType)
            .build()

    private val TEXT_NAMESPACE = makeNamespace(TEXT_NAMESPACE_FNS)

    private val TEXT_FNS = typeMemFuncBuilder(R_TextType)
            .add("empty", R_BooleanType, listOf(), R_SysFn_Text_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size)
            .add("upperCase", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase)
            .add("upper_case", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase)
            .add("lowerCase", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase)
            .add("lower_case", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase)
            .add("compareTo", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo)
            .add("compare_to", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo)
            .add("startsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith)
            .add("starts_with", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith)
            .add("endsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith)
            .add("ends_with", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith)
            .add("contains", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Contains)
            .add("replace", R_TextType, listOf(R_TextType, R_TextType), R_SysFn_Text_Replace)
            .add("split", R_TextType, listOf(R_TextType), R_SysFn_Text_Split)
            .add("trim", R_TextType, listOf(), R_SysFn_Text_Trim)
            .add("matches", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Matches)
            .add("encode", R_ByteArrayType, listOf(), R_SysFn_Text_Encode)
            .add("charAt", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt)
            .add("char_at", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt)
            .add("indexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf)
            .add("index_of", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf)
            .add("indexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf)
            .add("index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf)
            .add("last_index_of", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf)
            .add("last_index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf)
            .add("sub", R_TextType, listOf(R_IntegerType), R_SysFn_Text_Sub)
            .add("sub", R_TextType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Text_Sub)
            .add("format", C_SysMemberFunction_Text_Format)
            .build()

    private val BYTEARRAY_NAMESPACE_FNS = typeGlobalFuncBuilder(R_ByteArrayType)
            .build()

    private val BYTEARRAY_NAMESPACE = makeNamespace(BYTEARRAY_NAMESPACE_FNS)

    private val BYTEARRAY_FNS = typeMemFuncBuilder(R_ByteArrayType)
            .add("empty", R_BooleanType, listOf(), R_SysFn_ByteArray_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_ByteArray_Size, Db_SysFn_ByteArray_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_ByteArray_Size, Db_SysFn_ByteArray_Size)
            .add("decode", R_TextType, listOf(), R_SysFn_ByteArray_Decode)
            .add("toList", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray_ToList)
            .add("to_list", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray_ToList)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType), R_SysFn_ByteArray_Sub)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType, R_IntegerType), R_SysFn_ByteArray_Sub)
            .build()

    private val JSON_NAMESPACE_FNS = typeGlobalFuncBuilder(R_JsonType)
            .build()

    private val JSON_NAMESPACE = makeNamespace(JSON_NAMESPACE_FNS)

    private val JSON_FNS = typeMemFuncBuilder(R_JsonType)
            .add("str", R_TextType, listOf(), R_SysFn_Json_Str, Db_SysFn_Json_Str)
            .build()

    private val RANGE_NAMESPACE_FNS = typeGlobalFuncBuilder(R_RangeType)
            .build()

    private val RANGE_NAMESPACE = makeNamespace(RANGE_NAMESPACE_FNS)

    private val GTV_FNS = typeMemFuncBuilder(R_GtvType)
            .add("toBytes", R_ByteArrayType, listOf(), R_SysFn_Gtv_ToBytes)
            .add("to_bytes", R_ByteArrayType, listOf(), R_SysFn_Gtv_ToBytes)
            .add("toJSON", R_JsonType, listOf(), R_SysFn_Gtv_ToJson)
            .add("to_json", R_JsonType, listOf(), R_SysFn_Gtv_ToJson)
            .build()

    private val NAMESPACES = mapOf(
            "boolean" to BOOLEAN_NAMESPACE,
            "integer" to INTEGER_NAMESPACE,
            "text" to TEXT_NAMESPACE,
            "byte_array" to BYTEARRAY_NAMESPACE,
            "json" to JSON_NAMESPACE,
            "range" to RANGE_NAMESPACE,
            "GTXValue" to GTV_NAMESPACE,
            "gtv" to GTV_NAMESPACE,
            "chain_context" to CHAIN_CONTEXT_NAMESPACE,
            C_Ns_OpContext.NAME to OP_CONTEXT_NAMESPACE
    )

    fun getGlobalFunctions(): Map<String, C_GlobalFunction> = GLOBAL_FNS.toMap()
    fun getSystemNamespaces(): Map<String, C_Namespace> = NAMESPACES

    fun getMemberFunctionOpt(type: R_Type, name: String): C_SysMemberFunction? {
        val table = getTypeMemberFunctions(type)
        val fn = table.get(name)
        return fn
    }

    private fun getTypeMemberFunctions(type: R_Type): C_MemberFuncTable {
        return when (type) {
            R_BooleanType -> BOOLEAN_FNS
            R_IntegerType -> INTEGER_FNS
            R_TextType -> TEXT_FNS
            R_ByteArrayType -> BYTEARRAY_FNS
            R_JsonType -> JSON_FNS
            R_GtvType -> GTV_FNS
            is R_ListType -> getListFns(type)
            is R_SetType -> getSetFns(type)
            is R_MapType -> getMapFns(type)
            is R_ClassType -> getClassFns(type)
            is R_EnumType -> getEnumFns(type)
            is R_TupleType -> getTupleFns(type)
            is R_RecordType -> getRecordFns(type)
            else -> C_MemberFuncTable(mapOf())
        }
    }

    private fun getClassFns(type: R_ClassType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .build()
    }

    private fun getEnumFns(type: R_EnumType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .build()
    }

    private fun getTupleFns(type: R_TupleType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .build()
    }

    private fun getListFns(listType: R_ListType): C_MemberFuncTable {
        val elemType = listType.elementType
        val comparator = elemType.comparator()
        val comparator2 = comparator ?: Comparator { _, _ -> 0 }
        return typeMemFuncBuilder(listType)
                .add("str", R_TextType, listOf(), R_SysFn_ToString)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Collection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("get", elemType, listOf(R_IntegerType), R_SysFn_List_Get)
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("indexOf", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf)
                .add("index_of", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("removeAt", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt)
                .add("remove_at", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt)
                .add("_set", elemType, listOf(R_IntegerType, elemType), R_SysFn_List_Set)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .addEx("remove_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .add("sub", listType, listOf(R_IntegerType), R_SysFn_List_Sub)
                .add("sub", listType, listOf(R_IntegerType, R_IntegerType), R_SysFn_List_Sub)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .add("add", R_BooleanType, listOf(R_IntegerType, elemType), R_SysFn_List_Add)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("addAll", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), R_SysFn_List_AddAll)
                .addEx("add_all", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), R_SysFn_List_AddAll)
                .addIf(comparator != null, "_sort", R_UnitType, listOf(), R_SysFn_List_Sort(comparator2))
                .addIf(comparator != null, "sorted", listType, listOf(), R_SysFn_Collection_Sorted(listType, comparator2))
                .build()
    }

    private fun getSetFns(setType: R_SetType): C_MemberFuncTable {
        val elemType = setType.elementType
        val listType = R_ListType(elemType)
        val comparator = elemType.comparator()
        val comparator2 = comparator ?: Comparator { _, _ -> 0 }
        return typeMemFuncBuilder(setType)
                .add("str", R_TextType, listOf(), R_SysFn_ToString)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Collection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .addEx("remove_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .addIf(comparator != null, "sorted", listType, listOf(), R_SysFn_Collection_Sorted(listType, comparator2))
                .build()
    }

    private fun getMapFns(mapType: R_MapType): C_MemberFuncTable {
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(valueType)
        return typeMemFuncBuilder(mapType)
                .add("str", R_TextType, listOf(), R_SysFn_ToString)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Map_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Map_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Map_Size)
                .add("get", valueType, listOf(keyType), R_SysFn_Map_Get)
                .add("contains", R_BooleanType, listOf(keyType), R_SysFn_Map_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_Map_Clear)
                .add("put", R_UnitType, listOf(keyType, valueType), R_SysFn_Map_Put)
                .addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), R_SysFn_Map_PutAll)
                .addEx("put_all", R_UnitType, listOf(matcherMapSub(keyType, valueType)), R_SysFn_Map_PutAll)
                .add("remove", valueType, listOf(keyType), R_SysFn_Map_Remove)
                .add("keys", keySetType, listOf(), R_SysFn_Map_Keys(keySetType))
                .add("values", valueListType, listOf(), R_SysFn_Map_Values(valueListType))
                .build()
    }

    fun makeRecordNamespace(type: R_RecordType): C_Namespace {
        val mFromBytes = gtvGlobalFn(type, false, R_SysFn_Record_FromBytes(type))
        val mFromGtv = gtvGlobalFn(type, false, R_SysFn_Record_FromGtv(type, false))
        val mFromGtvPretty = gtvGlobalFn(type, true, R_SysFn_Record_FromGtv(type, true))

        val fns = C_GlobalFuncBuilder()
                .add("fromBytes", listOf(R_ByteArrayType), mFromBytes)
                .add("from_bytes", listOf(R_ByteArrayType), mFromBytes)
                .add("fromGTXValue", listOf(R_GtvType), mFromGtv)
                .add("from_gtv", listOf(R_GtvType), mFromGtv)
                .add("fromPrettyGTXValue", listOf(R_GtvType), mFromGtvPretty)
                .add("from_gtv_pretty", listOf(R_GtvType), mFromGtvPretty)
                .build()

        return makeNamespace(fns)
    }

    private fun gtvGlobalFn(type: R_Type, human: Boolean, fn: R_SysFunction): C_GlobalFuncCaseMatch {
        val flags = type.completeFlags()
        val flag = if (human) flags.gtvHuman else flags.gtvCompact
        return if (!flag.compatible) C_SysFunction_Invalid(type) else C_StdGlobalFuncCaseMatch(R_GtvType, fn)
    }

    private fun getRecordFns(type: R_RecordType): C_MemberFuncTable {
        val mToBytes = gtvMemFn(type, false, R_ByteArrayType, R_SysFn_Record_ToBytes(type))
        val mToGtv = gtvMemFn(type, false, R_GtvType, R_SysFn_Record_ToGtv(type, false))
        val mToGtvPretty = gtvMemFn(type, true, R_GtvType, R_SysFn_Record_ToGtv(type, true))
        val mHash = gtvMemFn(type, false, R_ByteArrayType, R_SysFn_Any_Hash(type))

        return C_MemberFuncBuilder()
                .add("toBytes", listOf(), mToBytes)
                .add("to_bytes", listOf(), mToBytes)
                .add("toGTXValue", listOf(), mToGtv)
                .add("to_gtv", listOf(), mToGtv)
                .add("toPrettyGTXValue", listOf(), mToGtvPretty)
                .add("to_gtv_pretty", listOf(), mToGtvPretty)
                .add("hash", listOf(), mHash)
                .build()
    }

    private fun gtvMemFn(
            type: R_Type,
            human: Boolean,
            resType: R_Type,
            fn: R_SysFunction
    ): C_MemberFuncCaseMatch {
        val flags = type.completeFlags()
        val flag = if (human) flags.gtvHuman else flags.gtvCompact
        return if (!flag.compatible) C_SysMemberFunction_Invalid(type) else C_StdMemberFuncCaseMatch(resType, fn)
    }

    fun getTypeStaticFunction(type: R_Type, name: String): C_GlobalFunction? {
        val b = typeGlobalFuncBuilder(type)
        when(type) {
            is R_EnumType -> getEnumStaticFns(b, type)
        }
        val fns = b.build()
        val fn = fns.get(name)
        return fn
    }

    private fun getEnumStaticFns(b: C_GlobalFuncBuilder, type: R_EnumType) {
        b.add("values", R_ListType(type), listOf(), R_SysFn_Enum_Values(type))
        b.add("value", type, listOf(R_TextType), R_SysFn_Enum_Value_Text(type))
        b.add("value", type, listOf(R_IntegerType), R_SysFn_Enum_Value_Int(type))
    }

    private fun typeGlobalFuncBuilder(type: R_Type): C_GlobalFuncBuilder {
        val b = C_GlobalFuncBuilder()
        b.add("from_gtv", listOf(R_GtvType), gtvGlobalFn(type, false, R_SysFn_Any_FromGtv(type, false, "from_gtv")))
        b.add("from_gtv_pretty", listOf(R_GtvType), gtvGlobalFn(type, true, R_SysFn_Any_FromGtv(type, true, "from_gtv_pretty")))
        return b
    }

    private fun typeMemFuncBuilder(type: R_Type): C_MemberFuncBuilder {
        val b = C_MemberFuncBuilder()
        b.add("hash", listOf(), gtvMemFn(type, false, R_ByteArrayType, R_SysFn_Any_Hash(type)))
        b.add("to_gtv", listOf(), gtvMemFn(type, false, R_GtvType, R_SysFn_Any_ToGtv(type, false, "to_gtv")))
        b.add("to_gtv_pretty", listOf(), gtvMemFn(type, true, R_GtvType, R_SysFn_Any_ToGtv(type, true, "to_gtv_pretty")))
        return b
    }
}

object C_Ns_OpContext {
    val NAME = "op_context"

    fun transactionExpr(entCtx: C_EntityContext): R_Expr {
        val type = entCtx.nsCtx.modCtx.transactionClassType
        return R_SysCallExpr(type, R_SysFn_OpContext_Transaction(type), listOf())
    }

    private fun checkCtx(entCtx: C_EntityContext, name: List<S_Name>) {
        val et = entCtx.entityType
        if (et != C_EntityType.OPERATION && et != C_EntityType.FUNCTION && et != C_EntityType.CLASS) {
            throw C_Error(name[0].pos, "op_ctx_noop", "Cannot access '$NAME' outside of an operation")
        }
    }

    object Value_LastBlockTime: C_NamespaceValue_RExpr() {
        override fun get0(entCtx: C_EntityContext, name: List<S_Name>): R_Expr {
            checkCtx(entCtx, name)
            return R_SysCallExpr(R_IntegerType, R_SysFn_OpContext_LastBlockTime, listOf())
        }
    }

    object Value_Transaction: C_NamespaceValue_RExpr() {
        override fun get0(entCtx: C_EntityContext, name: List<S_Name>): R_Expr {
            checkCtx(entCtx, name)
            return transactionExpr(entCtx)
        }
    }
}

private object C_NsValue_ChainContext_Args: C_NamespaceValue_RExpr() {
    override fun get0(entCtx: C_EntityContext, name: List<S_Name>): R_Expr {
        val record = entCtx.nsCtx.modCtx.getModuleArgsRecord()
        if (record == null) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "expr_chainctx_args_norec",
                    "To use '$nameStr', define a record '${C_Defs.MODULE_ARGS_RECORD}'")
        }
        val type = R_NullableType(record)
        return R_SysCallExpr(type, R_SysFn_ChainContext_Args, listOf())
    }
}

private class C_SysFunction_Print(val rFn: R_SysFunction): C_SimpleGlobalFuncCase() {
    override fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        return CaseMatch()
    }

    private inner class CaseMatch: C_SimpleGlobalFuncCaseMatch() {
        override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
            // Print supports any number of arguments and any types.
            val rExpr = R_SysCallExpr(R_UnitType, rFn, args)
            return rExpr
        }
    }
}

private object C_SysFunction_TypeOf: C_SimpleGlobalFuncCase() {
    override fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val type = args[0]
        val str = type.toStrictString()
        return CaseMatch(str)
    }

    private class CaseMatch(val str: String): C_SimpleGlobalFuncCaseMatch() {
        override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
            var rExpr = R_ConstantExpr.makeText(str)
            return rExpr
        }

        override fun compileCallDbExpr(name: S_Name, args: List<Db_Expr>): Db_Expr {
            var rExpr = R_ConstantExpr.makeText(str)
            return C_Utils.toDbExpr(name.pos, rExpr)
        }
    }
}

private class C_SysFunction_Nullable(private val baseType: R_Type?): C_SimpleGlobalFuncCase() {
    override fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val type = args[0]
        if (baseType == null && type == R_NullType) return null
        if (baseType != null && !R_NullableType(baseType).isAssignableFrom(type)) return null
        return CaseMatch()
    }

    private inner class CaseMatch: C_SimpleGlobalFuncCaseMatch() {
        override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
            check(args.size == 1)
            val arg = args[0]
            if (arg.type is R_NullableType) {
                return arg
            } else {
                val type = R_NullableType(baseType ?: arg.type)
                return R_SysCallExpr(type, R_SysFn_Nop, args)
            }
        }
    }
}

private object C_SysFunction_Require_Boolean: C_SimpleGlobalFuncCase() {
    override fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0]
        if (!R_BooleanType.isAssignableFrom(expr)) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg)) return null

        return CaseMatch()
    }

    private class CaseMatch: C_SimpleGlobalFuncCaseMatch() {
        override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Boolean(rExpr, rMsgExpr)
        }
    }
}

private object C_SysFunction_Require_Nullable: C_GlobalFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()
        if (exprType !is R_NullableType) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg.type())) return null

        return CaseMatch(exprType.valueType)
    }

    private class CaseMatch(val valueType: R_Type): C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<C_Value>): C_Value {
            val exprValue = args[0]
            val rExpr = exprValue.toRExpr()

            val rMsgExpr = if (args.size < 2) null else args[1].toRExpr()
            val rResExpr = R_RequireExpr_Nullable(valueType, rExpr, rMsgExpr)

            val preFacts = exprValue.varFacts().postFacts
            val exprVarFacts = C_ExprVarFacts.forNullCast(preFacts, exprValue)

            return C_RValue(name.pos, rResExpr, exprVarFacts)
        }
    }
}

private object C_SysFunction_Require_Collection: C_SimpleGlobalFuncCase() {
    override fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0]
        val valueType = if (expr is R_NullableType) expr.valueType else expr

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg)) return null

        if (valueType is R_CollectionType) {
            return CaseMatch_Collection(valueType)
        } else if (valueType is R_MapType) {
            return CaseMatch_Map(valueType)
        } else {
            return null
        }
    }

    private class CaseMatch_Collection(val valueType: R_Type): C_SimpleGlobalFuncCaseMatch() {
        override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Collection(valueType, rExpr, rMsgExpr)
        }
    }

    private class CaseMatch_Map(val valueType: R_Type): C_SimpleGlobalFuncCaseMatch() {
        override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Map(valueType, rExpr, rMsgExpr)
        }
    }
}

private object C_SysFunction_Exists: C_GlobalFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()
        if (exprType !is R_NullableType) return null

        return CaseMatch()
    }

    private class CaseMatch: C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<C_Value>): C_Value {
            check(args.size == 1)

            val arg = args[0]

            val preFacts = C_ExprVarFacts.forSubExpressions(args)
            val facts = preFacts.and(C_ExprVarFacts.forNullCheck(arg, false))

            val rArgs = args.map { it.toRExpr() }
            val rExpr = R_SysCallExpr(R_BooleanType, R_SysFn_Exists, rArgs)

            return C_RValue(name.pos, rExpr, facts)
        }
    }
}

private object C_SysMemberFunction_Text_Format: C_MemberFuncCase() {
    override fun match(args: List<R_Type>): C_MemberFuncCaseMatch? {
        return CaseMatch()
    }

    private class CaseMatch: C_MemberFuncCaseMatch() {
        override fun compileCall(pos: S_Pos, name: String, args: List<R_Expr>): R_MemberCalculator {
            return R_MemberCalculator_SysFn(R_TextType, R_SysFn_Text_Format, args)
        }
    }
}

private class C_SysFunction_Invalid(private val type: R_Type): C_SimpleGlobalFuncCaseMatch() {
    override fun compileCallExpr(name: S_Name, args: List<R_Expr>) = throw err(name)
    override fun compileCallDbExpr(name: S_Name, args: List<Db_Expr>) = throw err(name)

    private fun err(name: S_Name): C_Error {
        val typeStr = type.name
        val nameStr = name.str
        return C_Error(name.pos, "fn:invalid:$typeStr:$nameStr", "Function '$nameStr' not available for type '$typeStr'")
    }
}

private class C_SysMemberFunction_Invalid(private val type: R_Type): C_MemberFuncCaseMatch() {
    override fun compileCall(pos: S_Pos, name: String, args: List<R_Expr>): R_MemberCalculator {
        val typeStr = type.name
        throw C_Error(pos, "fn:invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
    }
}

private fun makeNamespace(fns: C_GlobalFuncTable, vararg values: Pair<String, C_NamespaceValue>): C_Namespace {
    val valueMap = mutableMapOf<String, C_NamespaceValue>()
    for ((name, field) in values) {
        check(name !in valueMap)
        valueMap[name] = field
    }

    val functionMap = fns.toMap()
    return C_Namespace(mapOf(), mapOf(), valueMap, functionMap)
}

private fun stdConstValue(name: String, value: Long): Pair<String, C_NamespaceValue> {
    return Pair(name, C_NamespaceValue_Value(Rt_IntValue(value)))
}

private fun stdFnValue(name: String, type: R_Type, fn: R_SysFunction): Pair<String, C_NamespaceValue> {
    return Pair(name, C_NamespaceValue_SysFunction(type, fn))
}

private fun matcher(type: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(type)
private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)
private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_MapSub(keyType, valueType)
