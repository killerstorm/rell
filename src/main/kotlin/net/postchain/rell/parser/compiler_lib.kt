package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_DecimalValue
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value
import java.math.BigDecimal

object C_LibFunctions {
    private val GLOBAL_FNS = C_GlobalFuncBuilder()
            .add("unit", R_UnitType, listOf(), R_SysFn_General.Unit)

            .add("abs", R_IntegerType, listOf(R_IntegerType), R_SysFn_Math.Abs_Integer, Db_SysFn_Abs_Integer)
            .add("abs", R_DecimalType, listOf(R_DecimalType), R_SysFn_Math.Abs_Decimal, Db_SysFn_Abs_Decimal)
            .add("min", R_IntegerType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Math.Min_Integer, Db_SysFn_Min_Integer)
            .add("min", R_DecimalType, listOf(R_DecimalType, R_DecimalType), R_SysFn_Math.Min_Decimal, Db_SysFn_Min_Decimal)
            .add("max", R_IntegerType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Math.Max_Integer, Db_SysFn_Max_Integer)
            .add("max", R_DecimalType, listOf(R_DecimalType, R_DecimalType), R_SysFn_Math.Max_Decimal, Db_SysFn_Max_Decimal)
            .add("is_signer", R_BooleanType, listOf(R_ByteArrayType), R_SysFn_Crypto.IsSigner)
            .add("json", R_JsonType, listOf(R_TextType), R_SysFn_Json.FromText, Db_SysFn_Json)

            .add("require", C_SysFn_Require_Boolean)
            .add("require", C_SysFn_Require_Nullable)
            .add("requireNotEmpty", C_SysFn_Require_Collection, C_Deprecated_UseInstead("require_not_empty"))
            .add("requireNotEmpty", C_SysFn_Require_Nullable, C_Deprecated_UseInstead("require_not_empty"))
            .add("require_not_empty", C_SysFn_Require_Collection)
            .add("require_not_empty", C_SysFn_Require_Nullable)
            .add("exists", C_SysFn_Exists)
            .add("empty", C_SysFn_Exists, C_Deprecated_UseInstead("exists"))

            .add("integer", R_IntegerType, listOf(R_TextType), R_SysFn_Integer.FromText)
            .add("integer", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Integer.FromText)
            .add("integer", R_IntegerType, listOf(R_DecimalType), R_SysFn_Decimal.ToInteger)

            .add("decimal", R_DecimalType, listOf(R_TextType), R_SysFn_Decimal.FromText, Db_SysFn_Decimal.FromText)
            .add("decimal", R_DecimalType, listOf(R_IntegerType), R_SysFn_Decimal.FromInteger, Db_SysFn_Decimal.FromInteger)

