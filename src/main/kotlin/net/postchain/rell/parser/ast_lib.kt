package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtValue
import java.util.*

abstract class S_LibNamespace {
    abstract fun getValueOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): RExpr?
    abstract fun getFunctionOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): C_Function?

    fun getValue(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): RExpr {
        val v = getValueOpt(entCtx, nsName, name)
        return v ?: throw C_Utils.errUnknownName(nsName, name)
    }

    fun getFunction(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): C_Function {
        val fn = getFunctionOpt(entCtx, nsName, name)
        return fn ?: throw C_Utils.errUnknownFunction(nsName, name)
    }
}

object S_LibFunctions {
    private val GLOBAL_FNS = makeGlobalFns(
            stdFn("unit", RUnitType, listOf(), RSysFunction_Unit),

            stdFn("abs", RIntegerType, listOf(RIntegerType), RSysFunction_Abs, DbSysFunction_Abs),
            stdFn("min", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Min, DbSysFunction_Min),
            stdFn("max", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Max, DbSysFunction_Max),
            stdFn("is_signer", RBooleanType, listOf(RByteArrayType), RSysFunction_IsSigner),
            stdFn("json", RJSONType, listOf(RTextType), RSysFunction_Json, DbSysFunction_Json),

            overFn("require",
                    S_SysFunction_Require_Boolean,
                    S_SysFunction_Require_Nullable
            ),
            overFn("requireNotEmpty",
                    S_SysFunction_Require_Collection,
                    S_SysFunction_Require_Nullable
            ),

            overFn("integer",
                    globCase(RIntegerType, listOf(RTextType), RSysFunction_Int_Parse),
                    globCase(RIntegerType, listOf(RTextType, RIntegerType), RSysFunction_Int_Parse)
            ),
            overFn("byte_array",
                    globCase(RByteArrayType, listOf(RTextType), RSysFunction_ByteArray_New_Text),
                    globCase(RByteArrayType, listOf(RListType(RIntegerType)), RSysFunction_ByteArray_New_List)
            ),

            overFn("range",
                    globCase(RRangeType, listOf(RIntegerType), RSysFunction_Range),
                    globCase(RRangeType, listOf(RIntegerType, RIntegerType), RSysFunction_Range),
                    globCase(RRangeType, listOf(RIntegerType, RIntegerType, RIntegerType), RSysFunction_Range)
            ),

            NamespaceDef_Fn(S_SysFunction_Print("print", RSysFunction_Print(false))),
            NamespaceDef_Fn(S_SysFunction_Print("log", RSysFunction_Print(true))),

            NamespaceDef_Fn(S_SysFunction_TypeOf),
            specFn("_strictStr", RTextType, listOf(C_ArgTypeMatcher_Any), RSysFunction_StrictStr)
   )

    private val INTEGER_FNS = makeFnMap(
            overMemFn("str",
                    memCase(RTextType, listOf(), RSysFunction_Int_Str, DbSysFunction_Int_Str),
                    memCase(RTextType, listOf(RIntegerType), RSysFunction_Int_Str)
            ),
            stdMemFn("hex", RTextType, listOf(), RSysFunction_Int_Hex),
            stdMemFn("signum", RIntegerType, listOf(), RSysFunction_Int_Signum)
    )

    private val INTEGER_NAMESPACE = makeNamespace(
            stdConst("MIN_VALUE", Long.MIN_VALUE),
            stdConst("MAX_VALUE", Long.MAX_VALUE),
            stdFn("parseHex", RIntegerType, listOf(RTextType), RSysFunction_Int_ParseHex)
    )

    private val GTXVALUE_NAMESPACE = makeNamespace(
            stdFn("fromBytes", RGtxValueType, listOf(RByteArrayType), RSysFunction_GtxValue_FromBytes),
            overFn("fromJSON",
                    globCase(RGtxValueType, listOf(RTextType), RSysFunction_GtxValue_FromJson_Text),
                    globCase(RGtxValueType, listOf(RJSONType), RSysFunction_GtxValue_FromJson_Json)
            )
    )

