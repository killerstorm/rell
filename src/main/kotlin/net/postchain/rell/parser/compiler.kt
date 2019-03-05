package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_Error(val pos: S_Pos, val code: String, val errMsg: String): Exception("$pos $errMsg")

abstract class C_GlobalFunction {
    abstract fun compileCall(name: S_Name, args: List<C_Value>): C_Expr
}

class C_UserFunctionHeader(val params: List<R_ExternalParam>, val type: R_Type)

class C_UserGlobalFunction(val name: String, val fnKey: Int): C_GlobalFunction() {
    private lateinit var headerLate: C_UserFunctionHeader

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate = header
    }

    override fun compileCall(sName: S_Name, args: List<C_Value>): C_Expr {
        val header = headerLate
        val params = header.params.map { it.type }
        val rArgs = args.map { it.toRExpr() }
        C_FuncUtils.checkArgs(sName, params, rArgs)
        val rExpr = R_UserCallExpr(header.type, name, fnKey, rArgs)
        return C_RExpr(sName.pos, rExpr)
    }

    fun toRFunction(body: R_Statement, frame: R_CallFrame): R_Function {
        val header = headerLate
        return R_Function(name, header.params, body, frame, header.type, fnKey)
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

class C_Class(val name: S_Name?, val cls: R_Class)
class C_Record(val name: S_Name, val type: R_RecordType)

enum class C_ModulePass {
    DECLARATIONS,
    DEFINITIONS,
    EXPRESSIONS,
}

private class C_Table(val type: C_NameType, val name: String)

class C_ModuleContext(val globalCtx: C_GlobalContext) {
    private val sysTypes = mapOf(
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

    private val blockClass = createBlockClass()
    private val transactionClass = createTransactionClass()
    val transactionClassType = R_ClassType(transactionClass)

    private val allClasses = mutableMapOf<String, C_Class>()
    private val allObjects = mutableMapOf<String, R_Object>()
    private val allRecords = mutableMapOf<String, R_RecordType>()
    private val allOperations = mutableMapOf<String, R_Operation>()
    private val allQueries = mutableMapOf<String, R_Query>()

    private val tables = mutableMapOf<String, C_Table>()

    private var entityCount: Int = 0
    private var functionCount: Int = 0
    private val functions = mutableListOf<R_Function>()

    private val passes = C_ModulePass.values().map { Pair(it, mutableListOf<()->Unit>()) }.toMap()
    private var currentPass = C_ModulePass.DECLARATIONS

    val nsCtx = C_NamespaceContext(
            this,
            null,
            null,
            predefNamespaces = C_LibFunctions.getSystemNamespaces(),
            predefTypes = sysTypes,
            predefClasses =  listOf(blockClass, transactionClass),
            predefFunctions = C_LibFunctions.getGlobalFunctions()
    )

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
        val flags = R_ClassFlags(false, false, false, false, false)
        val mapping = R_ClassSqlMapping(table, true, rowid, false)
        val cls = R_Class(name, flags, mapping)

        val attrMap = attrs.map { Pair(it.name, it) }.toMap()
        cls.setBody(R_ClassBody(listOf(), listOf(), attrMap))

        return cls
    }

    fun getModuleArgsRecord(): R_RecordType? {
        return nsCtx.getRecordOpt(C_Defs.MODULE_ARGS_RECORD)?.type
    }

    fun nextEntityIndex(): Int = entityCount++
    fun nextFunctionKey(): Int = functionCount++

    fun addClass(def: C_Class, sName: S_Name?) {
        addTable(def.cls, C_NameType.CLASS, sName)
        addDef(def.cls.name, def, allClasses)
    }

    fun addObject(def: R_Object, sName: S_Name) {
        addTable(def.rClass, C_NameType.OBJECT, sName)
        addDef(def.rClass.name, def, allObjects)
    }

    fun addRecord(def: R_RecordType) = addDef(def.name, def, allRecords)
    fun addOperation(def: R_Operation) = addDef(def.name, def, allOperations)
    fun addQuery(def: R_Query) = addDef(def.name, def, allQueries)

    private fun addTable(rClass: R_Class, defType: C_NameType, sName: S_Name?) {
        val tableKey = rClass.mapping.tableKey()
        val oldTable = tables[tableKey]
        if (oldTable != null) {
            sName ?: throw IllegalStateException("tableKey:$defType:${rClass.name}")
            throw C_Error(sName.pos,
                    "def_duptable:$tableKey:${oldTable.type.description}:${oldTable.name}",
                    "Table '$tableKey' already used for ${oldTable.type.description} '${oldTable.name}'")
        }
        tables[tableKey] = C_Table(defType, rClass.name)
    }

    private fun <T> addDef(name: String, def: T, map: MutableMap<String, T>) {
        check(name !in map) { name }
        map[name] = def
    }

    fun addFunctionDefinition(f: R_Function) {
        check(functions.size == f.fnKey)
        functions.add(f)
    }

    fun checkPass(minPass: C_ModulePass?, maxPass: C_ModulePass?) {
        if (minPass != null) {
            check(currentPass >= minPass) { "Expected pass >= $minPass, actual $currentPass" }
        }
        if (maxPass != null) {
            check(currentPass <= maxPass) { "Expected pass <= $maxPass, actual $currentPass" }
        }
    }

    fun onPass(pass: C_ModulePass, code: () -> Unit) {
        check(currentPass < pass)
        val ordinal = currentPass.ordinal
        if (ordinal + 1 == pass.ordinal) {
            passes.getValue(pass).add(code)
        } else {
            // Extra code is needed to maintain execution order:
            // - entity 0 adds code to pass A, that code adds code to pass B
            // - entity 1 adds code to pass B directly
            // -> on pass B entity 0 must be executed before entity 1
            val nextPass = C_ModulePass.values()[ordinal + 1]
            passes.getValue(nextPass).add { onPass(pass, code) }
        }
    }

    private fun runPass(pass: C_ModulePass) {
        check(currentPass < pass)
        currentPass = pass
        for (code in passes.getValue(pass)) {
            code()
        }
    }

    fun createModule(): R_Module {
        nsCtx.createNamespace()
        runPass(C_ModulePass.DEFINITIONS)
        processRecords()
        runPass(C_ModulePass.EXPRESSIONS)

        val topologicalClasses = calcTopologicalClasses()

        val moduleArgs = nsCtx.getRecordOpt(C_Defs.MODULE_ARGS_RECORD)
        if (moduleArgs != null && !moduleArgs.type.flags.typeFlags.gtxHuman) {
            throw C_Error(moduleArgs.name.pos, "module_args_nogtx", "Record '${C_Defs.MODULE_ARGS_RECORD}' is not GTX-compatible")
        }

        return R_Module(
                allClasses.mapValues { it.value.cls }.toMap(),
                allObjects.toMap(),
                allRecords.toMap(),
                allOperations.toMap(),
                allQueries.toMap(),
                functions.toList(),
                moduleArgs?.type,
                topologicalClasses
        )
    }

    private fun processRecords() {
        val records = allRecords.values
        val structure = buildRecordsStructure(records)
        val graph = structure.graph
        val transGraph = C_GraphUtils.transpose(graph)

        val cyclicRecs = C_GraphUtils.findCyclicVertices(graph).toSet()
        val infiniteRecs = C_GraphUtils.closure(transGraph, cyclicRecs).toSet()
        val mutableRecs = C_GraphUtils.closure(transGraph, structure.mutable).toSet()
        val nonGtxHumanRecs = C_GraphUtils.closure(transGraph, structure.nonGtxHuman).toSet()
        val nonGtxCompactRecs = C_GraphUtils.closure(transGraph, structure.nonGtxCompact).toSet()

        for (record in records) {
            val typeFlags = R_TypeFlags(record in mutableRecs, record !in nonGtxHumanRecs, record !in nonGtxCompactRecs)
            val flags = R_RecordFlags(typeFlags, record in cyclicRecs, record in infiniteRecs)
            record.setFlags(flags)
        }
    }

    private fun calcTopologicalClasses(): List<R_Class> {
        val graph = mutableMapOf<R_Class, Collection<R_Class>>()
        for (cls in allClasses.values) {
            val deps = mutableSetOf<R_Class>()
            for (attr in cls.cls.attributes.values) {
                if (attr.type is R_ClassType) {
                    deps.add(attr.type.rClass)
                }
            }
            graph[cls.cls] = deps
        }

        val classToPos = allClasses.values.filter { it.name != null }.map { Pair(it.cls, it.name!!.pos) }.toMap()

        val cycles = C_GraphUtils.findCycles(graph)
        if (!cycles.isEmpty()) {
            val cycle = cycles[0]
            val shortStr = cycle.joinToString(",") { it.name }
            val str = cycle.joinToString { it.name }
            val cls = cycle[0]
            val pos = classToPos[cls]
            check(pos != null) { cls.name }
            throw C_Error(pos!!, "class_cycle:$shortStr", "Class cycle, not allowed: $str")
        }

        val res = C_GraphUtils.topologicalSort(graph)
        return res
    }
}

enum class C_NameType(val description: String) {
    NAMESPACE("namespace"),
    TYPE("type"),
    CLASS("class"),
    RECORD("record"),
    ENUM("enum"),
    OBJECT("object"),
    FUNCTION("function"),
    OPERATION("operation"),
    QUERY("query")
}

class C_NamespaceContext(
        val modCtx: C_ModuleContext,
        private val parentCtx: C_NamespaceContext?,
        private val namespaceName: String?,
        predefNamespaces: Map<String, C_Namespace> = mapOf(),
        predefTypes: Map<String, R_Type> = mapOf(),
        predefClasses: List<R_Class> = listOf(),
        predefFunctions: Map<String, C_GlobalFunction> = mapOf()
){
    private val names = mutableMapOf<String, C_NameType>()
    private val nsBuilder = C_NamespaceBuilder()

    private val records = mutableMapOf<String, C_Record>()

    private lateinit var namespace: C_Namespace

    init {
        val predefValues = predefNamespaces.mapValues { C_NamespaceValue_Namespace(it.value) }

        addPredefs(predefTypes, C_NameType.TYPE, nsBuilder::addType)
        addPredefs(predefNamespaces, C_NameType.NAMESPACE, nsBuilder::addNamespace)
        addPredefs(predefValues, C_NameType.NAMESPACE, nsBuilder::addValue)
        addPredefs(predefFunctions, C_NameType.FUNCTION, nsBuilder::addFunction)

        for (cls in predefClasses) {
            // Assuming only top-level namespace can have predefined classes, so name == fullName
            check(parentCtx == null)
            val name = cls.name
            check(name !in names)
            addClass0(null, name, cls)
            names[name] = C_NameType.CLASS
        }
    }

    private fun <T> addPredefs(predefs: Map<String, T>, type: C_NameType, adder: (String, T) -> Unit) {
        for ((name, def) in predefs) {
            adder(name, def)
            // For predefined names, name duplication is allowed (e.g. "integer" is a type, function and namespace)
            if (name !in names) names[name] = type
        }
    }

    fun fullName(name: String): String {
        return if (namespaceName == null) name else (namespaceName + "." + name)
    }

    fun getType(name: List<S_Name>): R_Type {
        val type = getTypeOpt(name)
        if (type == null) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "unknown_type:$nameStr", "Unknown type: '$nameStr'")
        }
        return type
    }

    fun getTypeOpt(name: List<S_Name>): R_Type? {
        check(!name.isEmpty())

        val name0 = name[0].str
        if (name.size == 1) {
            val type = getDefOpt { it.namespace.types[name0] }
            return type
        }

        var ns = getDefOpt { it.namespace.namespaces[name0] }
        if (ns == null) return null

        var namesTail = name.subList(1, name.size)
        while (namesTail.size > 1) {
            ns = ns!!.namespaces[namesTail[0].str]
            if (ns == null) return null
            namesTail = namesTail.subList(1, namesTail.size)
        }

        val type = ns!!.types[namesTail[0].str]
        return type
    }

    fun getClass(name: List<S_Name>): R_Class {
        val type = getTypeOpt(name)
        if (type !is R_ClassType) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "unknown_class:$nameStr", "Unknown class: '$nameStr'")
        }
        return type.rClass
    }

    fun getRecordOpt(name: String): C_Record? {
        val r = getDefOpt { it.records[name] }
        return r
    }

    fun getValueOpt(name: String): C_NamespaceValue? {
        val e = getDefOpt { it.namespace.values[name] }
        return e
    }

    fun getFunctionOpt(name: String): C_GlobalFunction? {
        modCtx.checkPass(C_ModulePass.EXPRESSIONS, null)
        val fn = getDefOpt { it.namespace.functions[name] }
        return fn
    }

    private fun <T> getDefOpt(getter: (C_NamespaceContext) -> T?): T? {
        modCtx.checkPass(C_ModulePass.DEFINITIONS, null)
        var ctx: C_NamespaceContext? = this
        while (ctx != null) {
            val def = getter(ctx)
            if (def != null) return def
            ctx = ctx.parentCtx
        }
        return null
    }

    fun addNamespace(name: String, ns: C_Namespace) {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        nsBuilder.addNamespace(name, ns)
        nsBuilder.addValue(name, C_NamespaceValue_Namespace(ns))
    }

    fun addClass(name: S_Name, cls: R_Class) {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        addClass0(name, name.str, cls)
    }

    private fun addClass0(sName: S_Name?, name: String, cls: R_Class) {
        val cCls = C_Class(sName, cls)
        modCtx.addClass(cCls, sName)
        nsBuilder.addType(name, R_ClassType(cls))
    }

    fun addObject(sName: S_Name, obj: R_Object) {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        modCtx.addObject(obj, sName)
        nsBuilder.addValue(sName.str, C_NamespaceValue_Object(obj))
    }

    fun addRecord(rec: C_Record) {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        val name = rec.name.str
        check(name !in records)
        modCtx.addRecord(rec.type)
        nsBuilder.addType(name, rec.type)
        nsBuilder.addValue(name, C_NamespaceValue_Record(rec.type))
        records[name] = rec
    }

    fun addEnum(name: String, e: R_EnumType) {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        nsBuilder.addType(name, e)
        nsBuilder.addValue(name, C_NamespaceValue_Enum(e))
    }

    fun addOperation(operation: R_Operation) {
        modCtx.checkPass(null, C_ModulePass.EXPRESSIONS)
        modCtx.addOperation(operation)
    }

    fun addQuery(query: R_Query) {
        modCtx.checkPass(null, C_ModulePass.EXPRESSIONS)
        modCtx.addQuery(query)
    }

    fun addFunctionDeclaration(name: String): C_UserGlobalFunction {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        val fnKey = modCtx.nextFunctionKey()
        val fullName = fullName(name)
        val fn = C_UserGlobalFunction(fullName, fnKey)
        nsBuilder.addFunction(name, fn)
        return fn
    }

    fun addFunctionBody(function: R_Function) {
        modCtx.checkPass(null, C_ModulePass.EXPRESSIONS)
        modCtx.addFunctionDefinition(function)
    }

    fun registerName(name: S_Name, type: C_NameType): String {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        val oldType = names[name.str]
        if (oldType != null) {
            throw C_Error(name.pos, "name_conflict:${oldType.description}:${name.str}",
                    "Name conflict: ${oldType.description} '$name.str' exists")
        }
        names[name.str] = type
        return fullName(name.str)
    }

    fun checkTopNamespace(pos: S_Pos, type: C_NameType) {
        if (parentCtx != null) {
            val s = type.description
            throw C_Error(pos, "def_ns:$s", "Not allowed to declare $s in a namespace")
        }
    }

    fun createNamespace(): C_Namespace {
        modCtx.checkPass(null, C_ModulePass.DECLARATIONS)
        namespace = nsBuilder.build()
        return namespace
    }
}

