/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_String
import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.vexpr.V_EntityToStructExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ObjectToStructExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_DecimalValue
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.immMapOf
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
            .add("requireNotEmpty", C_SysFn_Require_Collection, depError("require_not_empty"))
            .add("requireNotEmpty", C_SysFn_Require_Nullable, depError("require_not_empty"))
            .add("require_not_empty", C_SysFn_Require_Collection)
            .add("require_not_empty", C_SysFn_Require_Nullable)
            .add("exists", C_SysFn_Exists_Collection(false))
            .add("exists", C_SysFn_Exists_Nullable(false))
            .add("empty", C_SysFn_Exists_Collection(true))
            .add("empty", C_SysFn_Exists_Nullable(true))

            .add("integer", R_IntegerType, listOf(R_TextType), R_SysFn_Integer.FromText)
            .add("integer", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Integer.FromText)
            .add("integer", R_IntegerType, listOf(R_DecimalType), R_SysFn_Decimal.ToInteger)

            .add("decimal", R_DecimalType, listOf(R_TextType), R_SysFn_Decimal.FromText, Db_SysFn_Decimal.FromText)
            .add("decimal", R_DecimalType, listOf(R_IntegerType), R_SysFn_Decimal.FromInteger, Db_SysFn_Decimal.FromInteger)

            .add("byte_array", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray.FromHex)
            .add("byte_array", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), R_SysFn_ByteArray.FromList,
                    depError("byte_array.from_list"))

            .add("range", R_RangeType, listOf(R_IntegerType), R_SysFn_General.Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType), R_SysFn_General.Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType, R_IntegerType), R_SysFn_General.Range)

            .add("print", C_SysFn_Print(false))
            .add("log", C_SysFn_Print(true))

            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), R_SysFn_Crypto.VerifySignature)
            .add("sha256", R_ByteArrayType, listOf(R_ByteArrayType), R_SysFn_ByteArray.Sha256)
            .add("keccak256", R_ByteArrayType, listOf(R_ByteArrayType), R_SysFn_Crypto.Keccak256)
            .add("eth_ecrecover", R_ByteArrayType, listOf(R_ByteArrayType, R_ByteArrayType, R_IntegerType, R_ByteArrayType), R_SysFn_Crypto.EthEcRecover)

            .add("_crash", C_SysFn_Crash)
            .add("_type_of", C_SysFn_TypeOf)
            .add("_nullable", C_SysFn_Nullable(null))
            .add("_nullable_int", C_SysFn_Nullable(R_IntegerType))
            .add("_nullable_text", C_SysFn_Nullable(R_TextType))
            .add("_nop", C_SysFn_Nop)
            .addEx("_strict_str", R_TextType, listOf(C_ArgTypeMatcher_Any), R_SysFn_Internal.StrictStr)
            .add("_int_to_rowid", R_RowidType, listOf(R_IntegerType), R_SysFn_Internal.IntToRowid)

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
            .add("hex", R_TextType, listOf(), R_SysFn_Integer.ToHex, depError("to_hex"))
            .add("to_decimal", R_DecimalType, listOf(), R_SysFn_Decimal.FromInteger, Db_SysFn_Decimal.FromInteger)
            .add("to_text", R_TextType, listOf(), R_SysFn_Integer.ToText, Db_SysFn_Int_ToText)
            .add("to_text", R_TextType, listOf(R_IntegerType), R_SysFn_Integer.ToText)
            .add("to_hex", R_TextType, listOf(), R_SysFn_Integer.ToHex)
            .add("signum", R_IntegerType, listOf(), R_SysFn_Integer.Sign, Db_SysFn_Sign, depError("sign"))
            .add("sign", R_IntegerType, listOf(), R_SysFn_Integer.Sign, Db_SysFn_Sign)
            .build()

    private val INTEGER_NAMESPACE_FNS = typeGlobalFuncBuilder(R_IntegerType)
            .add("parseHex", R_IntegerType, listOf(R_TextType), R_SysFn_Integer.FromHex, depError("from_hex"))
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
            .add("signum", R_IntegerType, listOf(), R_SysFn_Decimal.Sign, Db_SysFn_Decimal.Sign, depError("sign"))
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
            .add("fromBytes", R_GtvType, listOf(R_ByteArrayType), R_SysFn_Gtv.FromBytes, depError("from_bytes"))
            .add("from_bytes", R_GtvType, listOf(R_ByteArrayType), R_SysFn_Gtv.FromBytes)
            .add("fromJSON", R_GtvType, listOf(R_TextType), R_SysFn_Gtv.FromJson_Text, depError("from_json"))
            .add("from_json", R_GtvType, listOf(R_TextType), R_SysFn_Gtv.FromJson_Text)
            .add("fromJSON", R_GtvType, listOf(R_JsonType), R_SysFn_Gtv.FromJson_Json, depError("from_json"))
            .add("from_json", R_GtvType, listOf(R_JsonType), R_SysFn_Gtv.FromJson_Json)
            .build()

    private val GTV_NAMESPACE = makeNamespace(GTV_NAMESPACE_FNS)

    private val GTV_FNS = typeMemFuncBuilder(R_GtvType)
            .add("toBytes", R_ByteArrayType, listOf(), R_SysFn_Gtv.ToBytes, depError("to_bytes"))
            .add("to_bytes", R_ByteArrayType, listOf(), R_SysFn_Gtv.ToBytes)
            .add("toJSON", R_JsonType, listOf(), R_SysFn_Gtv.ToJson, depError("to_json"))
            .add("to_json", R_JsonType, listOf(), R_SysFn_Gtv.ToJson)
            .build()

    private val CHAIN_CONTEXT_NAMESPACE = makeNamespace(
            C_GlobalFuncTable.EMPTY,
            stdFnValue("raw_config", R_GtvType, R_SysFn_ChainContext.RawConfig),
            stdFnValue("blockchain_rid", R_ByteArrayType, R_SysFn_ChainContext.BlockchainRid),
            "args" to C_NsValue_ChainContext_Args
    )

    private val OP_CONTEXT_NAMESPACE = makeNamespace(
            C_GlobalFuncTable.EMPTY,
            "last_block_time" to C_Ns_OpContext.Value_LastBlockTime,
            "block_height" to C_Ns_OpContext.Value_BlockHeight,
            "transaction" to C_Ns_OpContext.Value_Transaction
    )

    private val TEXT_NAMESPACE_FNS = typeGlobalFuncBuilder(R_TextType)
            .add("from_bytes", R_TextType, listOf(R_ByteArrayType), R_SysFn_Text_FromBytes_1)
            .add("from_bytes", R_TextType, listOf(R_ByteArrayType, R_BooleanType), R_SysFn_Text_FromBytes)
            .build()

    private val TEXT_NAMESPACE = makeNamespace(TEXT_NAMESPACE_FNS)

    private val TEXT_FNS = typeMemFuncBuilder(R_TextType)
            .add("empty", R_BooleanType, listOf(), R_SysFn_Text_Empty, Db_SysFn_Text_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size, depError("size"))
            .add("upperCase", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase, depError("upper_case"))
            .add("upper_case", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase)
            .add("lowerCase", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase, depError("lower_case"))
            .add("lower_case", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase)
            .add("compareTo", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo, depError("compare_to"))
            .add("compare_to", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo)
            .add("contains", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Contains, Db_SysFn_Text_Contains)
            .add("startsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith, depError("starts_with"))
            .add("starts_with", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith, Db_SysFn_Text_StartsWith)
            .add("endsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith, depError("ends_with"))
            .add("ends_with", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith, Db_SysFn_Text_EndsWith)
            .add("format", C_SysFn_Text_Format)
            .add("replace", R_TextType, listOf(R_TextType, R_TextType), R_SysFn_Text_Replace, Db_SysFn_Text_Replace)
            .add("split", R_ListType(R_TextType), listOf(R_TextType), R_SysFn_Text_Split)
            .add("trim", R_TextType, listOf(), R_SysFn_Text_Trim/*, Db_SysFn_Text_Trim*/)
            .add("like", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Like, Db_SysFn_Text_Like)
            .add("matches", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Matches)
            .add("encode", R_ByteArrayType, listOf(), R_SysFn_Text_ToBytes, depError("to_bytes"))
            .add("charAt", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt, depError("char_at"))
            .add("char_at", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt, Db_SysFn_Text_CharAt)
            .add("indexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf, depError("index_of"))
            .add("index_of", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf, Db_SysFn_Text_IndexOf)
            .add("indexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf, depError("index_of"))
            .add("index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf, depError("last_index_of"))
            .add("last_index_of", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf, depError("last_index_of"))
            .add("last_index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf)
            .add("sub", R_TextType, listOf(R_IntegerType), R_SysFn_Text_Sub, Db_SysFn_Text_Sub_1)
            .add("sub", R_TextType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Text_Sub, Db_SysFn_Text_Sub_2)
            .add("to_bytes", R_ByteArrayType, listOf(), R_SysFn_Text_ToBytes)
            .build()

    private val BYTEARRAY_NAMESPACE_FNS = typeGlobalFuncBuilder(R_ByteArrayType)
            .add("from_list", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), R_SysFn_ByteArray.FromList)
            .add("from_hex", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray.FromHex)
            .add("from_base64", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray.FromBase64)
            .build()

    private val BYTEARRAY_NAMESPACE = makeNamespace(BYTEARRAY_NAMESPACE_FNS)

    private val BYTEARRAY_FNS = typeMemFuncBuilder(R_ByteArrayType)
            .add("empty", R_BooleanType, listOf(), R_SysFn_ByteArray.Empty, Db_SysFn_ByteArray_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_ByteArray.Size, Db_SysFn_ByteArray_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_ByteArray.Size, Db_SysFn_ByteArray_Size, depError("size"))
            .add("decode", R_TextType, listOf(), R_SysFn_ByteArray.Decode, depError("text.from_bytes"))
            .add("toList", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray.ToList, depError("to_list"))
            .add("to_list", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray.ToList)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType), R_SysFn_ByteArray.Sub, Db_SysFn_ByteArray_Sub_1)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType, R_IntegerType), R_SysFn_ByteArray.Sub, Db_SysFn_ByteArray_Sub_2)
            .add("to_hex", R_TextType, listOf(), R_SysFn_ByteArray.ToHex, Db_SysFn_ByteArray_ToHex)
            .add("to_base64", R_TextType, listOf(), R_SysFn_ByteArray.ToBase64, Db_SysFn_ByteArray_ToBase64)
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

    private val ENUM_PROPS = immMapOf(
            "name" to C_SysMemberFormalParamsFuncBody(R_TextType, R_SysFn_Enum.Name),
            "value" to C_SysMemberFormalParamsFuncBody(R_IntegerType, R_SysFn_Enum.Value, Db_SysFn_Nop)
    )

    private val RELL_NAMESPACE_FNS = C_GlobalFuncBuilder()
//            .add("get_rell_version", R_TextType, listOf(), R_SysFn_Rell.GetRellVersion)
//            .add("get_postchain_version", R_TextType, listOf(), R_SysFn_Rell.GetPostchainVersion)
//            .add("get_build", R_TextType, listOf(), R_SysFn_Rell.GetBuild)
//            .add("get_build_details", R_SysFn_Rell.GetBuildDetails.TYPE, listOf(), R_SysFn_Rell.GetBuildDetails)
//            .add("get_app_structure", R_GtvType, listOf(), R_SysFn_Rell.GetAppStructure)
            .build()

    private val RELL_GTX_NAMESPACE_FNS = C_GlobalFuncBuilder()
            .add("block", R_GtxBlockType, listOf(), R_SysFn_Gtx.Block.NewEmpty)
            .add("block", R_GtxBlockType, listOf(R_GtxTxType), R_SysFn_Gtx.Block.NewOneTx)
            .add("block", R_GtxBlockType, listOf(R_ListType(R_GtxTxType)), R_SysFn_Gtx.Block.NewListOfTxs)
            .add("tx", R_GtxTxType, listOf(), R_SysFn_Gtx.Tx.NewEmpty)
            .add("tx", R_GtxTxType, listOf(R_OperationType), R_SysFn_Gtx.Tx.NewOneOp)
            .add("tx", R_GtxTxType, listOf(R_ListType(R_OperationType)), R_SysFn_Gtx.Tx.NewListOfOps)
            .build()

    private val RELL_GTX_NAMESPACE = makeNamespaceEx(
            functions = RELL_GTX_NAMESPACE_FNS,
            types = mapOf(
                    "block" to R_GtxBlockType,
                    "tx" to R_GtxTxType
            )
    )

    private val RELL_NAMESPACE = makeNamespaceEx(
            functions = RELL_NAMESPACE_FNS,
            namespaces = mapOf("gtx" to RELL_GTX_NAMESPACE),
            values = mapOf("gtx" to C_NamespaceValue_Namespace(C_DefProxy.create(RELL_GTX_NAMESPACE)))
    )

    private val NAMESPACES = mapOf(
            "boolean" to nsProxy(BOOLEAN_NAMESPACE),
            "byte_array" to nsProxy(BYTEARRAY_NAMESPACE),
            "chain_context" to nsProxy(CHAIN_CONTEXT_NAMESPACE),
            "decimal" to nsProxy(DECIMAL_NAMESPACE),
            "GTXValue" to nsProxy(GTV_NAMESPACE, depError("gtv")),
            "gtv" to nsProxy(GTV_NAMESPACE),
            "integer" to nsProxy(INTEGER_NAMESPACE),
            "json" to nsProxy(JSON_NAMESPACE),
            "range" to nsProxy(RANGE_NAMESPACE),
            "rell" to nsProxy(RELL_NAMESPACE),
            "rowid" to nsProxy(ROWID_NAMESPACE),
            "text" to nsProxy(TEXT_NAMESPACE),
            C_Ns_OpContext.NAME to nsProxy(OP_CONTEXT_NAMESPACE)
    )

    private fun nsProxy(ns: C_Namespace, deprecated: C_Deprecated? = null) = C_DefProxy.create(ns, deprecated)

    fun getGlobalFunctions(): Map<String, C_GlobalFunction> = GLOBAL_FNS.toMap()
    fun getSystemNamespaces(): Map<String, C_DefProxy<C_Namespace>> = NAMESPACES

    fun getMemberFunctionOpt(type: R_Type, name: String): C_SysMemberFunction? {
        val table = getTypeMemberFunctions(type)
        val fn = table.get(name)
        return fn
    }

    fun getEnumPropertyOpt(name: String): C_SysMemberFormalParamsFuncBody? {
        return ENUM_PROPS[name]
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
            is R_ObjectType -> getObjectFns(type)
            is R_EnumType -> getEnumFns(type)
            is R_TupleType -> getTupleFns(type)
            is R_VirtualTupleType -> getVirtualTupleFns(type)
            is R_StructType -> getStructFns(type.struct)
            is R_VirtualStructType -> getVirtualStructFns(type)
            is R_OperationType -> typeMemFuncBuilder(type).build()
            is R_GtxTxType -> getGtxTxFns(type)
            is R_GtxBlockType -> getGtxBlockFns(type)
            else -> C_MemberFuncTable(mapOf())
        }
    }

    private fun getEntityFns(type: R_EntityType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("to_struct", C_SysFn_Entity_ToStruct(type))
                .build()
    }

    private fun getObjectFns(type: R_ObjectType): C_MemberFuncTable {
        return C_MemberFuncBuilder()
                .add("to_struct", C_SysFn_Object_ToStruct(type))
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
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size, depError("size"))
                .add("get", elemType, listOf(R_IntegerType), R_SysFn_List_Get)
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("indexOf", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf, depError("index_of"))
                .add("index_of", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("removeAt", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt, depError("remove_at"))
                .add("remove_at", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt)
                .add("_set", elemType, listOf(R_IntegerType, elemType), R_SysFn_List_Set)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll, depError("contains_all"))
                .addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll, depError("remove_all"))
                .addEx("remove_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .add("sub", listType, listOf(R_IntegerType), R_SysFn_List_Sub)
                .add("sub", listType, listOf(R_IntegerType, R_IntegerType), R_SysFn_List_Sub)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .add("add", R_BooleanType, listOf(R_IntegerType, elemType), R_SysFn_List_Add)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll, depError("add_all"))
                .addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("addAll", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), R_SysFn_List_AddAll, depError("add_all"))
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
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size, depError("size"))
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll, depError("contains_all"))
                .addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll, depError("add_all"))
                .addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll, depError("remove_all"))
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
                .add("len", R_IntegerType, listOf(), R_SysFn_Map_Size, depError("size"))
                .add("get", valueType, listOf(keyType), R_SysFn_Map_Get)
                .add("contains", R_BooleanType, listOf(keyType), R_SysFn_Map_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_MutableMap_Clear)
                .add("put", R_UnitType, listOf(keyType, valueType), R_SysFn_MutableMap_Put)
                .addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), R_SysFn_MutableMap_PutAll, depError("put_all"))
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

    fun makeStructNamespace(struct: R_Struct): C_Namespace {
        val type = struct.type
        val mFromBytes = globalFnFromGtv(type, R_SysFn_Struct.FromBytes(struct))
        val mFromGtv = globalFnFromGtv(type, R_SysFn_Struct.FromGtv(struct, false))
        val mFromGtvPretty = globalFnFromGtv(type, R_SysFn_Struct.FromGtv(struct, true))

        val fns = C_GlobalFuncBuilder()
                .add("fromBytes", listOf(R_ByteArrayType), mFromBytes, depError("from_bytes"))
                .add("from_bytes", listOf(R_ByteArrayType), mFromBytes)
                .add("fromGTXValue", listOf(R_GtvType), mFromGtv, depError("from_gtv"))
                .add("from_gtv", listOf(R_GtvType), mFromGtv)
                .add("fromPrettyGTXValue", listOf(R_GtvType), mFromGtvPretty, depError("from_gtv_pretty"))
                .add("from_gtv_pretty", listOf(R_GtvType), mFromGtvPretty)
                .build()

        return makeNamespace(fns)
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
                .add("toBytes", listOf(), mToBytes, depError("to_bytes"))
                .add("to_bytes", listOf(), mToBytes)
                .add("toGTXValue", listOf(), mToGtv, depError("to_gtv"))
                .add("toPrettyGTXValue", listOf(), mToGtvPretty, depError("to_gtv_pretty"))
                .build()
    }

    private fun getVirtualStructFns(type: R_VirtualStructType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("to_full", type.innerType, listOf(), R_SysFn_Virtual.ToFull)
                .build()
    }

    private fun getGtxTxFns(type: R_GtxTxType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("run", R_UnitType, listOf(), R_SysFn_Gtx.Tx.Run)
                .build()
    }

    private fun getGtxBlockFns(type: R_GtxBlockType): C_MemberFuncTable {
        return typeMemFuncBuilder(type)
                .add("run", R_UnitType, listOf(), R_SysFn_Gtx.Block.Run)
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

    private fun getEnumStaticFns(b: C_GlobalFuncBuilder, enum: R_EnumDefinition) {
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

    private val TRANSACTION_FN = "$NAME.transaction"

    fun transactionExpr(ctx: C_NamespaceValueContext, pos: S_Pos): R_Expr {
        val type = ctx.modCtx.sysDefs.transactionEntity.type
        return C_Utils.createSysCallExpr(type, R_SysFn_OpContext.Transaction(type), listOf(), pos, TRANSACTION_FN)
    }

    private fun checkCtx(ctx: C_NamespaceValueContext, name: List<S_Name>) {
        val dt = ctx.defCtx.definitionType
        if (dt != C_DefinitionType.OPERATION && dt != C_DefinitionType.FUNCTION && dt != C_DefinitionType.ENTITY) {
            throw C_Error.stop(name[0].pos, "op_ctx_noop", "Can access '$NAME' only in an operation, function or entity")
        }
    }

    object Value_LastBlockTime: C_NamespaceValue_RExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
            checkCtx(ctx, name)
            return C_Utils.createSysCallExpr(R_IntegerType, R_SysFn_OpContext.LastBlockTime, listOf(), name)
        }
    }

    object Value_BlockHeight: C_NamespaceValue_RExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
            checkCtx(ctx, name)
            return C_Utils.createSysCallExpr(R_IntegerType, R_SysFn_OpContext.BlockHeight, listOf(), name)
        }
    }

    object Value_Transaction: C_NamespaceValue_RExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
            checkCtx(ctx, name)
            return transactionExpr(ctx, name[0].pos)
        }
    }
}