    private val TEXT_FNS = makeFnMap(
            stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Text_Empty),
            stdMemFn("size", RIntegerType, listOf(), RSysFunction_Text_Size, DbSysFunction_Text_Size),
            stdMemFn("len", RIntegerType, listOf(), RSysFunction_Text_Size, DbSysFunction_Text_Size),
            stdMemFn("upperCase", RTextType, listOf(), RSysFunction_Text_UpperCase, DbSysFunction_Text_UpperCase),
            stdMemFn("lowerCase", RTextType, listOf(), RSysFunction_Text_LowerCase, DbSysFunction_Text_LowerCase),
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
            overMemFn("indexOf",
                    memCase(RIntegerType, listOf(RTextType), RSysFunction_Text_IndexOf),
                    memCase(RIntegerType, listOf(RTextType, RIntegerType), RSysFunction_Text_IndexOf)
            ),
            overMemFn("lastIndexOf",
                    memCase(RIntegerType, listOf(RTextType), RSysFunction_Text_LastIndexOf),
                    memCase(RIntegerType, listOf(RTextType, RIntegerType), RSysFunction_Text_LastIndexOf)
            ),
            overMemFn("sub",
                    memCase(RTextType, listOf(RIntegerType), RSysFunction_Text_Sub),
                    memCase(RTextType, listOf(RIntegerType, RIntegerType), RSysFunction_Text_Sub)
            ),
            S_SysMemberFunction_Text_Format
    )

    private val BYTEARRAY_FNS = makeFnMap(
            stdMemFn("empty", RBooleanType, listOf(), RSysFunction_ByteArray_Empty),
            stdMemFn("size", RIntegerType, listOf(), RSysFunction_ByteArray_Size, DbSysFunction_ByteArray_Size),
            stdMemFn("len", RIntegerType, listOf(), RSysFunction_ByteArray_Size, DbSysFunction_ByteArray_Size),
            stdMemFn("decode", RTextType, listOf(), RSysFunction_ByteArray_Decode),
            stdMemFn("toList", RListType(RIntegerType), listOf(), RSysFunction_ByteArray_ToList),
            overMemFn("sub",
                    memCase(RByteArrayType, listOf(RIntegerType), RSysFunction_ByteArray_Sub),
                    memCase(RByteArrayType, listOf(RIntegerType, RIntegerType), RSysFunction_ByteArray_Sub)
            )
    )

    private val JSON_FNS = makeFnMap(
            stdMemFn("str", RTextType, listOf(), RSysFunction_Json_Str, DbSysFunction_Json_Str)
    )

    private val GTXVALUE_FNS = makeFnMap(
            stdMemFn("toBytes", RByteArrayType, listOf(), RSysFunction_GtxValue_ToBytes),
            stdMemFn("toJSON", RJSONType, listOf(), RSysFunction_GtxValue_ToJson)
    )

    private val NAMESPACES = mutableMapOf(
            "integer" to INTEGER_NAMESPACE,
            "GTXValue" to GTXVALUE_NAMESPACE,
            "op_context" to S_OpContextNamespace
    )

    fun getGlobalFunctions(): List<S_SysFunction> = GLOBAL_FNS

    fun getNamespace(modCtx: C_ModuleContext, name: String): S_LibNamespace? {
        val record = modCtx.getRecordOpt(name)
        if (record != null) {
            return getRecordNamespace(record)
        }
        return NAMESPACES[name]
    }

    fun getMemberFunction(type: RType, name: S_Name): S_SysMemberFunction {
        val map = getTypeMemberFunctions(type)
        val fn = map[name.str]
        if (fn == null) {
            throw C_Utils.errUnknownMemberFunction(type, name)
        }
        return fn
    }

    private fun getTypeMemberFunctions(type: RType): Map<String, S_SysMemberFunction> {
        if (type == RIntegerType) {
            return INTEGER_FNS
        } else if (type == RTextType) {
            return TEXT_FNS
        } else if (type == RByteArrayType) {
            return BYTEARRAY_FNS
        } else if (type == RJSONType) {
            return JSON_FNS
        } else if (type == RGtxValueType) {
            return GTXVALUE_FNS
        } else if (type is RListType) {
            return getListFns(type.elementType)
        } else if (type is RSetType) {
            return getSetFns(type.elementType)
        } else if (type is RMapType) {
            return getMapFns(type.keyType, type.valueType)
        } else if (type is RRecordType) {
            return getRecordFns(type)
        } else {
            return mapOf()
        }
    }

    private fun getListFns(elemType: RType): Map<String, S_SysMemberFunction> {
        val listType = RListType(elemType)
        return makeFnMap(
                stdMemFn("str", RTextType, listOf(), RSysFunction_ToString),
                stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Collection_Empty),
                stdMemFn("size", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("len", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("calculate", elemType, listOf(RIntegerType), RSysFunction_List_Get),
                stdMemFn("contains", RBooleanType, listOf(elemType), RSysFunction_Collection_Contains),
                stdMemFn("indexOf", RIntegerType, listOf(elemType), RSysFunction_List_IndexOf),
                stdMemFn("clear", RUnitType, listOf(), RSysFunction_Collection_Clear),
                stdMemFn("remove", RBooleanType, listOf(elemType), RSysFunction_Collection_Remove),
                stdMemFn("removeAt", elemType, listOf(RIntegerType), RSysFunction_List_RemoveAt),
                stdMemFn("_set", elemType, listOf(RIntegerType, elemType), RSysFunction_List_Set),
                stdMemFnEx("containsAll", RBooleanType, listOf(matcherColSub(elemType)), RSysFunction_Collection_ContainsAll),
                stdMemFnEx("removeAll", RBooleanType, listOf(matcherColSub(elemType)), RSysFunction_Collection_RemoveAll),
                overMemFn("sub",
                        memCase(listType, listOf(RIntegerType), RSysFunction_List_Sub),
                        memCase(listType, listOf(RIntegerType, RIntegerType), RSysFunction_List_Sub)
                ),
                overMemFn("add",
                        memCase(RBooleanType, listOf(elemType), RSysFunction_Collection_Add),
                        memCase(RBooleanType, listOf(RIntegerType, elemType), RSysFunction_List_Add)
                ),
                overMemFn("addAll",
                        memCaseEx(RBooleanType, listOf(matcherColSub(elemType)), RSysFunction_Collection_AddAll),
                        memCaseEx(RBooleanType, listOf(matcher(RIntegerType), matcherColSub(elemType)), RSysFunction_List_AddAll)
                )
        )
    }

    private fun getSetFns(elemType: RType): Map<String, S_SysMemberFunction> {
        return makeFnMap(
                stdMemFn("str", RTextType, listOf(), RSysFunction_ToString),
                stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Collection_Empty),
                stdMemFn("size", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("len", RIntegerType, listOf(), RSysFunction_Collection_Size),
                stdMemFn("contains", RBooleanType, listOf(elemType), RSysFunction_Collection_Contains),
                stdMemFn("clear", RUnitType, listOf(), RSysFunction_Collection_Clear),
                stdMemFn("remove", RBooleanType, listOf(elemType), RSysFunction_Collection_Remove),
                stdMemFn("add", RBooleanType, listOf(elemType), RSysFunction_Collection_Add),
                stdMemFnEx("containsAll", RBooleanType, listOf(matcherColSub(elemType)), RSysFunction_Collection_ContainsAll),
                stdMemFnEx("addAll", RBooleanType, listOf(matcherColSub(elemType)), RSysFunction_Collection_AddAll),
                stdMemFnEx("removeAll", RBooleanType, listOf(matcherColSub(elemType)), RSysFunction_Collection_RemoveAll)
        )
    }

    private fun getMapFns(keyType: RType, valueType: RType): Map<String, S_SysMemberFunction> {
        val keySetType = RSetType(keyType)
        val valueListType = RListType(valueType)
        return makeFnMap(
                stdMemFn("str", RTextType, listOf(), RSysFunction_ToString),
                stdMemFn("empty", RBooleanType, listOf(), RSysFunction_Map_Empty),
                stdMemFn("size", RIntegerType, listOf(), RSysFunction_Map_Size),
                stdMemFn("len", RIntegerType, listOf(), RSysFunction_Map_Size),
                stdMemFn("calculate", valueType, listOf(keyType), RSysFunction_Map_Get),
                stdMemFn("contains", RBooleanType, listOf(keyType), RSysFunction_Map_Contains),
                stdMemFn("clear", RUnitType, listOf(), RSysFunction_Map_Clear),
                stdMemFn("put", RUnitType, listOf(keyType, valueType), RSysFunction_Map_Put),
                stdMemFnEx("putAll", RUnitType, listOf(matcherMapSub(keyType, valueType)), RSysFunction_Map_PutAll),
                stdMemFn("remove", valueType, listOf(keyType), RSysFunction_Map_Remove),
                stdMemFn("keys", keySetType, listOf(), RSysFunction_Map_Keys(keySetType)),
                stdMemFn("values", valueListType, listOf(), RSysFunction_Map_Values(valueListType))
        )
    }

    private fun getRecordNamespace(type: RRecordType): S_LibNamespace {
        val flags = type.completeFlags()
        val fns = mutableListOf<NamespaceDef>()

        if (flags.gtxCompact) {
            fns.add(stdFn("fromBytes", type, listOf(RByteArrayType), RSysFunction_Record_FromBytes(type)))
            fns.add(stdFn("fromGTXValue", type, listOf(RGtxValueType), RSysFunction_Record_FromGtx(type, false)))
        } else {
            fns.add(invalidRecordFn(type, "fromBytes", listOf(RByteArrayType)))
            fns.add(invalidRecordFn(type, "fromGTXValue", listOf(RGtxValueType)))
        }

        if (flags.gtxHuman) {
            fns.add(stdFn("fromPrettyGTXValue", type, listOf(RGtxValueType), RSysFunction_Record_FromGtx(type, true)))
        } else {
            fns.add(invalidRecordFn(type, "fromPrettyGTXValue", listOf(RGtxValueType)))
        }

        return makeNamespace(*fns.toTypedArray())
    }

    private fun getRecordFns(type: RRecordType): Map<String, S_SysMemberFunction> {
        val flags = type.completeFlags()
        val fns = mutableListOf<S_SysMemberFunction>()

        if (flags.gtxCompact) {
            fns.add(stdMemFn("toBytes", RByteArrayType, listOf(), RSysFunction_Record_ToBytes(type)))
            fns.add(stdMemFn("toGTXValue", RGtxValueType, listOf(), RSysFunction_Record_ToGtx(type, false)))
        } else {
            fns.add(invalidRecordMemFn(type, "toBytes", listOf()))
            fns.add(invalidRecordMemFn(type, "toGTXValue", listOf()))
        }

        if (flags.gtxHuman) {
            fns.add(stdMemFn("toPrettyGTXValue", RGtxValueType, listOf(), RSysFunction_Record_ToGtx(type, true)))
        } else {
            fns.add(invalidRecordMemFn(type, "toPrettyGTXValue", listOf()))
        }

        return makeFnMap(*fns.toTypedArray())
    }
}