            .add("byte_array", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray.FromHex)
            .add("byte_array", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), R_SysFn_ByteArray.FromList,
                    C_Deprecated_UseInstead("byte_array.from_list"))

            .add("range", R_RangeType, listOf(R_IntegerType), R_SysFn_General.Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType), R_SysFn_General.Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType, R_IntegerType), R_SysFn_General.Range)

            .add("print", C_SysFn_Print(false))
            .add("log", C_SysFn_Print(true))

            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), R_SysFn_Crypto.VerifySignature)

            .add("_type_of", C_SysFn_TypeOf)
            .add("_nullable", C_SysFn_Nullable(null))
            .add("_nullable_int", C_SysFn_Nullable(R_IntegerType))
            .add("_nullable_text", C_SysFn_Nullable(R_TextType))
            .add("_nop", C_SysFn_Nop)
            .addEx("_strict_str", R_TextType, listOf(C_ArgTypeMatcher_Any), R_SysFn_Internal.StrictStr)

            .build()

    private val BOOLEAN_FNS = typeMemFuncBuilder(R_BooleanType)
            .build()

    private val BOOLEAN_NAMESPACE_FNS = typeGlobalFuncBuilder(R_BooleanType)
            .build()

    private val BOOLEAN_NAMESPACE = makeNamespace(BOOLEAN_NAMESPACE_FNS)

    private val INTEGER_FNS = typeMemFuncBuilder(R_IntegerType)
            .add("abs", R_IntegerType, listOf(), R_SysFn_Math.Abs_Integer, Db_SysFn_Abs_Integer)
            .add("min", R_IntegerType, listOf(R_IntegerType), R_SysFn_Math.Min_Integer, Db_SysFn_Min_Integer)
            .add("min", R_DecimalType, listOf(R_DecimalType), R_SysFn_Integer.Min_Decimal, Db_SysFn_Min_Decimal)
            .add("max", R_IntegerType, listOf(R_IntegerType), R_SysFn_Math.Max_Integer, Db_SysFn_Max_Integer)
            .add("max", R_DecimalType, listOf(R_DecimalType), R_SysFn_Integer.Max_Decimal, Db_SysFn_Max_Decimal)
            .add("str", R_TextType, listOf(), R_SysFn_Integer.ToText, Db_SysFn_Int_ToText)
            .add("str", R_TextType, listOf(R_IntegerType), R_SysFn_Integer.ToText)
            .add("hex", R_TextType, listOf(), R_SysFn_Integer.ToHex, C_Deprecated_UseInstead("to_hex"))
            .add("to_decimal", R_DecimalType, listOf(), R_SysFn_Decimal.FromInteger, Db_SysFn_Decimal.FromInteger)
            .add("to_text", R_TextType, listOf(), R_SysFn_Integer.ToText, Db_SysFn_Int_ToText)
            .add("to_text", R_TextType, listOf(R_IntegerType), R_SysFn_Integer.ToText)
            .add("to_hex", R_TextType, listOf(), R_SysFn_Integer.ToHex)
            .add("signum", R_IntegerType, listOf(), R_SysFn_Integer.Sign, Db_SysFn_Sign, C_Deprecated_UseInstead("sign"))
            .add("sign", R_IntegerType, listOf(), R_SysFn_Integer.Sign, Db_SysFn_Sign)
            .build()

    private val INTEGER_NAMESPACE_FNS = typeGlobalFuncBuilder(R_IntegerType)
            .add("parseHex", R_IntegerType, listOf(R_TextType), R_SysFn_Integer.FromHex, C_Deprecated_UseInstead("from_hex"))
            .add("from_text", R_IntegerType, listOf(R_TextType), R_SysFn_Integer.FromText)
            .add("from_text", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Integer.FromText)
            .add("from_hex", R_IntegerType, listOf(R_TextType), R_SysFn_Integer.FromHex)
            .build()

    private val INTEGER_NAMESPACE = makeNamespace(
            INTEGER_NAMESPACE_FNS,
            stdConstValue("MIN_VALUE", Long.MIN_VALUE),
            stdConstValue("MAX_VALUE", Long.MAX_VALUE)
    )

    private val DECIMAL_FNS = typeMemFuncBuilder(R_DecimalType)
            .add("abs", R_DecimalType, listOf(), R_SysFn_Math.Abs_Decimal, Db_SysFn_Abs_Decimal)
            .add("ceil", R_DecimalType, listOf(), R_SysFn_Decimal.Ceil, Db_SysFn_Decimal.Ceil)
            .add("floor", R_DecimalType, listOf(), R_SysFn_Decimal.Floor, Db_SysFn_Decimal.Floor)
            .add("min", R_DecimalType, listOf(R_DecimalType), R_SysFn_Math.Min_Decimal, Db_SysFn_Min_Decimal)
            .add("max", R_DecimalType, listOf(R_DecimalType), R_SysFn_Math.Max_Decimal, Db_SysFn_Max_Decimal)
            .add("round", R_DecimalType, listOf(), R_SysFn_Decimal.Round, Db_SysFn_Decimal.Round)
            .add("round", R_DecimalType, listOf(R_IntegerType), R_SysFn_Decimal.Round, Db_SysFn_Decimal.Round)
            //.add("pow", R_DecimalType, listOf(R_IntegerType), R_SysFn_Decimal.Pow, Db_SysFn_Decimal.Pow)
            .add("signum", R_IntegerType, listOf(), R_SysFn_Decimal.Sign, Db_SysFn_Decimal.Sign, C_Deprecated_UseInstead("sign"))
            .add("sign", R_IntegerType, listOf(), R_SysFn_Decimal.Sign, Db_SysFn_Decimal.Sign)
            //.add("sqrt", R_DecimalType, listOf(), R_SysFn_Decimal.Sqrt, Db_SysFn_Decimal.Sqrt)
            .add("to_integer", R_IntegerType, listOf(), R_SysFn_Decimal.ToInteger, Db_SysFn_Decimal.ToInteger)
            .add("to_text", R_TextType, listOf(), R_SysFn_Decimal.ToText, Db_SysFn_Decimal.ToText)
            .add("to_text", R_TextType, listOf(R_BooleanType), R_SysFn_Decimal.ToText)
            .build()

    private val DECIMAL_NAMESPACE_FNS = typeGlobalFuncBuilder(R_DecimalType)
            .add("from_text", R_DecimalType, listOf(R_TextType), R_SysFn_Decimal.FromText)
            .build()

    private val DECIMAL_NAMESPACE = makeNamespace(
            DECIMAL_NAMESPACE_FNS,
            stdConstValue("PRECISION", (C_Constants.DECIMAL_INT_DIGITS + C_Constants.DECIMAL_FRAC_DIGITS).toLong()),
            stdConstValue("SCALE", C_Constants.DECIMAL_FRAC_DIGITS.toLong()),
            stdConstValue("INT_DIGITS", C_Constants.DECIMAL_INT_DIGITS.toLong()),
            stdConstValue("MIN_VALUE", C_Constants.DECIMAL_MIN_VALUE),
            stdConstValue("MAX_VALUE", C_Constants.DECIMAL_MAX_VALUE)
    )

    private val ROWID_FNS = typeMemFuncBuilder(R_RowidType)
            .build()

    private val ROWID_NAMESPACE_FNS = typeGlobalFuncBuilder(R_RowidType)
            .build()

    private val ROWID_NAMESPACE = makeNamespace(ROWID_NAMESPACE_FNS)

    private val GTV_NAMESPACE_FNS = C_GlobalFuncBuilder()
            .add("fromBytes", R_GtvType, listOf(R_ByteArrayType), R_SysFn_Gtv.FromBytes, C_Deprecated_UseInstead("from_bytes"))
            .add("from_bytes", R_GtvType, listOf(R_ByteArrayType), R_SysFn_Gtv.FromBytes)
            .add("fromJSON", R_GtvType, listOf(R_TextType), R_SysFn_Gtv.FromJson_Text, C_Deprecated_UseInstead("from_json"))
            .add("from_json", R_GtvType, listOf(R_TextType), R_SysFn_Gtv.FromJson_Text)
            .add("fromJSON", R_GtvType, listOf(R_JsonType), R_SysFn_Gtv.FromJson_Json, C_Deprecated_UseInstead("from_json"))
            .add("from_json", R_GtvType, listOf(R_JsonType), R_SysFn_Gtv.FromJson_Json)
            .build()

    private val GTV_NAMESPACE = makeNamespace(GTV_NAMESPACE_FNS)

    private val CHAIN_CONTEXT_NAMESPACE = makeNamespace(
            C_GlobalFuncTable.EMPTY,
            stdFnValue("raw_config", R_GtvType, R_SysFn_ChainContext.RawConfig),
            stdFnValue("blockchain_rid", R_ByteArrayType, R_SysFn_ChainContext.BlockchainRid),
            Pair("args", C_NsValue_ChainContext_Args)
    )

    private val OP_CONTEXT_NAMESPACE = makeNamespace(
            C_GlobalFuncTable.EMPTY,
            Pair("last_block_time", C_Ns_OpContext.Value_LastBlockTime),
            Pair("block_height", C_Ns_OpContext.Value_BlockHeight),
            Pair("transaction", C_Ns_OpContext.Value_Transaction)
    )

    private val TEXT_NAMESPACE_FNS = typeGlobalFuncBuilder(R_TextType)
            .add("from_bytes", R_TextType, listOf(R_ByteArrayType), R_SysFn_Text_FromBytes_1)
            .add("from_bytes", R_TextType, listOf(R_ByteArrayType, R_BooleanType), R_SysFn_Text_FromBytes)
            .build()

    private val TEXT_NAMESPACE = makeNamespace(TEXT_NAMESPACE_FNS)

    private val TEXT_FNS = typeMemFuncBuilder(R_TextType)
            .add("empty", R_BooleanType, listOf(), R_SysFn_Text_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size, C_Deprecated_UseInstead("size"))
            .add("upperCase", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase, C_Deprecated_UseInstead("upper_case"))
            .add("upper_case", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase)
            .add("lowerCase", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase, C_Deprecated_UseInstead("lower_case"))
            .add("lower_case", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase)
            .add("compareTo", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo, C_Deprecated_UseInstead("compare_to"))
            .add("compare_to", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo)
            .add("contains", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Contains)
            .add("startsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith, C_Deprecated_UseInstead("starts_with"))
            .add("starts_with", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith)
            .add("endsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith, C_Deprecated_UseInstead("ends_with"))
            .add("ends_with", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith)
            .add("format", C_SysFn_Text_Format)
            .add("replace", R_TextType, listOf(R_TextType, R_TextType), R_SysFn_Text_Replace)
            .add("split", R_ListType(R_TextType), listOf(R_TextType), R_SysFn_Text_Split)
            .add("trim", R_TextType, listOf(), R_SysFn_Text_Trim)
            .add("matches", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Matches)
            .add("encode", R_ByteArrayType, listOf(), R_SysFn_Text_ToBytes, C_Deprecated_UseInstead("to_bytes"))
            .add("charAt", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt, C_Deprecated_UseInstead("char_at"))
            .add("char_at", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt)
            .add("indexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf, C_Deprecated_UseInstead("index_of"))
            .add("index_of", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf)
            .add("indexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf, C_Deprecated_UseInstead("index_of"))
            .add("index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf, C_Deprecated_UseInstead("last_index_of"))
            .add("last_index_of", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf, C_Deprecated_UseInstead("last_index_of"))
            .add("last_index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf)
            .add("sub", R_TextType, listOf(R_IntegerType), R_SysFn_Text_Sub)
            .add("sub", R_TextType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Text_Sub)
            .add("to_bytes", R_ByteArrayType, listOf(), R_SysFn_Text_ToBytes)
            .build()

    private val BYTEARRAY_NAMESPACE_FNS = typeGlobalFuncBuilder(R_ByteArrayType)
            .add("from_list", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), R_SysFn_ByteArray.FromList)
            .add("from_hex", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray.FromHex)
            .add("from_base64", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray.FromBase64)
            .build()

    private val BYTEARRAY_NAMESPACE = makeNamespace(BYTEARRAY_NAMESPACE_FNS)

    private val BYTEARRAY_FNS = typeMemFuncBuilder(R_ByteArrayType)
            .add("empty", R_BooleanType, listOf(), R_SysFn_ByteArray.Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_ByteArray.Size, Db_SysFn_ByteArray_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_ByteArray.Size, Db_SysFn_ByteArray_Size, C_Deprecated_UseInstead("size"))
            .add("decode", R_TextType, listOf(), R_SysFn_ByteArray.Decode, C_Deprecated_UseInstead("text.from_bytes"))
            .add("toList", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray.ToList, C_Deprecated_UseInstead("to_list"))
            .add("to_list", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray.ToList)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType), R_SysFn_ByteArray.Sub)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType, R_IntegerType), R_SysFn_ByteArray.Sub)
            .add("to_hex", R_TextType, listOf(), R_SysFn_ByteArray.ToHex)
            .add("to_base64", R_TextType, listOf(), R_SysFn_ByteArray.ToBase64)
            .add("sha256", R_ByteArrayType, listOf(), R_SysFn_ByteArray.Sha256)
            .build()

    private val JSON_NAMESPACE_FNS = typeGlobalFuncBuilder(R_JsonType)
            .build()

    private val JSON_NAMESPACE = makeNamespace(JSON_NAMESPACE_FNS)

    private val JSON_FNS = typeMemFuncBuilder(R_JsonType)
            .add("str", R_TextType, listOf(), R_SysFn_Json.ToText, Db_SysFn_Json_ToText)
            .add("to_text", R_TextType, listOf(), R_SysFn_Json.ToText, Db_SysFn_Json_ToText)
            .build()

    private val RANGE_NAMESPACE_FNS = typeGlobalFuncBuilder(R_RangeType)
            .build()

    private val RANGE_NAMESPACE = makeNamespace(RANGE_NAMESPACE_FNS)

    private val GTV_FNS = typeMemFuncBuilder(R_GtvType)
            .add("toBytes", R_ByteArrayType, listOf(), R_SysFn_Gtv.ToBytes, C_Deprecated_UseInstead("to_bytes"))
            .add("to_bytes", R_ByteArrayType, listOf(), R_SysFn_Gtv.ToBytes)
            .add("toJSON", R_JsonType, listOf(), R_SysFn_Gtv.ToJson, C_Deprecated_UseInstead("to_json"))
            .add("to_json", R_JsonType, listOf(), R_SysFn_Gtv.ToJson)
            .build()

    private val NAMESPACES = mapOf(
            "boolean" to C_RegularNamespaceDef(BOOLEAN_NAMESPACE),
            "integer" to C_RegularNamespaceDef(INTEGER_NAMESPACE),
            "decimal" to C_RegularNamespaceDef(DECIMAL_NAMESPACE),
            "text" to C_RegularNamespaceDef(TEXT_NAMESPACE),
            "byte_array" to C_RegularNamespaceDef(BYTEARRAY_NAMESPACE),
            "rowid" to C_RegularNamespaceDef(ROWID_NAMESPACE),
            "json" to C_RegularNamespaceDef(JSON_NAMESPACE),
            "range" to C_RegularNamespaceDef(RANGE_NAMESPACE),
            "GTXValue" to C_RegularNamespaceDef(GTV_NAMESPACE, C_Deprecated_UseInstead("gtv")),
            "gtv" to C_RegularNamespaceDef(GTV_NAMESPACE),
            "chain_context" to C_RegularNamespaceDef(CHAIN_CONTEXT_NAMESPACE),
            C_Ns_OpContext.NAME to C_RegularNamespaceDef(OP_CONTEXT_NAMESPACE)
    )

    fun getGlobalFunctions(): Map<String, C_GlobalFunction> = GLOBAL_FNS.toMap()
    fun getSystemNamespaces(): Map<String, C_NamespaceDef> = NAMESPACES

    fun getMemberFunctionOpt(type: R_Type, name: String): C_SysMemberFunction? {
        val table = getTypeMemberFunctions(type)
        val fn = table.get(name)
        return fn
    }

    private fun getTypeMemberFunctions(type: R_Type): C_MemberFuncTable {
        return when (type) {
            R_BooleanType -> BOOLEAN_FNS
            R_IntegerType -> INTEGER_FNS
            R_DecimalType -> DECIMAL_FNS
            R_TextType -> TEXT_FNS
            R_ByteArrayType -> BYTEARRAY_FNS
            R_RowidType -> ROWID_FNS
            R_JsonType -> JSON_FNS
            R_GtvType -> GTV_FNS
            is R_ListType -> getListFns(type)
            is R_VirtualListType -> getVirtualListFns(type)
            is R_VirtualSetType -> getVirtualSetFns(type)
            is R_SetType -> getSetFns(type)
            is R_MapType -> getMapFns(type)
            is R_VirtualMapType -> getVirtualMapFns(type)
            is R_EntityType -> getEntityFns(type)
            is R_EnumType -> getEnumFns(type)
            is R_TupleType -> getTupleFns(type)
            is R_VirtualTupleType -> getVirtualTupleFns(type)
            is R_StructType -> getStructFns(type.struct)
            is R_VirtualStructType -> getVirtualStructFns(type)
            else -> C_MemberFuncTable(mapOf())
        }
    }

    private fun getEntityFns(type: R_EntityType): C_MemberFuncTable {
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

    private fun getVirtualTupleFns(type: R_VirtualTupleType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("to_full", type.innerType, listOf(), R_SysFn_Virtual.ToFull)
                .build()
    }

    private fun getListFns(listType: R_ListType): C_MemberFuncTable {
        val elemType = listType.elementType
        val comparator = elemType.comparator()
        val comparator2 = comparator ?: Comparator { _, _ -> 0 }
        return typeMemFuncBuilder(listType)
                .add("str", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("to_text", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Collection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size, C_Deprecated_UseInstead("size"))
                .add("get", elemType, listOf(R_IntegerType), R_SysFn_List_Get)
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("indexOf", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf, C_Deprecated_UseInstead("index_of"))
                .add("index_of", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("removeAt", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt, C_Deprecated_UseInstead("remove_at"))
                .add("remove_at", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt)
                .add("_set", elemType, listOf(R_IntegerType, elemType), R_SysFn_List_Set)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll,
                        C_Deprecated_UseInstead("contains_all"))
                .addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll,
                        C_Deprecated_UseInstead("remove_all"))
                .addEx("remove_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .add("sub", listType, listOf(R_IntegerType), R_SysFn_List_Sub)
                .add("sub", listType, listOf(R_IntegerType, R_IntegerType), R_SysFn_List_Sub)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .add("add", R_BooleanType, listOf(R_IntegerType, elemType), R_SysFn_List_Add)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll, C_Deprecated_UseInstead("add_all"))
                .addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("addAll", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), R_SysFn_List_AddAll,
                        C_Deprecated_UseInstead("add_all"))
                .addEx("add_all", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), R_SysFn_List_AddAll)
                .addIf(comparator != null, "_sort", R_UnitType, listOf(), R_SysFn_List_Sort(comparator2))
                .addIf(comparator != null, "sorted", listType, listOf(), R_SysFn_Collection_Sorted(listType, comparator2))
                .build()
    }

    private fun getVirtualListFns(type: R_VirtualListType): C_MemberFuncTable {
        val elemType = type.innerType.elementType
        return typeMemFuncBuilder(type)
                .add("str", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("to_text", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("empty", R_BooleanType, listOf(), R_SysFn_VirtualCollection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_VirtualCollection_Size)
                .add("get", elemType, listOf(R_IntegerType), R_SysFn_VirtualList_Get)
                .add("to_full", type.innerType, listOf(), R_SysFn_Virtual.ToFull)
                .build()
    }

    private fun getVirtualSetFns(type: R_VirtualSetType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("str", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("to_text", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("empty", R_BooleanType, listOf(), R_SysFn_VirtualCollection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_VirtualCollection_Size)
                .add("to_full", type.innerType, listOf(), R_SysFn_Virtual.ToFull)
                .build()
    }

    private fun getSetFns(setType: R_SetType): C_MemberFuncTable {
        val elemType = setType.elementType
        val listType = R_ListType(elemType)
        val comparator = elemType.comparator()
        val comparator2 = comparator ?: Comparator { _, _ -> 0 }
        return typeMemFuncBuilder(setType)
                .add("str", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("to_text", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Collection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size, C_Deprecated_UseInstead("size"))
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll,
                        C_Deprecated_UseInstead("contains_all"))
                .addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll,
                        C_Deprecated_UseInstead("add_all"))
                .addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll,
                        C_Deprecated_UseInstead("remove_all"))
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
                .add("str", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("to_text", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Map_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Map_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Map_Size, C_Deprecated_UseInstead("size"))
                .add("get", valueType, listOf(keyType), R_SysFn_Map_Get)
                .add("contains", R_BooleanType, listOf(keyType), R_SysFn_Map_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_MutableMap_Clear)
                .add("put", R_UnitType, listOf(keyType, valueType), R_SysFn_MutableMap_Put)
                .addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), R_SysFn_MutableMap_PutAll,
                        C_Deprecated_UseInstead("put_all"))
                .addEx("put_all", R_UnitType, listOf(matcherMapSub(keyType, valueType)), R_SysFn_MutableMap_PutAll)
                .add("remove", valueType, listOf(keyType), R_SysFn_MutableMap_Remove)
                .add("keys", keySetType, listOf(), R_SysFn_Map_Keys(keySetType))
                .add("values", valueListType, listOf(), R_SysFn_Map_Values(valueListType))
                .build()
    }

    private fun getVirtualMapFns(type: R_VirtualMapType): C_MemberFuncTable {
        val mapType = type.innerType
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(S_VirtualType.virtualMemberType(valueType))
        return typeMemFuncBuilder(type)
                .add("str", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("to_text", R_TextType, listOf(), R_SysFn_Any.ToText)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Map_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Map_Size)
                .add("get", valueType, listOf(keyType), R_SysFn_Map_Get)
                .add("contains", R_BooleanType, listOf(keyType), R_SysFn_Map_Contains)
                .add("keys", keySetType, listOf(), R_SysFn_Map_Keys(keySetType))
                .add("values", valueListType, listOf(), R_SysFn_Map_Values(valueListType))
                .add("to_full", mapType, listOf(), R_SysFn_Virtual.ToFull)
                .build()
    }

    fun makeStructNamespace(struct: R_Struct): C_NamespaceDef {
        val type = struct.type
        val mFromBytes = globalFnFromGtv(type, R_SysFn_Struct.FromBytes(struct))
        val mFromGtv = globalFnFromGtv(type, R_SysFn_Struct.FromGtv(struct, false))
        val mFromGtvPretty = globalFnFromGtv(type, R_SysFn_Struct.FromGtv(struct, true))

        val fns = C_GlobalFuncBuilder()
                .add("fromBytes", listOf(R_ByteArrayType), mFromBytes, C_Deprecated_UseInstead("from_bytes"))
                .add("from_bytes", listOf(R_ByteArrayType), mFromBytes)
                .add("fromGTXValue", listOf(R_GtvType), mFromGtv, C_Deprecated_UseInstead("from_gtv"))
                .add("from_gtv", listOf(R_GtvType), mFromGtv)
                .add("fromPrettyGTXValue", listOf(R_GtvType), mFromGtvPretty, C_Deprecated_UseInstead("from_gtv_pretty"))
                .add("from_gtv_pretty", listOf(R_GtvType), mFromGtvPretty)
                .build()

        val ns = makeNamespace(fns)
        return C_RegularNamespaceDef(ns)
    }

    private fun globalFnFromGtv(type: R_Type, fn: R_SysFunction): C_GlobalFormalParamsFuncBody {
        val flags = type.completeFlags()
        return if (!flags.gtv.fromGtv) C_SysFunction_Invalid(type) else C_SysGlobalFormalParamsFuncBody(type, fn)
    }

    private fun getStructFns(struct: R_Struct): C_MemberFuncTable {
        val type = struct.type
        val mToBytes = memFnToGtv(type, R_ByteArrayType, R_SysFn_Struct.ToBytes(struct))
        val mToGtv = memFnToGtv(type, R_GtvType, R_SysFn_Struct.ToGtv(struct, false))
        val mToGtvPretty = memFnToGtv(type, R_GtvType, R_SysFn_Struct.ToGtv(struct, true))

        return typeMemFuncBuilder(type)
                .add("toBytes", listOf(), mToBytes, C_Deprecated_UseInstead("to_bytes"))
                .add("to_bytes", listOf(), mToBytes)
                .add("toGTXValue", listOf(), mToGtv, C_Deprecated_UseInstead("to_gtv"))
                .add("toPrettyGTXValue", listOf(), mToGtvPretty, C_Deprecated_UseInstead("to_gtv_pretty"))
                .build()
    }

    private fun getVirtualStructFns(type: R_VirtualStructType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("to_full", type.innerType, listOf(), R_SysFn_Virtual.ToFull)
                .build()
    }

    private fun memFnToGtv(type: R_Type, resType: R_Type, fn: R_SysFunction): C_MemberFormalParamsFuncBody {
        val flags = type.completeFlags()
        return if (!flags.gtv.toGtv) C_SysMemberFunction_Invalid(type) else C_SysMemberFormalParamsFuncBody(resType, fn)
    }

    fun getTypeStaticFunction(type: R_Type, name: String): C_GlobalFunction? {
        val b = typeGlobalFuncBuilder(type)
        when (type) {
            is R_EnumType -> getEnumStaticFns(b, type.enum)
        }
        val fns = b.build()
        val fn = fns.get(name)
        return fn
    }

    private fun getEnumStaticFns(b: C_GlobalFuncBuilder, enum: R_Enum) {
        val type = enum.type
        b.add("values", R_ListType(type), listOf(), R_SysFn_Enum.Values(enum))
        b.add("value", type, listOf(R_TextType), R_SysFn_Enum.Value_Text(enum))
        b.add("value", type, listOf(R_IntegerType), R_SysFn_Enum.Value_Int(enum))
    }

    private fun typeGlobalFuncBuilder(type: R_Type): C_GlobalFuncBuilder {
        val b = C_GlobalFuncBuilder()

        b.add("from_gtv", listOf(R_GtvType), globalFnFromGtv(type, R_SysFn_Any.FromGtv(type, false, "from_gtv")))

        if (type !is R_VirtualType) {
            val name = "from_gtv_pretty"
            b.add(name, listOf(R_GtvType), globalFnFromGtv(type, R_SysFn_Any.FromGtv(type, true, name)))
        }

        return b
    }

    private fun typeMemFuncBuilder(type: R_Type): C_MemberFuncBuilder {
        val b = C_MemberFuncBuilder()

        if (type is R_VirtualType) {
            b.add("hash", R_ByteArrayType, listOf(), R_SysFn_Virtual.Hash)
        } else {
            b.add("hash", listOf(), memFnToGtv(type, R_ByteArrayType, R_SysFn_Any.Hash(type)))
        }

        b.add("to_gtv", listOf(), memFnToGtv(type, R_GtvType, R_SysFn_Any.ToGtv(type, false, "to_gtv")))
        b.add("to_gtv_pretty", listOf(), memFnToGtv(type, R_GtvType, R_SysFn_Any.ToGtv(type, true, "to_gtv_pretty")))
        return b
    }
}