enum class C_EntityType {
    CLASS,
    OBJECT,
    RECORD,
    QUERY,
    OPERATION,
    FUNCTION,
}

class C_EntityContext(
        val nsCtx: C_NamespaceContext,
        val entityType: C_EntityType,
        val entityIndex: Int,
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
        val entCtx: C_EntityContext,
        private val className: String,
        private val logAnnotation: Boolean
) {
    private val attributes = mutableMapOf<String, R_Attrib>()
    private val keys = mutableListOf<R_Key>()
    private val indices = mutableListOf<R_Index>()
    private val uniqueKeys = mutableSetOf<Set<String>>()
    private val uniqueIndices = mutableSetOf<Set<String>>()

    fun hasAttribute(name: String): Boolean = name in attributes

    fun addAttribute(attr: S_NameTypePair, mutable: Boolean, expr: S_Expr?) {
        val name = attr.name
        val entityType = entCtx.entityType

        val nameStr = name.str
        if (nameStr in attributes) {
            throw C_Error(name.pos, "dup_attr:$nameStr", "Duplicate attribute: '$nameStr'")
        }

        val rType = attr.compileType(entCtx.nsCtx)
        if ((entityType == C_EntityType.CLASS || entityType == C_EntityType.OBJECT) && !rType.isSqlCompatible()) {
            throw C_Error(name.pos, "class_attr_type:$nameStr:${rType.toStrictString()}",
                    "Attribute '$nameStr' has unallowed type: ${rType.toStrictString()}")
        }

        if (mutable && logAnnotation) {
            val ann = C_Defs.LOG_ANNOTATION
            throw C_Error(name.pos, "class_attr_mutable_log:$className:$nameStr",
                    "Class '$className' cannot have mutable attributes because of the '$ann' annotation")
        }

        if (entityType == C_EntityType.OBJECT && expr == null) {
            throw C_Error(name.pos, "object_attr_novalue:$className:$nameStr",
                    "Object attribute '$className.$nameStr' must have a default value")
        }

        val exprCreator: (() -> R_Expr)? = if (expr == null) null else { ->
            val rExpr = expr.compile(entCtx.rootExprCtx).value().toRExpr()
            S_Type.match(rType, rExpr.type, name.pos, "attr_type:$nameStr", "Default value type missmatch for '$nameStr'")
            rExpr
        }

        addAttribute0(nameStr, rType, mutable, true, exprCreator)
    }

    fun addAttribute0(name: String, rType: R_Type, mutable: Boolean, canSetInCreate: Boolean, exprCreator: (() -> R_Expr)?) {
        check(name !in attributes)
        check(entCtx.entityType != C_EntityType.OBJECT || exprCreator != null)

        val rAttr = R_Attrib(attributes.size, name, rType, mutable, exprCreator != null, canSetInCreate)

        if (exprCreator == null) {
            rAttr.setExpr(null)
        } else {
            entCtx.nsCtx.modCtx.onPass(C_ModulePass.EXPRESSIONS) {
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
        if (entCtx.entityType == C_EntityType.OBJECT) {
            throw C_Error(pos, "object_keyindex:${className}", "Object cannot have key or index")
        }

        val nameSet = names.toSet()
        if (!set.add(nameSet)) {
            val nameLst = names.sorted()
            throw C_Error(pos, "$errCode:${nameLst.joinToString(",")}", "$errMsg: ${nameLst.joinToString()}")
        }
    }
}

private fun buildRecordsStructure(records: Collection<R_RecordType>): RecordsStructure {
    val structMap = records.map { Pair(it, calcRecStruct(it)) }.toMap()
    val graph = structMap.mapValues { (_, v) -> v.dependencies.toList() }
    val mutable = structMap.filter { (_, v) -> v.directFlags.mutable }.keys
    val nonGtxHuman = structMap.filter { (_, v) -> !v.directFlags.gtxHuman }.keys
    val nonGtxCompact = structMap.filter { (_, v) -> !v.directFlags.gtxCompact }.keys
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