private object C_NsValue_ChainContext_Args: C_NamespaceValue_RExpr() {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
        val struct = ctx.modCtx.getModuleArgsStruct()
        if (struct == null) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error.stop(name[0].pos, "expr_chainctx_args_norec",
                    "To use '$nameStr', define a struct '${C_Constants.MODULE_ARGS_STRUCT}'")
        }

        val moduleName = ctx.modCtx.moduleName
        return C_Utils.createSysCallExpr(struct.type, R_SysFn_ChainContext.Args(moduleName), listOf(), name)
    }
}

private class C_SysFn_Print(private val log: Boolean): C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch {
        // Print supports any number of arguments and any types, so not checking.
        return CaseMatch(args)
    }

    private inner class CaseMatch(args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(R_UnitType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val fullName = caseCtx.fullName
            val filePos = caseCtx.filePos()
            val rFn = R_SysFn_General.Print(log, filePos)
            return C_Utils.createSysCallExpr(R_UnitType, rFn, args, fullName)
        }
    }
}

private object C_SysFn_TypeOf: C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val type = args[0].type()
        val str = type.toStrictString()
        return CaseMatch(str)
    }

    private class CaseMatch(private val str: String): C_BasicGlobalFuncCaseMatch(R_TextType, listOf()) {
        override val canBeDb = false

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val rExpr = R_ConstantExpr.makeText(str)
            return rExpr
        }

        override fun compileCallDbExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
            val rExpr = R_ConstantExpr.makeText(str)
            return C_Utils.toDbExpr(caseCtx.fullName.pos, rExpr)
        }
    }
}

