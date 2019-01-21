package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value

abstract class C_LibNamespace {
    abstract fun getValueOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): R_Expr?
    abstract fun getFunctionOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): C_GlobalFunction?
}

object C_LibFunctions {
    private val GLOBAL_FNS = C_GlobalFuncBuilder()
            .add("unit", R_UnitType, listOf(), R_SysFn_Unit)

            .add("abs", R_IntegerType, listOf(R_IntegerType), R_SysFn_Abs, Db_SysFn_Abs)
            .add("min", R_IntegerType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Min, Db_SysFn_Min)
            .add("max", R_IntegerType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Max, Db_SysFn_Max)
            .add("is_signer", R_BooleanType, listOf(R_ByteArrayType), R_SysFn_IsSigner)
            .add("json", R_JSONType, listOf(R_TextType), R_SysFn_Json, Db_SysFn_Json)

            .add("require", C_SysFunction_Require_Boolean)
            .add("require", C_SysFunction_Require_Nullable)

            .add("requireNotEmpty", C_SysFunction_Require_Collection)
            .add("requireNotEmpty", C_SysFunction_Require_Nullable)

            .add("integer", R_IntegerType, listOf(R_TextType), R_SysFn_Int_Parse)
            .add("integer", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Int_Parse)

            .add("byte_array", R_ByteArrayType, listOf(R_TextType), R_SysFn_ByteArray_New_Text)
            .add("byte_array", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), R_SysFn_ByteArray_New_List)

            .add("range", R_RangeType, listOf(R_IntegerType), R_SysFn_Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Range)
            .add("range", R_RangeType, listOf(R_IntegerType, R_IntegerType, R_IntegerType), R_SysFn_Range)

            .add("print", C_SysFunction_Print(R_SysFn_Print(false)))
            .add("log", C_SysFunction_Print(R_SysFn_Print(true)))

            .add("_typeOf", C_SysFunction_TypeOf)
            .addEx("_strictStr", R_TextType, listOf(C_ArgTypeMatcher_Any), R_SysFn_StrictStr)

            .build()

    private val INTEGER_FNS = C_MemberFuncBuilder()
            .add("str", R_TextType, listOf(), R_SysFn_Int_Str, Db_SysFn_Int_Str)
            .add("str", R_TextType, listOf(R_IntegerType), R_SysFn_Int_Str)
            .add("hex", R_TextType, listOf(), R_SysFn_Int_Hex)
            .add("signum", R_IntegerType, listOf(), R_SysFn_Int_Signum)
            .build()

    private val INTEGER_NAMESPACE_FNS = C_GlobalFuncBuilder()
            .add("parseHex", R_IntegerType, listOf(R_TextType), R_SysFn_Int_ParseHex)
            .build()

    private val INTEGER_NAMESPACE = makeNamespace(
            INTEGER_NAMESPACE_FNS,
            stdConst("MIN_VALUE", Long.MIN_VALUE),
            stdConst("MAX_VALUE", Long.MAX_VALUE)
    )

    private val GTXVALUE_NAMESPACE_FNS = C_GlobalFuncBuilder()
            .add("fromBytes", R_GtxValueType, listOf(R_ByteArrayType), R_SysFn_GtxValue_FromBytes)
            .add("fromJSON", R_GtxValueType, listOf(R_TextType), R_SysFn_GtxValue_FromJson_Text)
            .add("fromJSON", R_GtxValueType, listOf(R_JSONType), R_SysFn_GtxValue_FromJson_Json)
            .build()

    private val GTXVALUE_NAMESPACE = makeNamespace(GTXVALUE_NAMESPACE_FNS)

    private val TEXT_FNS = C_MemberFuncBuilder()
            .add("empty", R_BooleanType, listOf(), R_SysFn_Text_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_Text_Size, Db_SysFn_Text_Size)
            .add("upperCase", R_TextType, listOf(), R_SysFn_Text_UpperCase, Db_SysFn_Text_UpperCase)
            .add("lowerCase", R_TextType, listOf(), R_SysFn_Text_LowerCase, Db_SysFn_Text_LowerCase)
            .add("compareTo", R_IntegerType, listOf(R_TextType), R_SysFn_Text_CompareTo)
            .add("startsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_StartsWith)
            .add("endsWith", R_BooleanType, listOf(R_TextType), R_SysFn_Text_EndsWith)
            .add("contains", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Contains)
            .add("replace", R_TextType, listOf(R_TextType, R_TextType), R_SysFn_Text_Replace)
            .add("split", R_TextType, listOf(R_TextType), R_SysFn_Text_Split)
            .add("trim", R_TextType, listOf(), R_SysFn_Text_Trim)
            .add("matches", R_BooleanType, listOf(R_TextType), R_SysFn_Text_Matches)
            .add("encode", R_ByteArrayType, listOf(), R_SysFn_Text_Encode)
            .add("chatAt", R_IntegerType, listOf(R_IntegerType), R_SysFn_Text_CharAt)
            .add("indexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_IndexOf)
            .add("indexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_IndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType), R_SysFn_Text_LastIndexOf)
            .add("lastIndexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), R_SysFn_Text_LastIndexOf)
            .add("sub", R_TextType, listOf(R_IntegerType), R_SysFn_Text_Sub)
            .add("sub", R_TextType, listOf(R_IntegerType, R_IntegerType), R_SysFn_Text_Sub)
            .add("format", C_SysMemberFunction_Text_Format)
            .build()

    private val BYTEARRAY_FNS = C_MemberFuncBuilder()
            .add("empty", R_BooleanType, listOf(), R_SysFn_ByteArray_Empty)
            .add("size", R_IntegerType, listOf(), R_SysFn_ByteArray_Size, Db_SysFn_ByteArray_Size)
            .add("len", R_IntegerType, listOf(), R_SysFn_ByteArray_Size, Db_SysFn_ByteArray_Size)
            .add("decode", R_TextType, listOf(), R_SysFn_ByteArray_Decode)
            .add("toList", R_ListType(R_IntegerType), listOf(), R_SysFn_ByteArray_ToList)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType), R_SysFn_ByteArray_Sub)
            .add("sub", R_ByteArrayType, listOf(R_IntegerType, R_IntegerType), R_SysFn_ByteArray_Sub)
            .build()

    private val JSON_FNS = C_MemberFuncBuilder()
            .add("str", R_TextType, listOf(), R_SysFn_Json_Str, Db_SysFn_Json_Str)
            .build()

    private val GTXVALUE_FNS = C_MemberFuncBuilder()
            .add("toBytes", R_ByteArrayType, listOf(), R_SysFn_GtxValue_ToBytes)
            .add("toJSON", R_JSONType, listOf(), R_SysFn_GtxValue_ToJson)
            .build()

    private val NAMESPACES = mutableMapOf(
            "integer" to INTEGER_NAMESPACE,
            "GTXValue" to GTXVALUE_NAMESPACE,
            "op_context" to C_OpContextNamespace
    )

    fun getGlobalFunctions(): Map<String, C_GlobalFunction> = GLOBAL_FNS.toMap()

    fun getNamespace(modCtx: C_ModuleContext, name: String): C_LibNamespace? {
        val record = modCtx.getRecordOpt(name)
        if (record != null) {
            return getRecordNamespace(record)
        }
        return NAMESPACES[name]
    }

    fun getMemberFunctionOpt(type: R_Type, name: String): C_SysMemberFunction? {
        val table = getTypeMemberFunctions(type)
        val fn = table.get(name)
        return fn
    }

    private fun getTypeMemberFunctions(type: R_Type): C_MemberFuncTable {
        if (type == R_IntegerType) {
            return INTEGER_FNS
        } else if (type == R_TextType) {
            return TEXT_FNS
        } else if (type == R_ByteArrayType) {
            return BYTEARRAY_FNS
        } else if (type == R_JSONType) {
            return JSON_FNS
        } else if (type == R_GtxValueType) {
            return GTXVALUE_FNS
        } else if (type is R_ListType) {
            return getListFns(type.elementType)
        } else if (type is R_SetType) {
            return getSetFns(type.elementType)
        } else if (type is R_MapType) {
            return getMapFns(type.keyType, type.valueType)
        } else if (type is R_RecordType) {
            return getRecordFns(type)
        } else {
            return C_MemberFuncTable(mapOf())
        }
    }

    private fun getListFns(elemType: R_Type): C_MemberFuncTable {
        val listType = R_ListType(elemType)
        return C_MemberFuncBuilder()
                .add("str", R_TextType, listOf(), R_SysFn_ToString)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Collection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("calculate", elemType, listOf(R_IntegerType), R_SysFn_List_Get)
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("indexOf", R_IntegerType, listOf(elemType), R_SysFn_List_IndexOf)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("removeAt", elemType, listOf(R_IntegerType), R_SysFn_List_RemoveAt)
                .add("_set", elemType, listOf(R_IntegerType, elemType), R_SysFn_List_Set)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .add("sub", listType, listOf(R_IntegerType), R_SysFn_List_Sub)
                .add("sub", listType, listOf(R_IntegerType, R_IntegerType), R_SysFn_List_Sub)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .add("add", R_BooleanType, listOf(R_IntegerType, elemType), R_SysFn_List_Add)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("addAll", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), R_SysFn_List_AddAll)
                .build()
    }

    private fun getSetFns(elemType: R_Type): C_MemberFuncTable {
        return C_MemberFuncBuilder()
                .add("str", R_TextType, listOf(), R_SysFn_ToString)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Collection_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Collection_Size)
                .add("contains", R_BooleanType, listOf(elemType), R_SysFn_Collection_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_Collection_Clear)
                .add("remove", R_BooleanType, listOf(elemType), R_SysFn_Collection_Remove)
                .add("add", R_BooleanType, listOf(elemType), R_SysFn_Collection_Add)
                .addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_ContainsAll)
                .addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_AddAll)
                .addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), R_SysFn_Collection_RemoveAll)
                .build()
    }

    private fun getMapFns(keyType: R_Type, valueType: R_Type): C_MemberFuncTable {
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(valueType)
        return C_MemberFuncBuilder()
                .add("str", R_TextType, listOf(), R_SysFn_ToString)
                .add("empty", R_BooleanType, listOf(), R_SysFn_Map_Empty)
                .add("size", R_IntegerType, listOf(), R_SysFn_Map_Size)
                .add("len", R_IntegerType, listOf(), R_SysFn_Map_Size)
                .add("calculate", valueType, listOf(keyType), R_SysFn_Map_Get)
                .add("contains", R_BooleanType, listOf(keyType), R_SysFn_Map_Contains)
                .add("clear", R_UnitType, listOf(), R_SysFn_Map_Clear)
                .add("put", R_UnitType, listOf(keyType, valueType), R_SysFn_Map_Put)
                .addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), R_SysFn_Map_PutAll)
                .add("remove", valueType, listOf(keyType), R_SysFn_Map_Remove)
                .add("keys", keySetType, listOf(), R_SysFn_Map_Keys(keySetType))
                .add("values", valueListType, listOf(), R_SysFn_Map_Values(valueListType))
                .build()
    }

    private fun getRecordNamespace(type: R_RecordType): C_LibNamespace {
        val flags = type.completeFlags()
        val invalid = C_SysFunction_InvalidRecord(type)

        val mFromBytes = if (!flags.gtxCompact) invalid else {
            C_StdGlobalFuncCaseMatch(type, R_SysFn_Record_FromBytes(type))
        }

        val mFromGtxValue = if (!flags.gtxCompact) invalid else {
            C_StdGlobalFuncCaseMatch(type, R_SysFn_Record_FromGtx(type, false))
        }

        val mFromPrettyGtxValue = if (!flags.gtxHuman) invalid else {
            C_StdGlobalFuncCaseMatch(type, R_SysFn_Record_FromGtx(type, true))
        }

        val fns = C_GlobalFuncBuilder()
                .add("fromBytes", listOf(R_ByteArrayType), mFromBytes)
                .add("fromGTXValue", listOf(R_GtxValueType), mFromGtxValue)
                .add("fromPrettyGTXValue", listOf(R_GtxValueType), mFromPrettyGtxValue)
                .build()

        return makeNamespace(fns)
    }

    private fun getRecordFns(type: R_RecordType): C_MemberFuncTable {
        val flags = type.completeFlags()

        val invalid = C_SysMemberFunction_InvalidRecord(type)

        val mToBytes = if (!flags.gtxCompact) invalid else {
            C_StdMemberFuncCaseMatch(R_ByteArrayType, R_SysFn_Record_ToBytes(type))
        }

        val mToGtxValue = if (!flags.gtxCompact) invalid else {
            C_StdMemberFuncCaseMatch(R_GtxValueType, R_SysFn_Record_ToGtx(type, false))
        }

        val mToPrettyGtxValue = if (!flags.gtxHuman) invalid else {
            C_StdMemberFuncCaseMatch(R_GtxValueType, R_SysFn_Record_ToGtx(type, true))
        }

        return C_MemberFuncBuilder()
                .add("toBytes", listOf(), mToBytes)
                .add("toGTXValue", listOf(), mToGtxValue)
                .add("toPrettyGTXValue", listOf(), mToPrettyGtxValue)
                .build()
    }
}