object C_Ns_OpContext {
    val NAME = "op_context"

    fun transactionExpr(defCtx: C_DefinitionContext): R_Expr {
        val type = defCtx.appCtx.sysDefs.transactionEntity.type
        return R_SysCallExpr(type, R_SysFn_OpContext.Transaction(type), listOf())
    }

    private fun checkCtx(defCtx: C_DefinitionContext, name: List<S_Name>) {
        val dt = defCtx.definitionType
        if (dt != C_DefinitionType.OPERATION && dt != C_DefinitionType.FUNCTION && dt != C_DefinitionType.ENTITY) {
            throw C_Error(name[0].pos, "op_ctx_noop", "Cannot access '$NAME' outside of an operation")
        }
    }

    object Value_LastBlockTime: C_NamespaceValue_RExpr() {
        override fun get0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr {
            checkCtx(defCtx, name)
            return R_SysCallExpr(R_IntegerType, R_SysFn_OpContext.LastBlockTime, listOf())
        }
    }

    object Value_BlockHeight: C_NamespaceValue_RExpr() {
        override fun get0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr {
            checkCtx(defCtx, name)
            return R_SysCallExpr(R_IntegerType, R_SysFn_OpContext.BlockHeight, listOf())
        }
    }

    object Value_Transaction: C_NamespaceValue_RExpr() {
        override fun get0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr {
            checkCtx(defCtx, name)
            return transactionExpr(defCtx)
        }
    }
}

