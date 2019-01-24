package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_Error(val pos: S_Pos, val code: String, val errMsg: String): Exception("$pos $errMsg")

class C_UserFunctionDeclaration(val name: String, val params: List<R_ExternalParam>, val type: R_Type)

abstract class C_GlobalFunction {
    abstract fun compileCall(name: S_Name, args: List<C_Expr>): C_Expr
}

class C_UserGlobalFunction(val fn: C_UserFunctionDeclaration, val fnKey: Int): C_GlobalFunction() {
    override fun compileCall(name: S_Name, args: List<C_Expr>): C_Expr {
        val params = fn.params.map { it.type }
        val rArgs = args.map { it.toRExpr() }
        C_FuncUtils.checkArgs(name, params, rArgs)
        val rExpr = R_UserCallExpr(fn.type, fn.name, fnKey, rArgs)
        return C_RExpr(name.pos, rExpr)
    }
}

class C_GlobalContext(val gtx: Boolean) {
    private var frameBlockIdCtr = 0L

    fun nextFrameBlockId(): R_FrameBlockId = R_FrameBlockId(frameBlockIdCtr++)
}

object C_Defs {
    val LOG_ANNOTATION = "log"
    val MODULE_ARGS_RECORD = "module_args"
}

class C_Record(val name: S_Name, val type: R_RecordType)

class C_ModuleContext(val globalCtx: C_GlobalContext) {
    private val types = mutableMapOf(
            "boolean" to R_BooleanType,
            "text" to R_TextType,
            "byte_array" to R_ByteArrayType,
            "integer" to R_IntegerType,
            "pubkey" to R_ByteArrayType,
            "name" to R_TextType,
            "timestamp" to R_IntegerType,
            "signer" to R_SignerType,
            "guid" to R_GUIDType,
            "tuid" to R_TextType,
            "json" to R_JSONType,
            "range" to R_RangeType,
            "GTXValue" to R_GtxValueType
    )

    private val classes = mutableMapOf<String, R_Class>()
    private val tables = mutableSetOf<String>()
    private val records = mutableMapOf<String, C_Record>()
    private val operations = mutableMapOf<String, R_Operation>()
    private val queries = mutableMapOf<String, R_Query>()
    private val functions = mutableMapOf<String, C_GlobalFunction>()
    private val functionDecls = mutableListOf<C_UserFunctionDeclaration>()
    private val functionDefs = mutableListOf<R_Function>()

    private val blockClass = createBlockClass()
    private val transactionClass = createTransactionClass()
    val transactionClassType = R_ClassType(transactionClass)

    val classesPass = C_ModulePass()
    val functionsPass = C_ModulePass()

    init {
        for ((name, fn) in C_LibFunctions.getGlobalFunctions()) {
            check(name !in functions)
            functions[name] = fn
        }

        addClass(blockClass)
        addClass(transactionClass)
    }

    private fun createBlockClass(): R_Class {
        val attrs = listOf(
                R_Attrib(0, "block_height", R_IntegerType, false, false),
                R_Attrib(1, "block_rid", R_ByteArrayType, false, false),
                R_Attrib(2, "timestamp", R_IntegerType, false, false)
        )
        return createSysClass("block", "blocks", "block_iid", attrs)
    }

    private fun createTransactionClass(): R_Class {
        val attrs = listOf(
                R_Attrib(0, "tx_rid", R_ByteArrayType, false, false),
                R_Attrib(1, "tx_hash", R_ByteArrayType, false, false),
                R_Attrib(2, "tx_data", R_ByteArrayType, false, false),
                R_Attrib(3, "block", R_ClassType(blockClass), false, false, true, "block_iid")
        )
        return createSysClass("transaction", "transactions", "tx_iid", attrs)
    }

    private fun createSysClass(name: String, table: String, rowid: String, attrs: List<R_Attrib>): R_Class {
        val flags = R_ClassFlags(false, false, false, false)
        val mapping = R_ClassSqlMapping(table, rowid, false)
        val cls = R_Class(name, flags, mapping)

        val attrMap = attrs.map { Pair(it.name, it) }.toMap()
        cls.setBody(R_ClassBody(listOf(), listOf(), attrMap))

        return cls
    }

    fun checkTypeName(name: S_Name) {
        checkNameConflict("record", name, records)
        checkNameConflict("type", name, types)
    }