private class C_StdLibNamespace(
        private val consts: Map<String, Rt_Value>,
        private val fns: C_GlobalFuncTable
): C_LibNamespace()
{
    override fun getValueOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): R_Expr? {
        val v = consts[name.str]
        return if (v == null) null else R_ConstantExpr(v)
    }

    override fun getFunctionOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name) = fns.get(name.str)
}

object C_OpContextNamespace: C_LibNamespace() {
    override fun getValueOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): R_Expr? {
        val et = entCtx.entityType
        if (et != C_EntityType.OPERATION && et != C_EntityType.FUNCTION && et != C_EntityType.CLASS) {
            throw C_Error(nsName.pos, "op_ctx_noop", "Cannot access '${nsName.str}' outside of an operation")
        }

        if (name.str == "last_block_time") {
            return R_SysCallExpr(R_IntegerType, R_SysFn_OpContext_LastBlockTime, listOf())
        } else if (name.str == "transaction") {
            return transactionExpr(entCtx)
        } else {
            return null
        }
    }

    override fun getFunctionOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name) = null

    fun transactionExpr(entCtx: C_EntityContext): R_Expr {
        val type = entCtx.modCtx.transactionClassType
        return R_SysCallExpr(type, R_SysFn_OpContext_Transaction(type), listOf())
    }
}