private class S_StdLibNamespace(
        private val consts: Map<String, RtValue>,
        private val fns: Map<String, C_Function>
): S_LibNamespace()
{
    override fun getValueOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): RExpr? {
        val v = consts[name.str]
        return if (v == null) null else RConstantExpr(v)
    }

    override fun getFunctionOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name) = fns[name.str]
}

private object S_OpContextNamespace: S_LibNamespace() {
    override fun getValueOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name): RExpr? {
        if (entCtx.entityType != C_EntityType.OPERATION && entCtx.entityType != C_EntityType.FUNCTION) {
            throw C_Error(nsName.pos, "op_ctx_noop", "Cannot access '${nsName.str}' outside of an operation")
        }

        if (name.str == "last_block_time") {
            return RSysCallExpr(RIntegerType, RSysFunction_LastBlockTime, listOf())
        } else {
            return null
        }
    }

    override fun getFunctionOpt(entCtx: C_EntityContext, nsName: S_Name, name: S_Name) = null
}

private class S_SysFunction_Print(name: String, val rFn: RSysFunction): S_SysFunction(name) {
    override fun compileCall(pos: S_Pos, args: List<RExpr>): RExpr {
        // Print supports any number of arguments and any types.
        return RSysCallExpr(RUnitType, rFn, args)
    }