    fun checkTableName(className: S_Name, tableName: String) {
        if (tableName in tables) {
            throw C_Error(className.pos, "name_conflict:table:$tableName",
                    "Cannot define class '${className.str}', because table '$tableName' already exists")
        }
    }

    fun checkRecordName(name: S_Name) {
        checkNameConflict("record", name, records)
        checkNameConflict("type", name, types)
        checkNameConflict("function", name, functions)
    }

    fun checkFunctionName(name: S_Name) {
        checkNameConflict("function", name, functions)
        checkNameConflict("operation", name, operations)
        checkNameConflict("query", name, queries)
        checkNameConflict("record", name, records)
    }

    fun checkOperationName(name: S_Name) {
        checkNameConflict("function", name, functions)
        checkNameConflict("operation", name, operations)
        checkNameConflict("query", name, queries)
    }

    private fun checkNameConflict(kind: String, name: S_Name, map: Map<String, Any>) {
        val nameStr = name.str
        if (nameStr in map) {
            throw C_Error(name.pos, "name_conflict:$kind:$nameStr", "Name conflict: $kind '$nameStr'")
        }
    }

    fun getType(name: S_Name): R_Type {
        val nameStr = name.str
        val type = getTypeOpt(nameStr)
        if (type == null) {
            throw C_Error(name.pos, "unknown_type:$nameStr", "Unknown type: '$nameStr'")
        }
        return type
    }

    fun getTypeOpt(name: String): R_Type? = types[name]

    fun getClass(name: S_Name): R_Class {
        val nameStr = name.str
        val cls = classes[nameStr]
        if (cls == null) {
            throw C_Error(name.pos, "unknown_class:$nameStr", "Unknown class: '$nameStr'")
        }
        return cls
    }

    fun getRecordOpt(name: String): R_RecordType? {
        return records[name]?.type
    }

    fun getFunctionOpt(name: String): C_GlobalFunction? {
        val fn = functions[name]
        return fn
    }

    fun getModuleArgsRecord(): R_RecordType? {
        return records[C_Defs.MODULE_ARGS_RECORD]?.type
    }

    fun addClass(cls: R_Class) {
        val name = cls.name
        check(name !in types)
        check(name !in classes)
        check(cls.mapping.table !in tables)
        classes[name] = cls
        types[name] = R_ClassType(cls)
        tables.add(cls.mapping.table)
    }

    fun addRecord(rec: C_Record) {
        val name = rec.type.name
        check(name !in types)
        check(name !in records)
        records[name] = rec
        types[name] = rec.type
    }

    fun addQuery(query: R_Query) {
        check(query.name !in queries)
        queries[query.name] = query
    }

    fun addOperation(operation: R_Operation) {
        check(operation.name !in operations)
        operations[operation.name] = operation
    }

    fun addFunctionDeclaration(declaration: C_UserFunctionDeclaration): Int {
        check(declaration.name !in functions)
        val fnKey = functionDecls.size
        functionDecls.add(declaration)
        functions[declaration.name] = C_UserGlobalFunction(declaration, fnKey)
        return fnKey
    }

    fun addFunctionDefinition(function: R_Function) {
        check(functionDefs.size == function.fnKey)
        functionDefs.add(function)
    }

    fun createModule(): R_Module {
        classesPass.run()
        processRecords()
        functionsPass.run()

        val moduleArgs = records[C_Defs.MODULE_ARGS_RECORD]
        if (moduleArgs != null && !moduleArgs.type.flags.typeFlags.gtxHuman) {
            throw C_Error(moduleArgs.name.pos, "module_args_nogtx", "Record '${C_Defs.MODULE_ARGS_RECORD}' is not GTX-compatible")
        }

        return R_Module(
                classes.toMap(),
                records.mapValues { it.value.type }.toMap(),
                operations.toMap(),
                queries.toMap(),
                functionDefs.toList(),
                moduleArgs?.type
        )
    }

    private fun processRecords() {
        val structure = buildRecordsStructure(records.values.map { it.type })
        val graph = structure.graph
        val transGraph = C_GraphUtils.transpose(graph)

        val cyclicRecs = C_GraphUtils.findCyclicVertices(graph).toSet()
        val infiniteRecs = C_GraphUtils.closure(transGraph, cyclicRecs).toSet()
        val mutableRecs = C_GraphUtils.closure(transGraph, structure.mutable).toSet()
        val nonGtxHumanRecs = C_GraphUtils.closure(transGraph, structure.nonGtxHuman).toSet()
        val nonGtxCompactRecs = C_GraphUtils.closure(transGraph, structure.nonGtxCompact).toSet()

        for (cRecord in records.values) {
            val record = cRecord.type
            val typeFlags = R_TypeFlags(record in mutableRecs, record !in nonGtxHumanRecs, record !in nonGtxCompactRecs)
            val flags = R_RecordFlags(typeFlags, record in cyclicRecs, record in infiniteRecs)
            record.setFlags(flags)
        }
    }
}