private object C_NsValue_ChainContext_Args: C_NamespaceValue_RExpr() {
    override fun get0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr {
        val struct = defCtx.modCtx.getModuleArgsStruct()
        if (struct == null) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "expr_chainctx_args_norec",
                    "To use '$nameStr', define a struct '${C_Constants.MODULE_ARGS_STRUCT}'")
        }

        val moduleName = defCtx.modCtx.module.name
        return R_SysCallExpr(struct.type, R_SysFn_ChainContext.Args(moduleName), listOf())
    }
}

private class C_SysFn_Print(private val log: Boolean): C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        // Print supports any number of arguments and any types, so not checking.
        return CaseMatch(args)
    }

    private inner class CaseMatch(args: List<C_Value>): C_BasicGlobalFuncCaseMatch(args) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            val pos = fullName.pos.strLine()
            val rFn = R_SysFn_General.Print(log, pos)
            val rExpr = R_SysCallExpr(R_UnitType, rFn, args)
            return rExpr
        }
    }
}

private object C_SysFn_TypeOf: C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val type = args[0].type()
        val str = type.toStrictString()
        return CaseMatch(str)
    }

    private class CaseMatch(private val str: String): C_BasicGlobalFuncCaseMatch(listOf()) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            var rExpr = R_ConstantExpr.makeText(str)
            return rExpr
        }

        override fun compileCallDbExpr(fullName: S_String, args: List<Db_Expr>): Db_Expr {
            var rExpr = R_ConstantExpr.makeText(str)
            return C_Utils.toDbExpr(fullName.pos, rExpr)
        }
    }
}