    override fun compileCallDb(pos: S_Pos, args: List<DbExpr>): DbExpr {
        throw C_Utils.errFunctionNoSql(pos, name)
    }
}

private object S_SysFunction_TypeOf: S_SysFunction("_typeOf") {
    override fun compileCall(pos: S_Pos, args: List<RExpr>): RExpr {
        val types = args.map { it.type }
        return compile0(pos, types)
    }

    override fun compileCallDb(pos: S_Pos, args: List<DbExpr>): DbExpr {
        val types = args.map { it.type }
        val rExpr = compile0(pos, types)
        return InterpretedDbExpr(rExpr)
    }

    private fun compile0(pos: S_Pos, types: List<RType>): RExpr {
        if (types.size != 1) throw C_OverloadFnUtils.errNoMatch(pos, name, types)
        val s = types[0].toStrictString()
        return RConstantExpr.makeText(s)
    }
}

private object S_SysFunction_Require_Boolean: C_CustomGlobalOverloadFnCase() {
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RExpr? {
        if (args.size < 1 || args.size > 2) return null

        val rExpr = args[0]
        if (!RBooleanType.isAssignableFrom(rExpr.type)) return null

        val rMsgExpr = if (args.size < 2) null else args[1]
        if (rMsgExpr != null && !RTextType.isAssignableFrom(rMsgExpr.type)) return null

        return RRequireExpr_Boolean(rExpr, rMsgExpr)
    }
}