class C_ModulePass {
    private val jobs = mutableListOf<() -> Unit>()

    fun add(code: () -> Unit) {
        jobs.add(code)
    }

    fun run() {
        jobs.forEach { it() }
    }
}

enum class C_EntityType {
    CLASS,
    RECORD,
    QUERY,
    OPERATION,
    FUNCTION,
}

class C_EntityContext(
        val modCtx: C_ModuleContext,
        val entityType: C_EntityType,
        explicitReturnType: R_Type?
){
    private val retTypeTracker =
            if (explicitReturnType != null) RetTypeTracker.Explicit(explicitReturnType) else RetTypeTracker.Implicit()

    private var callFrameSize = 0

    val rootExprCtx = C_RExprContext(C_BlockContext(this, null, false))

    fun checkDbUpdateAllowed(pos: S_Pos) {
        if (entityType == C_EntityType.QUERY) {
            throw C_Error(pos, "no_db_update", "Database modifications are not allowed in this context")
        }
    }

    fun matchReturnType(pos: S_Pos, type: R_Type) {
        retTypeTracker.match(pos, type)
    }

    fun actualReturnType(): R_Type = retTypeTracker.getRetType()

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun makeCallFrame(): R_CallFrame {
        val rootBlock = rootExprCtx.blkCtx.makeFrameBlock()
        return R_CallFrame(callFrameSize, rootBlock)
    }

    private sealed class RetTypeTracker {
        abstract fun getRetType(): R_Type
        abstract fun match(pos: S_Pos, type: R_Type)

        class Implicit: RetTypeTracker() {
            private var impType: R_Type? = null

            override fun getRetType(): R_Type {
                val t = impType
                if (t != null) return t
                val res = R_UnitType
                impType = res
                return res
            }

            override fun match(pos: S_Pos, type: R_Type) {
                val t = impType
                if (t == null) {
                    impType = type
                } else if (t == R_UnitType) {
                    if (type != R_UnitType) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                } else {
                    val comType = R_Type.commonTypeOpt(t, type)
                    if (comType == null) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                    impType = comType
                }
            }
        }

        class Explicit(val expType: R_Type): RetTypeTracker() {
            override fun getRetType() = expType

            override fun match(pos: S_Pos, type: R_Type) {
                val m = if (expType == R_UnitType) type == R_UnitType else expType.isAssignableFrom(type)
                if (!m) {
                    throw errRetTypeMiss(pos, expType, type)
                }
            }
        }
    }

    companion object {
        private fun errRetTypeMiss(pos: S_Pos, dstType: R_Type, srcType: R_Type): C_Error =
                C_Errors.errTypeMissmatch(pos, srcType, dstType, "entity_rettype", "Return type missmatch")
    }
}

class C_ScopeEntry(val name: String, val type: R_Type, val modifiable: Boolean, val ptr: R_VarPtr) {
    fun toVarExpr(): R_VarExpr = R_VarExpr(type, ptr, name)
}