private class C_SysFn_Nullable(private val baseType: R_Type?): C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val type = args[0].type()
        if (baseType == null && type == R_NullType) return null
        if (baseType != null && !R_NullableType(baseType).isAssignableFrom(type)) return null

        val resType = if (type is R_NullableType) type else R_NullableType(baseType ?: type)
        return CaseMatch(args, resType)
    }

    private inner class CaseMatch(
            args: List<C_Value>,
            private val resType: R_Type
    ): C_BasicGlobalFuncCaseMatch(args) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            check(args.size == 1)
            return R_SysCallExpr(resType, R_SysFn_Internal.Nop, args)
        }
    }
}

private object C_SysFn_Nop: C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        return CaseMatch(args)
    }

    private class CaseMatch(args: List<C_Value>): C_BasicGlobalFuncCaseMatch(args) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            check(args.size == 1)
            val arg = args[0]
            return R_SysCallExpr(arg.type, R_SysFn_Internal.Nop, args)
        }
    }
}

private object C_SysFn_Require_Boolean: C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val exprType = args[0].type()
        if (!R_BooleanType.isAssignableFrom(exprType)) return null

        val msgType = if (args.size < 2) null else args[1].type()
        if (msgType != null && !R_TextType.isAssignableFrom(msgType)) return null

        return CaseMatch(args)
    }

    private class CaseMatch(args: List<C_Value>): C_BasicGlobalFuncCaseMatch(args) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Boolean(rExpr, rMsgExpr)
        }
    }
}