private class C_SysFunction_Print(val rFn: R_SysFunction): C_GlobalFuncCase() {
    override fun match(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        return CaseMatch()
    }

    private inner class CaseMatch: C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<R_Expr>): R_Expr {
            // Print supports any number of arguments and any types.
            val rExpr = R_SysCallExpr(R_UnitType, rFn, args)
            return rExpr
        }
    }
}

private object C_SysFunction_TypeOf: C_GlobalFuncCase() {
    override fun match(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val type = args[0]
        val str = type.toStrictString()
        return CaseMatch(str)
    }

    private class CaseMatch(val str: String): C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<R_Expr>): R_Expr {
            var rExpr = R_ConstantExpr.makeText(str)
            return rExpr
        }

        override fun compileCallDb(name: S_Name, args: List<Db_Expr>): Db_Expr {
            var rExpr = R_ConstantExpr.makeText(str)
            return C_Utils.toDbExpr(name.pos, rExpr)
        }
    }
}

private object C_SysFunction_Require_Boolean: C_GlobalFuncCase() {
    override fun match(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0]
        if (!R_BooleanType.isAssignableFrom(expr)) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg)) return null

        return CaseMatch()
    }

    private class CaseMatch: C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Boolean(rExpr, rMsgExpr)
        }
    }
}