class C_ClassContext(
        private val modCtx: C_ModuleContext,
        private val className: String,
        private val entityType: C_EntityType,
        private val logAnnotation: Boolean
) {
    val entCtx = C_EntityContext(modCtx, entityType, null)

    private val attributes = mutableMapOf<String, R_Attrib>()
    private val keys = mutableListOf<R_Key>()
    private val indices = mutableListOf<R_Index>()
    private val uniqueKeys = mutableSetOf<Set<String>>()
    private val uniqueIndices = mutableSetOf<Set<String>>()

    fun hasAttribute(name: String): Boolean = name in attributes

    fun addAttribute(attr: S_NameTypePair, mutable: Boolean, expr: S_Expr?) {
        val name = attr.name

        val nameStr = name.str
        if (nameStr in attributes) {
            throw C_Error(name.pos, "dup_attr:$nameStr", "Duplicate attribute: '$nameStr'")
        }

        val rType = attr.compileType(modCtx)
        if (entityType == C_EntityType.CLASS && !rType.isSqlCompatible()) {
            throw C_Error(name.pos, "class_attr_type:$nameStr:${rType.toStrictString()}",
                    "Attribute '$nameStr' has unallowed type: ${rType.toStrictString()}")
        }

        if (mutable && logAnnotation) {
            val ann = C_Defs.LOG_ANNOTATION
            throw C_Error(name.pos, "class_attr_mutable_log:$className:$nameStr",
                    "Class '$className' cannot have mutable attributes because of the '$ann' annotation")
        }

        val exprCreator: (() -> R_Expr)? = if (expr == null) null else { ->
            val rExpr = expr.compile(entCtx.rootExprCtx).toRExpr()
            S_Type.match(rType, rExpr.type, name.pos, "attr_type:$nameStr", "Default value type missmatch for '$nameStr'")
            rExpr
        }

        addAttribute0(nameStr, rType, mutable, true, exprCreator)
    }

    fun addAttribute0(name: String, rType: R_Type, mutable: Boolean, canSetInCreate: Boolean, exprCreator: (() -> R_Expr)?) {
        check(name !in attributes)

        val rAttr = R_Attrib(attributes.size, name, rType, mutable, exprCreator != null, canSetInCreate)

        if (exprCreator == null) {
            rAttr.setExpr(null)
        } else {
            modCtx.functionsPass.add {
                val rExpr = exprCreator()
                check(rType.isAssignableFrom(rExpr.type)) {
                    val exp = rType.toStrictString()
                    val act = rExpr.type.toStrictString()
                    "Attribute '$className.$name' expression type missmatch: expected '$exp', was '$act'"
                }
                rAttr.setExpr(rExpr)
            }
        }

        attributes[name] = rAttr
    }

    fun addKey(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueKeys, names, "class_key_dup", "Duplicate key")
        keys.add(R_Key(names))
    }

    fun addIndex(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueIndices, names, "class_index_dup", "Duplicate index")
        indices.add(R_Index(names))
    }

    fun createClassBody(): R_ClassBody {
        return R_ClassBody(keys.toList(), indices.toList(), attributes.toMap())
    }

    fun createRecordBody(): Map<String, R_Attrib> {
        return attributes.toMap()
    }

    private fun addUniqueKeyIndex(pos: S_Pos, set: MutableSet<Set<String>>, names: List<String>, errCode: String, errMsg: String) {
        val nameSet = names.toSet()
        if (!set.add(nameSet)) {
            val nameLst = names.sorted()
            throw C_Error(pos, "$errCode:${nameLst.joinToString(",")}", "$errMsg: ${nameLst.joinToString()}")
        }
    }
}

private fun buildRecordsStructure(records: Collection<R_RecordType>): RecordsStructure {
    val structMap = records.map { Pair(it, calcRecStruct(it)) }.toMap()
    val graph = structMap.mapValues { (k, v) -> v.dependencies.toList() }
    val mutable = structMap.filter { (k, v) -> v.directFlags.mutable }.keys
    val nonGtxHuman = structMap.filter { (k, v) -> !v.directFlags.gtxHuman }.keys
    val nonGtxCompact = structMap.filter { (k, v) -> !v.directFlags.gtxCompact }.keys
    return RecordsStructure(mutable, nonGtxHuman, nonGtxCompact, graph)
}

private fun calcRecStruct(type: R_Type): RecStruct {
    val flags = mutableListOf(type.directFlags())
    val deps = mutableSetOf<R_RecordType>()

    for (subType in type.componentTypes()) {
        val subStruct = discoverRecStruct(subType)
        flags.add(subStruct.directFlags)
        deps.addAll(subStruct.dependencies)
    }

    val resFlags = R_TypeFlags.combine(flags)
    return RecStruct(resFlags, deps.toSet())
}

private fun discoverRecStruct(type: R_Type): RecStruct {
    if (type is R_RecordType) {
        return RecStruct(type.directFlags(), setOf(type))
    }
    return calcRecStruct(type)
}

private class RecordsStructure(
        val mutable: Set<R_RecordType>,
        val nonGtxHuman: Set<R_RecordType>,
        val nonGtxCompact: Set<R_RecordType>,
        val graph: Map<R_RecordType, List<R_RecordType>>
)

private class RecStruct(val directFlags: R_TypeFlags, val dependencies: Set<R_RecordType>)