private object C_SysFn_Require_Nullable: C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()
        if (exprType !is R_NullableType) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg.type())) return null

        return CaseMatch(args, exprType.valueType)
    }

    private class CaseMatch(private val args: List<C_Value>, private val valueType: R_Type): C_GlobalFuncCaseMatch() {
        override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): C_Value {
            val exprValue = args[0]
            val rExpr = exprValue.toRExpr()

            val rMsgExpr = if (args.size < 2) null else args[1].toRExpr()
            val rResExpr = R_RequireExpr_Nullable(valueType, rExpr, rMsgExpr)

            val preFacts = exprValue.varFacts().postFacts
            val exprVarFacts = C_ExprVarFacts.forNullCast(preFacts, exprValue)

            return C_RValue(caseCtx.fullName.pos, rResExpr, exprVarFacts)
        }
    }
}

private object C_SysFn_Require_Collection: C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val exprType = args[0].type()
        val valueType = if (exprType is R_NullableType) exprType.valueType else exprType

        val msgType = if (args.size < 2) null else args[1].type()
        if (msgType != null && !R_TextType.isAssignableFrom(msgType)) return null

        if (valueType is R_CollectionType) {
            return CaseMatch_Collection(args, valueType)
        } else if (valueType is R_MapType) {
            return CaseMatch_Map(args, valueType)
        } else {
            return null
        }
    }

    private class CaseMatch_Collection(args: List<C_Value>, val valueType: R_Type): C_BasicGlobalFuncCaseMatch(args) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Collection(valueType, rExpr, rMsgExpr)
        }
    }

    private class CaseMatch_Map(args: List<C_Value>, val valueType: R_Type): C_BasicGlobalFuncCaseMatch(args) {
        override fun compileCallExpr(fullName: S_String, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Map(valueType, rExpr, rMsgExpr)
        }
    }
}