private class C_SysFn_Nullable(private val baseType: R_Type?): C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val type = args[0].type()
        if (baseType == null && type == R_NullType) return null
        if (baseType != null && !R_NullableType(baseType).isAssignableFrom(type)) return null

        val resType = if (type is R_NullableType) type else R_NullableType(baseType ?: type)
        return CaseMatch(resType, args)
    }

    private inner class CaseMatch(resType: R_Type, args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            check(args.size == 1)
            return C_Utils.createSysCallExpr(resType, R_SysFn_Internal.Nop, args, caseCtx.fullName)
        }
    }
}

private object C_SysFn_Crash: C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        return CaseMatch(args)
    }

    private class CaseMatch(args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(R_UnitType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            check(args.size == 1)
            return C_Utils.createSysCallExpr(R_UnitType, R_SysFn_Internal.Crash, args, caseCtx.fullName)
        }
    }
}

private object C_SysFn_Nop: C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val resType = args[0].type()
        return CaseMatch(resType, args)
    }

    private class CaseMatch(resType: R_Type, args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            check(args.size == 1)
            return C_Utils.createSysCallExpr(resType, R_SysFn_Internal.Nop, args, caseCtx.fullName)
        }
    }
}

private object C_SysFn_Require_Boolean: C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val exprType = args[0].type()
        if (!R_BooleanType.isAssignableFrom(exprType)) return null

        val msgType = if (args.size < 2) null else args[1].type()
        if (msgType != null && !R_TextType.isAssignableFrom(msgType)) return null

        return CaseMatch(args)
    }

    private class CaseMatch(args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(R_UnitType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr(R_UnitType, rExpr, rMsgExpr, R_RequireCondition_Boolean)
        }
    }
}