private object S_SysFunction_Require_Nullable: C_CustomGlobalOverloadFnCase() {
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RExpr? {
        if (args.size < 1 || args.size > 2) return null

        val rExpr = args[0]
        if (rExpr.type !is RNullableType) return null

        val rMsgExpr = if (args.size < 2) null else args[1]
        if (rMsgExpr != null && !RTextType.isAssignableFrom(rMsgExpr.type)) return null

        val rType = rExpr.type.valueType
        return RRequireExpr_Nullable(rType, rExpr, rMsgExpr)
    }
}

private object S_SysFunction_Require_Collection: C_CustomGlobalOverloadFnCase() {
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RExpr? {
        if (args.size < 1 || args.size > 2) return null

        val rExpr = args[0]
        val rType = if (rExpr.type is RNullableType) rExpr.type.valueType else rExpr.type

        val rMsgExpr = if (args.size < 2) null else args[1]
        if (rMsgExpr != null && !RTextType.isAssignableFrom(rMsgExpr.type)) return null

        if (rType is RCollectionType) {
            return RRequireExpr_Collection(rType, rExpr, rMsgExpr)
        } else if (rType is RMapType) {
            return RRequireExpr_Map(rType, rExpr, rMsgExpr)
        } else {
            return null
        }
    }
}

private object S_SysMemberFunction_Text_Format: S_SysMemberFunction("format") {
    override fun compileCall(pos: S_Pos, baseType: RType, args: List<RExpr>): RMemberCalculator {
        return RMemberCalculator_SysFn(RTextType, RSysFunction_Text_Format, args)
    }

    override fun compileCallDb(pos: S_Pos, base: DbExpr, args: List<DbExpr>): DbExpr {
        throw C_Utils.errFunctionNoSql(pos, name)
    }
}

private class C_SysFnCase_InvalidRecord(val recordType: RRecordType, val params: List<C_ArgTypeMatcher>)
    : C_GlobalOverloadFnCase()
{
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RExpr? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        val typeStr = recordType.name
        throw C_Error(pos, "fn_record_invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
    }

    override fun compileCallDb(pos: S_Pos, name: String, args: List<DbExpr>): Optional<DbExpr>? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        return Optional.empty()
    }
}

private class C_SysMemberFnCase_InvalidRecord(val recordType: RRecordType, val params: List<C_ArgTypeMatcher>)
    : C_MemberOverloadFnCase()
{
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RMemberCalculator? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        val typeStr = recordType.name
        throw C_Error(pos, "fn_record_invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
    }

    override fun compileCallDb(pos: S_Pos, name: String, base: DbExpr, args: List<DbExpr>): Optional<DbExpr>? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        return Optional.empty()
    }
}

private fun invalidRecordFn(recordType: RRecordType, name: String, params: List<RType>): NamespaceDef_Fn =
        NamespaceDef_Fn(S_StdSysFunction(name, listOf(C_SysFnCase_InvalidRecord(recordType, params.map{ matcher(it) }))))