private object C_SysFn_Exists: C_GlobalSysFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()
        if (exprType !is R_NullableType) return null

        return CaseMatch(args)
    }

    private class CaseMatch(private val args: List<C_Value>): C_GlobalFuncCaseMatch() {
        override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): C_Value {
            check(args.size == 1)

            val arg = args[0]

            val preFacts = C_ExprVarFacts.forSubExpressions(args)
            val facts = preFacts.and(C_ExprVarFacts.forNullCheck(arg, false))

            val rArgs = args.map { it.toRExpr() }
            val rExpr = R_SysCallExpr(R_BooleanType, R_SysFn_General.Exists, rArgs)

            return C_RValue(caseCtx.fullName.pos, rExpr, facts)
        }
    }
}

private object C_SysFn_Text_Format: C_MemberSysFuncCase() {
    override fun match(args: List<C_Value>): C_MemberFuncCaseMatch? {
        val body = C_SysMemberFormalParamsFuncBody(R_TextType, R_SysFn_Text_Format)
        return C_FormalParamsFuncCaseMatch(body, args)
    }
}

private class C_SysFunction_Invalid(private val type: R_Type): C_GlobalFormalParamsFuncBody() {
    override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<C_Value>): C_Value {
        throw err(caseCtx.fullName)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<C_Value>): C_Value {
        throw err(caseCtx.fullName)
    }

    private fun err(fullName: S_String): C_Error {
        val typeStr = type.name
        val nameStr = fullName.str
        return C_Error(fullName.pos, "fn:invalid:$typeStr:$nameStr", "Function '$nameStr' not available for type '$typeStr'")
    }
}

private class C_SysMemberFunction_Invalid(private val type: R_Type): C_MemberFormalParamsFuncBody() {
    override fun compileCall(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<C_Value>): C_Value {
        val typeStr = type.name
        val member = caseCtx.member
        val name = member.qualifiedName()
        throw C_Error(member.name.pos, "fn:invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
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

private fun stdConstValue(name: String, value: Long) = stdConstValue(name, Rt_IntValue(value))
private fun stdConstValue(name: String, value: BigDecimal) = stdConstValue(name, Rt_DecimalValue.of(value))

private fun stdConstValue(name: String, value: Rt_Value): Pair<String, C_NamespaceValue> {
    return Pair(name, C_NamespaceValue_Value(value))
}

private fun stdFnValue(name: String, type: R_Type, fn: R_SysFunction): Pair<String, C_NamespaceValue> {
    return Pair(name, C_NamespaceValue_SysFunction(type, fn))
}

private fun matcher(type: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(type)
private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)
private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_MapSub(keyType, valueType)