private object C_SysFn_Require_Nullable: C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()
        if (exprType !is R_NullableType) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg.type())) return null

        val preFacts = expr.varFacts().postFacts
        val varFacts = C_ExprVarFacts.forNullCast(preFacts, expr)
        return CaseMatch(args, exprType.valueType, varFacts)
    }

    private class CaseMatch(
            private val args: List<V_Expr>,
            private val valueType: R_Type,
            private val varFacts: C_ExprVarFacts
    ): C_GlobalFuncCaseMatch(valueType) {
        override fun varFacts() = varFacts

        override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val exprValue = args[0]
            val rExpr = exprValue.toRExpr()
            val rMsgExpr = if (args.size < 2) null else args[1].toRExpr()
            return R_RequireExpr(valueType, rExpr, rMsgExpr, R_RequireCondition_Nullable)
        }
    }
}

private object C_SysFn_Require_Collection: C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val msgType = if (args.size < 2) null else args[1].type()
        if (msgType != null && !R_TextType.isAssignableFrom(msgType)) return null

        val exprType = args[0].type()
        val (valueType, condition) = getCondition(exprType)
        return if (condition == null) null else CaseMatch(valueType, args, condition)
    }

    fun getCondition(exprType: R_Type): Pair<R_Type, R_RequireCondition?> {
        val resType = if (exprType is R_NullableType) exprType.valueType else exprType

        val condition = if (resType is R_CollectionType) {
            R_RequireCondition_Collection
        } else if (resType is R_MapType) {
            R_RequireCondition_Map
        } else {
            null
        }

        return Pair(resType, condition)
    }

    private class CaseMatch(
            resType: R_Type,
            args: List<V_Expr>,
            private val condition: R_RequireCondition
    ): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr(resType, rExpr, rMsgExpr, condition)
        }
    }
}