private fun invalidRecordMemFn(recordType: RRecordType, name: String, params: List<RType>): S_SysMemberFunction =
        S_StdSysMemberFunction(name, listOf(C_SysMemberFnCase_InvalidRecord(recordType, params.map{ matcher(it) })))

private fun makeNamespace(vararg defs: NamespaceDef): S_LibNamespace {
    val consts = mutableMapOf<String, RtValue>()
    val fns = mutableMapOf<String, C_Function>()

    for  (def in defs) {
        val name = def.name
        when (def) {
            is NamespaceDef_Fn -> {
                check(name !in fns)
                fns[name] = C_SysFunction(def.fn)
            }
            is NamespaceDef_Const -> {
                check(name !in consts)
                consts[name] = def.value
            }
        }
    }

    return S_StdLibNamespace(consts, fns)
}

private fun makeGlobalFns(vararg fns: NamespaceDef_Fn): List<S_SysFunction> {
    return fns.map { it.fn }
}

private fun makeFnMap(vararg fns: S_SysFunction): Map<String, S_SysFunction> {
    val map = mutableMapOf<String, S_SysFunction>()
    for (fn in fns) {
        check(fn.name !in map)
        map[fn.name] = fn
    }
    return map.toMap()
}

private fun makeFnMap(vararg fns: S_SysMemberFunction): Map<String, S_SysMemberFunction> {
    val map = mutableMapOf<String, S_SysMemberFunction>()
    for (fn in fns) {
        check(fn.name !in map)
        map[fn.name] = fn
    }
    return map.toMap()
}

private sealed class NamespaceDef(val name: String)
private class NamespaceDef_Fn(val fn: S_SysFunction): NamespaceDef(fn.name)
private class NamespaceDef_Const(name: String, val value: RtValue): NamespaceDef(name)

private fun stdConst(name: String, value: Long): NamespaceDef_Const = NamespaceDef_Const(name, RtIntValue(value))

private fun stdFn(name: String, type: RType, params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null)
        : NamespaceDef_Fn = NamespaceDef_Fn(S_StdSysFunction(name, listOf(globCase(type, params, rFn, dbFn))))

private fun overFn(name: String, vararg cases: C_GlobalOverloadFnCase): NamespaceDef_Fn =
        NamespaceDef_Fn(S_StdSysFunction(name, cases.toList()))

private fun specFn(name: String, type: RType, params: List<C_ArgTypeMatcher>, rFn: RSysFunction)
        : NamespaceDef_Fn = NamespaceDef_Fn(S_StdSysFunction(name, listOf(C_StdGlobalOverloadFnCase(params, type, rFn, null))))

private fun stdMemFn(name: String, type: RType, params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null)
        : S_SysMemberFunction = S_StdSysMemberFunction(name, listOf(memCase(type, params, rFn, dbFn)))

private fun stdMemFnEx(name: String, type: RType, params: List<C_ArgTypeMatcher>, rFn: RSysFunction, dbFn: DbSysFunction? = null)
        : S_SysMemberFunction = S_StdSysMemberFunction(name, listOf(memCaseEx(type, params, rFn, dbFn)))

private fun overMemFn(name: String, vararg cases: C_MemberOverloadFnCase): S_SysMemberFunction =
        S_StdSysMemberFunction(name, cases.toList())

private fun globCase(type: RType, params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null): C_GlobalOverloadFnCase =
        C_StdGlobalOverloadFnCase(params.map{ matcher(it) }, type, rFn, dbFn)

private fun memCase(type: RType, params: List<RType>, rFn: RSysFunction, dbFn: DbSysFunction? = null): C_MemberOverloadFnCase =
        C_StdMemberOverloadFnCase(params.map{ matcher(it) }, type, rFn, dbFn)

private fun memCaseEx(type: RType, params: List<C_ArgTypeMatcher>, rFn: RSysFunction, dbFn: DbSysFunction? = null): C_MemberOverloadFnCase =
        C_StdMemberOverloadFnCase(params, type, rFn, dbFn)

private fun matcher(type: RType): C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(type)
private fun matcherColSub(elementType: RType): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)
private fun matcherMapSub(keyType: RType, valueType: RType): C_ArgTypeMatcher = C_ArgTypeMatcher_MapSub(keyType, valueType)