private object C_SysFunction_Require_Nullable: C_GlobalFuncCase() {
    override fun match(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0]
        if (expr !is R_NullableType) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg)) return null

        return CaseMatch(expr.valueType)
    }

    private class CaseMatch(val valueType: R_Type): C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Nullable(valueType, rExpr, rMsgExpr)
        }
    }
}

private object C_SysFunction_Require_Collection: C_GlobalFuncCase() {
    override fun match(args: List<R_Type>): C_GlobalFuncCaseMatch? {
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

    private class CaseMatch_Collection(val valueType: R_Type): C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Collection(valueType, rExpr, rMsgExpr)
        }
    }

    private class CaseMatch_Map(val valueType: R_Type): C_GlobalFuncCaseMatch() {
        override fun compileCall(name: S_Name, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr_Map(valueType, rExpr, rMsgExpr)
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

private class C_SysFunction_InvalidRecord(val recordType: R_RecordType): C_GlobalFuncCaseMatch() {
    override fun compileCall(name: S_Name, args: List<R_Expr>) = throw err(name)
    override fun compileCallDb(name: S_Name, args: List<Db_Expr>) = throw err(name)

    private fun err(name: S_Name): C_Error {
        val typeStr = recordType.name
        val nameStr = name.str
        return C_Error(name.pos, "fn_record_invalid:$typeStr:$nameStr", "Function '$nameStr' not available for type '$typeStr'")
    }
}

private class C_SysMemberFunction_InvalidRecord(val recordType: R_RecordType): C_MemberFuncCaseMatch() {
    override fun compileCall(pos: S_Pos, name: String, args: List<R_Expr>): R_MemberCalculator {
        val typeStr = recordType.name
        throw C_Error(pos, "fn_record_invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
    }
}

private fun makeNamespace(fns: C_GlobalFuncTable, vararg consts: Pair<String, Rt_Value>): C_LibNamespace {
    val cMap = mutableMapOf<String, Rt_Value>()

    for ((name, value) in consts) {
        check(name !in cMap)
        cMap[name] = value
    }

    return C_StdLibNamespace(cMap, fns)
}

private fun stdConst(name: String, value: Long): Pair<String, Rt_Value> = Pair(name, Rt_IntValue(value))

private fun matcher(type: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(type)
private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)
private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_MapSub(keyType, valueType)