private class C_SysFn_Exists_Nullable(private val not: Boolean): C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()
        if (exprType !is R_NullableType) return null

        return CaseMatch(not, args, R_RequireCondition_Nullable)
    }

    class CaseMatch(
            private val not: Boolean,
            private val args: List<V_Expr>,
            private val condition: R_RequireCondition
    ): C_GlobalFuncCaseMatch(R_BooleanType) {
        override fun varFacts(): C_ExprVarFacts {
            check(args.size == 1)
            val arg = args[0]
            val preFacts = C_ExprVarFacts.forSubExpressions(args)
            return preFacts.and(C_ExprVarFacts.forNullCheck(arg, not))
        }

        override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            check(args.size == 1)
            val fn = R_SysFn_General.Exists(condition, not)
            val rArgs = args.map { it.toRExpr() }
            return C_Utils.createSysCallExpr(R_BooleanType, fn, rArgs, caseCtx.fullName)
        }
    }
}

private class C_SysFn_Exists_Collection(private val not: Boolean): C_GlobalSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val expr = args[0].asNullable()
        val exprType = expr.type()

        val (_, condition) = C_SysFn_Require_Collection.getCondition(exprType)
        if (condition == null) return null

        return C_SysFn_Exists_Nullable.CaseMatch(not, args, condition)
    }
}

private object C_SysFn_Text_Format: C_MemberSpecialFuncCase() {
    override fun match(args: List<V_Expr>): C_MemberFuncCaseMatch? {
        val body = C_SysMemberFormalParamsFuncBody(R_TextType, R_SysFn_Text_Format)
        return C_FormalParamsFuncCaseMatch(body, args)
    }
}

private sealed class C_SysFn_Common_ToStruct: C_SpecialSysMemberFunction() {
    protected abstract fun compile0(member: C_MemberRef): V_Expr

    final override fun compileCall(ctx: C_ExprContext, member: C_MemberRef, args: List<V_Expr>): C_Expr {
        if (args.isNotEmpty()) {
            val argTypes = args.map { it.type() }
            C_FuncMatchUtils.errNoMatch(ctx, member.pos, member.qualifiedName(), argTypes)
        }

        val vExpr = compile0(member)
        return C_VExpr(vExpr)
    }
}

private class C_SysFn_Entity_ToStruct(private val entityType: R_EntityType): C_SysFn_Common_ToStruct() {
    override fun compile0(member: C_MemberRef): V_Expr = V_EntityToStructExpr(member, entityType)
}

private class C_SysFn_Object_ToStruct(private val objectType: R_ObjectType): C_SysFn_Common_ToStruct() {
    override fun compile0(member: C_MemberRef): V_Expr = V_ObjectToStructExpr(member.pos, objectType)
}

private class C_SysFunction_Invalid(private val type: R_Type): C_GlobalFormalParamsFuncBody(R_CtErrorType) {
    override fun varFacts(caseCtx: C_GlobalFuncCaseCtx, args: List<V_Expr>) = C_ExprVarFacts.EMPTY

    override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
        throw err(caseCtx.fullName)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
        throw err(caseCtx.fullName)
    }

    private fun err(fullName: S_String): C_Error {
        val typeStr = type.name
        val nameStr = fullName.str
        return C_Error.stop(fullName.pos, "fn:invalid:$typeStr:$nameStr", "Function '$nameStr' not available for type '$typeStr'")
    }
}

private class C_SysMemberFunction_Invalid(private val type: R_Type): C_MemberFormalParamsFuncBody(R_CtErrorType) {
    override fun varFacts(caseCtx: C_MemberFuncCaseCtx, args: List<V_Expr>) = C_ExprVarFacts.EMPTY

    override fun compileCall(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<R_Expr>): R_Expr {
        val typeStr = type.name
        val member = caseCtx.member
        val name = member.qualifiedName()
        throw C_Error.stop(member.name.pos, "fn:invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
    }
}

private fun makeNamespace(functions: C_GlobalFuncTable, vararg values: Pair<String, C_NamespaceValue>): C_Namespace {
    return makeNamespaceEx(functions = functions, values = mapOf(*values))
}

private fun makeNamespaceEx(
        functions: C_GlobalFuncTable = C_GlobalFuncTable.EMPTY,
        types: Map<String, R_Type> = mapOf(),
        namespaces: Map<String, C_Namespace> = mapOf(),
        values: Map<String, C_NamespaceValue> = mapOf()
): C_Namespace {
    val valueMap = mutableMapOf<String, C_DefProxy<C_NamespaceValue>>()
    for ((name, field) in values) {
        check(name !in valueMap)
        valueMap[name] = C_DefProxy.create(field)
    }

    val namespaceMap = namespaces.mapValues { C_DefProxy.create(it.value) }
    val typeMap = types.mapValues { C_DefProxy.create(it.value) }
    val functionMap = functions.toMap().mapValues { (k, v) -> C_DefProxy.create(v) }
    return C_Namespace(namespaceMap, typeMap, valueMap, functionMap)
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
private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher =
        C_ArgTypeMatcher_MapSub(R_MapKeyValueTypes(keyType, valueType))

private fun depError(newName: String) = C_Deprecated(useInstead = newName, error = true)
