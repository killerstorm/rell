package net.postchain.rell.parser

import com.sun.xml.internal.ws.util.StringUtils
import net.postchain.rell.CommonUtils
import net.postchain.rell.TypedKeyMap
import net.postchain.rell.model.*
import java.util.*

class C_Error: RuntimeException {
    val pos: S_Pos
    val code: String
    val errMsg: String
    val posStack: List<S_Pos>

    constructor(pos: S_Pos, code: String, errMsg: String): this(pos, code, errMsg, listOf(pos))

    private constructor(pos: S_Pos, code: String, errMsg: String, posStack: List<S_Pos>): super("$pos $errMsg") {
        this.pos = pos
        this.code = code
        this.errMsg = errMsg
        this.posStack = posStack
    }

    fun addStack(outerStack: List<S_Pos>): C_Error {
        return C_Error(pos, code, errMsg, outerStack + posStack)
    }
}

abstract class C_GlobalFunction {
    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr
}

abstract class C_RegularGlobalFunction: C_GlobalFunction() {
    abstract fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr

    override final fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val cArgs = compileArgs(ctx, args)
        return compileCallRegular(ctx, name, cArgs)
    }

    companion object {
        fun compileArgs(ctx: C_ExprContext, args: List<S_NameExprPair>): List<C_Value> {
            val namedArg = args.map { it.name }.filterNotNull().firstOrNull()
            if (namedArg != null) {
                val argName = namedArg.str
                throw C_Error(namedArg.pos, "expr_call_namedarg:$argName", "Named function arguments not supported")
            }

            val cArgs = args.map {
                val sArg = it.expr
                val cArg = sArg.compile(ctx).value()
                val type = cArg.type()
                C_Utils.checkUnitType(sArg.startPos, type, "expr_arg_unit", "Argument expression returns nothing")
                cArg
            }

            return cArgs
        }
    }
}

class C_RecordGlobalFunction(private val record: R_RecordType): C_GlobalFunction() {
    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        return compileCall(record, ctx, name, args)
    }

    companion object {
        fun compileCall(record: R_RecordType, ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
            val attrs = C_AttributeResolver.resolveCreate(ctx, record.attributes, args, name.pos)
            val rExpr = R_RecordExpr(record, attrs.rAttrs)
            return C_RValue.makeExpr(name.pos, rExpr, attrs.exprFacts)
        }
    }
}

class C_UserFunctionHeader(val params: List<R_ExternalParam>, val type: R_Type)

class C_UserGlobalFunction(val name: String, val fnKey: Int): C_RegularGlobalFunction() {
    private lateinit var headerLate: C_UserFunctionHeader

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate = header
    }

    override fun compileCallRegular(ctx: C_ExprContext, sName: S_Name, args: List<C_Value>): C_Expr {
        val header = headerLate
        val params = header.params.map { it.type }
        val rArgs = args.map { it.toRExpr() }
        C_FuncUtils.checkArgs(sName, params, rArgs)
        val rExpr = R_UserCallExpr(header.type, name, fnKey, rArgs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(args)
        return C_RValue.makeExpr(sName.pos, rExpr, exprFacts)
    }

    fun toRFunction(body: R_Statement, frame: R_CallFrame): R_Function {
        val header = headerLate
        return R_Function(name, header.params, body, frame, header.type, fnKey)
    }
}

enum class C_MessageType(val text: String, val ignorable: Boolean) {
    WARNING("Warning", true),
    ERROR("ERROR", false)
}

class C_Message(
        val type: C_MessageType,
        val pos: S_Pos,
        val code: String,
        val text: String,
        val posStack: List<S_Pos> = listOf(pos)
) {
    override fun toString(): String {
        return "$pos ${type.text}: $text"
    }
}

class C_GlobalContext(val compilerOptions: C_CompilerOptions) {
    private var frameBlockIdCtr = 0L
    private val messages = mutableListOf<C_Message>()
    private val includeStack = LinkedList<S_Pos>()

    fun nextFrameBlockId(): R_FrameBlockId = R_FrameBlockId(frameBlockIdCtr++)

    fun message(type: C_MessageType, pos: S_Pos, code: String, text: String) {
        val posStack = includeStack + listOf(pos)
        messages.add(C_Message(type, pos, code, text, posStack))
    }

    fun warning(pos: S_Pos, code: String, text: String) {
        message(C_MessageType.WARNING, pos, code, text)
    }

    fun error(error: C_Error) {
        val message = C_Message(C_MessageType.ERROR, error.pos, error.code, error.errMsg, error.posStack)
        messages.add(message)
    }

    fun messages() = messages.toList()

    fun includeStack() = includeStack.toList()

    fun <T> processIncludedFile(pos: S_Pos, code: () -> T) = processIncludedFile(listOf(pos), code)

    fun <T> processIncludedFile(stack: List<S_Pos>, code: () -> T): T {
        includeStack.addAll(stack)
        try {
            val res = code()
            return res
        } catch (e: C_Error) {
            throw e.addStack(stack)
        } finally {
            stack.forEach { includeStack.removeLast() }
        }
    }
}

object C_Defs {
    val LOG_ANNOTATION = "log"
    val MODULE_ARGS_RECORD = "module_args"

    val TRANSACTION_CLASS = "transaction"
    val BLOCK_CLASS = "block"
}

class C_Class(val name: S_Name?, val cls: R_Class)
class C_Record(val name: S_Name, val type: R_RecordType)

enum class C_ModulePass {
    NAMES,
    MEMBERS,
    EXPRESSIONS,
    VALIDATION,
}

sealed class C_Deprecated {
    abstract fun detailsCode(): String
    abstract fun detailsMessage(): String
}

class C_Deprecated_UseInstead(private val name: String): C_Deprecated() {
    override fun detailsCode() = ":$name"
    override fun detailsMessage() = ", use '$name' instead"
}

abstract class C_Def<T>(private val type: C_DefType, private val def: T, private val deprecated: C_Deprecated?) {
    fun useDef(nsCtx: C_NamespaceContext, name: List<S_Name>): T {
        if (deprecated != null) {
            val name = name.last()
            deprecatedMessage(nsCtx, type, name.pos, name.str, deprecated)
        }
        return def
    }

    companion object {
        fun deprecatedMessage(
                nsCtx: C_NamespaceContext,
                type: C_DefType,
                pos: S_Pos,
                name: String,
                deprecated: C_Deprecated
        ) {
            val typeStr = StringUtils.capitalize(type.description)
            val depCode = deprecated.detailsCode()
            val depStr = deprecated.detailsMessage()
            val code = "deprecated:$type:$name$depCode"
            val msg = "$typeStr '$name' is deprecated$depStr"
            val globalCtx = nsCtx.modCtx.globalCtx
            val msgType = if (globalCtx.compilerOptions.deprecatedError) C_MessageType.ERROR else C_MessageType.WARNING
            globalCtx.message(msgType, pos, code, msg)
        }
    }
}

class C_TypeDef(type: R_Type, deprecated: C_Deprecated? = null)
    : C_Def<R_Type>(C_DefType.TYPE, type, deprecated)
class C_NamespaceDef(namespace: C_Namespace, deprecated: C_Deprecated? = null)
    : C_Def<C_Namespace>(C_DefType.NAMESPACE, namespace, deprecated)

class C_ModuleContext(val globalCtx: C_GlobalContext) {
    companion object {
        private val SYSTEM_TYPES = mapOf(
                "boolean" to C_TypeDef(R_BooleanType),
                "text" to C_TypeDef(R_TextType),
                "byte_array" to C_TypeDef(R_ByteArrayType),
                "integer" to C_TypeDef(R_IntegerType),
                "pubkey" to C_TypeDef(R_ByteArrayType),
                "name" to C_TypeDef(R_TextType),
                "timestamp" to C_TypeDef(R_IntegerType),
                "signer" to C_TypeDef(R_SignerType),
                "guid" to C_TypeDef(R_GUIDType),
                "tuid" to C_TypeDef(R_TextType),
                "json" to C_TypeDef(R_JsonType),
                "range" to C_TypeDef(R_RangeType),
                "GTXValue" to C_TypeDef(R_GtvType, C_Deprecated_UseInstead("gtv")),
                "gtv" to C_TypeDef(R_GtvType)
        )
    }

    private val blockClass = C_Utils.createBlockClass(null, null)
    private val transactionClass = C_Utils.createTransactionClass(null, null, blockClass)
    val transactionClassType = R_ClassType(transactionClass)

    private val allClasses = mutableMapOf<String, C_Class>()
    private val allObjects = mutableMapOf<String, R_Object>()
    private val allRecords = mutableMapOf<String, R_RecordType>()
    private val allOperations = mutableMapOf<String, R_Operation>()
    private val allQueries = mutableMapOf<String, R_Query>()

    private var entityCount: Int = 0
    private var functionCount: Int = 0
    private val functions = mutableListOf<R_Function>()

    private val externalChains = mutableMapOf<String, R_ExternalChain>()

    private val passes = C_ModulePass.values().map { Pair(it, mutableListOf<C_PassTask>()) }.toMap()
    private var currentPass = C_ModulePass.NAMES

    val nsCtx = C_NamespaceContext(
            this,
            null,
            null,
            predefNamespaces = C_LibFunctions.getSystemNamespaces(),
            predefTypes = SYSTEM_TYPES,
            predefClasses =  listOf(blockClass, transactionClass),
            predefFunctions = C_LibFunctions.getGlobalFunctions()
    )

    fun getModuleArgsRecord(): R_RecordType? {
        return nsCtx.getRecordOpt(C_Defs.MODULE_ARGS_RECORD)?.type
    }

    fun nextEntityIndex(): Int = entityCount++
    fun nextFunctionKey(): Int = functionCount++

    fun addClass(def: R_Class, sName: S_Name?) {
        addDef(def.name, C_Class(sName, def), allClasses)
    }

    fun addObject(def: R_Object) {
        addDef(def.rClass.name, def, allObjects)
    }

    fun addRecord(def: R_RecordType) = addDef(def.name, def, allRecords)
    fun addOperation(def: R_Operation) = addDef(def.name, def, allOperations)
    fun addQuery(def: R_Query) = addDef(def.name, def, allQueries)

    private fun <T> addDef(name: String, def: T, map: MutableMap<String, T>) {
        check(name !in map) { name }
        map[name] = def
    }

    fun addFunctionDefinition(f: R_Function) {
        check(functions.size == f.fnKey)
        functions.add(f)
    }

    fun addExternalChain(pos: S_Pos, name: String): R_ExternalChain {
        if (name.isEmpty()) {
            throw C_Error(pos, "def_external_invalid:$name", "Invalid chain name: '$name'")
        }
        if (name in externalChains) {
            throw C_Error(pos, "def_external_dup:$name", "Chain '$name' already exported")
        }
        val index = externalChains.size
        val chain = R_ExternalChain(name, index)
        externalChains[name] = chain
        return chain
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
        check(currentPass < pass) { "currentPass: $currentPass targetPass: $pass" }

        val stack = globalCtx.includeStack()

        val ordinal = currentPass.ordinal
        if (ordinal + 1 == pass.ordinal) {
            val task = C_PassTask(stack, code)
            passes.getValue(pass).add(task)
        } else {
            // Extra code is needed to maintain execution order:
            // - entity 0 adds code to pass A, that code adds code to pass B
            // - entity 1 adds code to pass B directly
            // -> on pass B entity 0 must be executed before entity 1
            val nextPass = C_ModulePass.values()[ordinal + 1]

            val task = C_PassTask(stack) { onPass(pass, code) }
            passes.getValue(nextPass).add(task)
        }
    }

    private fun runPass(pass: C_ModulePass) {
        check(currentPass < pass)
        currentPass = pass
        for (task in passes.getValue(pass)) {
            globalCtx.processIncludedFile(task.stack) {
                task.code()
            }
        }
    }

    fun createModule(): R_Module {
        nsCtx.createNamespace()
        runPass(C_ModulePass.MEMBERS)
        processRecords()
        runPass(C_ModulePass.EXPRESSIONS)
        runPass(C_ModulePass.VALIDATION)

        val topologicalClasses = calcTopologicalClasses()

        val moduleArgs = nsCtx.getRecordOpt(C_Defs.MODULE_ARGS_RECORD)
        if (moduleArgs != null && !moduleArgs.type.flags.typeFlags.gtv.fromGtv) {
            throw C_Error(moduleArgs.name.pos, "module_args_nogtv",
                    "Record '${C_Defs.MODULE_ARGS_RECORD}' is not Gtv-compatible")
        }

        return R_Module(
                allClasses.mapValues { it.value.cls }.toMap(),
                allObjects.toMap(),
                allRecords.toMap(),
                allOperations.toMap(),
                allQueries.toMap(),
                functions.toList(),
                moduleArgs?.type,
                topologicalClasses,
                externalChains.values.toList()
        )
    }

    private fun processRecords() {
        val records = allRecords.values
        val structure = C_RecordUtils.buildRecordsStructure(records)
        val graph = structure.graph
        val transGraph = C_GraphUtils.transpose(graph)

        val cyclicRecs = C_GraphUtils.findCyclicVertices(graph).toSet()
        val infiniteRecs = C_GraphUtils.closure(transGraph, cyclicRecs).toSet()
        val mutableRecs = C_GraphUtils.closure(transGraph, structure.mutable).toSet()
        val nonVirtualableRecs = C_GraphUtils.closure(transGraph, structure.nonVirtualable).toSet()
        val nonGtvFromRecs = C_GraphUtils.closure(transGraph, structure.nonGtvFrom).toSet()
        val nonGtvToRecs = C_GraphUtils.closure(transGraph, structure.nonGtvTo).toSet()

        for (record in records) {
            val gtv = R_GtvCompatibility(record !in nonGtvFromRecs, record !in nonGtvToRecs)
            val typeFlags = R_TypeFlags(record in mutableRecs, gtv, record !in nonVirtualableRecs)
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

    private class C_PassTask(val stack: List<S_Pos>, val code: () -> Unit)
}

enum class C_DefType(val description: String) {
    NAMESPACE("namespace"),
    TYPE("type"),
    CLASS("class"),
    RECORD("record"),
    ENUM("enum"),
    OBJECT("object"),
    FUNCTION("function"),
    OPERATION("operation"),
    QUERY("query"),
    EXTERNAL("external")
}

class C_NamespaceContext(
        val modCtx: C_ModuleContext,
        private val parentCtx: C_NamespaceContext?,
        val namespaceName: String?,
        predefNamespaces: Map<String, C_NamespaceDef> = mapOf(),
        predefTypes: Map<String, C_TypeDef> = mapOf(),
        predefClasses: List<R_Class> = listOf(),
        predefFunctions: Map<String, C_GlobalFunction> = mapOf()
){
    private val names = mutableMapOf<String, C_DefType>()
    private val nsBuilder = C_NamespaceBuilder()

    private val records = mutableMapOf<String, C_Record>()

    private lateinit var namespace: C_Namespace

    init {
        val predefValues = predefNamespaces.mapValues { C_NamespaceValue_Namespace(it.value) }

        addPredefs(predefTypes, C_DefType.TYPE, nsBuilder::addType)
        addPredefs(predefNamespaces, C_DefType.NAMESPACE, nsBuilder::addNamespace)
        addPredefs(predefValues, C_DefType.NAMESPACE, nsBuilder::addValue)
        addPredefs(predefFunctions, C_DefType.FUNCTION, nsBuilder::addFunction)

        for (cls in predefClasses) {
            // Assuming only top-level namespace can have predefined classes, so name == fullName
            check(parentCtx == null)
            val name = cls.name
            check(name !in names)
            modCtx.addClass(cls, null)
            addClass0(name, cls)
            names[name] = C_DefType.CLASS
        }
    }

    private fun <T> addPredefs(predefs: Map<String, T>, type: C_DefType, adder: (String, T) -> Unit) {
        for ((name, def) in predefs) {
            adder(name, def)
            // For predefined names, name duplication is allowed (e.g. "integer" is a type, function and namespace)
            if (name !in names) names[name] = type
        }
    }

    fun fullName(name: String) = C_Utils.fullName(namespaceName, name)

    fun getType(name: List<S_Name>): R_Type {
        val type = getTypeOpt(name)
        if (type == null) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "unknown_type:$nameStr", "Unknown type: '$nameStr'")
        }
        return type
    }

    fun getTypeOpt(name: List<S_Name>): R_Type? {
        val typeDef = getTypeDefOpt(name)
        val type = typeDef?.useDef(this, name)
        return type
    }

    private fun getTypeDefOpt(name: List<S_Name>): C_TypeDef? {
        check(!name.isEmpty())

        val name0 = name[0].str
        if (name.size == 1) {
            val typeDef = getDefOpt { it.namespace.types[name0] }
            return typeDef
        }

        var nsDef = getDefOpt { it.namespace.namespaces[name0] }
        if (nsDef == null) return null

        for (i in 1 .. name.size - 2) {
            val nsName = name.subList(0, i)
            val ns = nsDef!!.useDef(this, nsName)
            nsDef = ns.namespaces[name[i].str]
            if (nsDef == null) return null
        }

        val nsName = name.subList(0, name.size - 1)
        val typeDef = nsDef!!.useDef(this, nsName).types[name[name.size - 1].str]
        return typeDef
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
        modCtx.checkPass(C_ModulePass.MEMBERS, null)
        var ctx: C_NamespaceContext? = this
        while (ctx != null) {
            val def = getter(ctx)
            if (def != null) return def
            ctx = ctx.parentCtx
        }
        return null
    }

    fun addNamespace(name: String, nsDef: C_NamespaceDef) {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        nsBuilder.addNamespace(name, nsDef)
        nsBuilder.addValue(name, C_NamespaceValue_Namespace(nsDef))
    }

    fun addClass(name: S_Name, cls: R_Class) {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        modCtx.addClass(cls, name)
        addClass0(name.str, cls)
    }

    fun addClass0(name: String, cls: R_Class) {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        val type = R_ClassType(cls)
        val typeDef = C_TypeDef(type)
        nsBuilder.addType(name, typeDef)
        nsBuilder.addValue(name, C_NamespaceValue_Class(typeDef))
    }

    fun addObject(sName: S_Name, obj: R_Object) {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        modCtx.addObject(obj)
        nsBuilder.addValue(sName.str, C_NamespaceValue_Object(obj))
    }

    fun addRecord(rec: C_Record) {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        val name = rec.name.str
        check(name !in records)
        modCtx.addRecord(rec.type)
        nsBuilder.addType(name, C_TypeDef(rec.type))
        nsBuilder.addValue(name, C_NamespaceValue_Record(rec.type))
        nsBuilder.addFunction(name, C_RecordGlobalFunction(rec.type))
        records[name] = rec
    }

    fun addEnum(name: String, e: R_EnumType) {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        nsBuilder.addType(name, C_TypeDef(e))
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
        modCtx.checkPass(null, C_ModulePass.NAMES)
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

    fun registerName(name: S_Name, type: C_DefType): String {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        val oldType = names[name.str]
        if (oldType != null) {
            throw C_Error(name.pos, "name_conflict:${oldType.description}:${name.str}",
                    "Name conflict: ${oldType.description} '${name.str}' exists")
        }
        names[name.str] = type
        return fullName(name.str)
    }

    fun createNamespace(): C_Namespace {
        modCtx.checkPass(null, C_ModulePass.NAMES)
        namespace = nsBuilder.build()
        return namespace
    }
}

class C_DefinitionContext(
        val nsCtx: C_NamespaceContext,
        val chainCtx: C_ExternalChainContext?,
        val incCtx: C_IncludeContext,
        val namespaceName: String?
) {
    val modCtx = nsCtx.modCtx

    fun checkNotExternal(pos: S_Pos, defType: C_DefType) {
        if (chainCtx != null) {
            val def = defType.description
            throw C_Error(pos, "def_external:$def", "Not allowed to have $def in external block")
        }
    }

    fun fullName(name: String) = C_Utils.fullName(namespaceName, name)
}

class C_ExternalChainContext(val nsCtx: C_NamespaceContext, val chain: R_ExternalChain) {
    private var classDeclaredBlock = false
    private var classDeclaredTx = false
    private lateinit var transactionClassType: R_Type

    fun declareClassBlock() {
        check(!classDeclaredBlock)
        classDeclaredBlock = true
    }

    fun declareClassTransaction() {
        check(!classDeclaredTx)
        classDeclaredTx = true
    }

    fun registerSysClasses() {
        val blkClassNs = sysClassNamespace(classDeclaredBlock)
        val txClassNs = sysClassNamespace(classDeclaredTx)

        val blkClass = C_Utils.createBlockClass(blkClassNs, chain)
        val txClass = C_Utils.createTransactionClass(txClassNs, chain, blkClass)

        nsCtx.modCtx.addClass(blkClass, null)
        nsCtx.modCtx.addClass(txClass, null)

        if (classDeclaredBlock) nsCtx.addClass0(C_Defs.BLOCK_CLASS, blkClass)
        if (classDeclaredTx) nsCtx.addClass0(C_Defs.TRANSACTION_CLASS, txClass)

        transactionClassType = R_ClassType(txClass)
    }

    private fun sysClassNamespace(defined: Boolean): String {
        return if (defined) nsCtx.namespaceName!! else "external[${chain.name}]"
    }

    fun transactionClassType(): R_Type {
        nsCtx.modCtx.checkPass(C_ModulePass.MEMBERS, null)
        return transactionClassType
    }
}

class C_IncludeContext private constructor(
        val resolver: C_IncludeResolver,
        private val pathChain: List<String>,
        private val namespaceIncludes: MutableSet<String> = mutableSetOf()
) {
    private val includes = mutableSetOf<String>()

    fun subInclude(pos: S_Pos, resource: C_IncludeResource): C_IncludeContext? {
        val path = resource.path

        val recIdx = pathChain.indexOf(path)
        if (recIdx >= 0) {
            val recChain = pathChain.subList(recIdx, pathChain.size) + listOf(path)
            throw C_Error(pos, "include_rec:${recChain.joinToString(",")}",
                    "Recursive file inclusion: ${recChain.joinToString(" -> ") { "'$it'" }}")
        }

        if (!includes.add(path)) {
            throw C_Error(pos, "include_dup:$path", "File already included: '$path'")
        }

        val subChain = pathChain + listOf(path)
        if (subChain.size >= MAX_DEPTH) {
            throw C_Error(pos, "include_long:${subChain.size}:${subChain.first()}:${subChain.last()}",
                    "Inclusion chain is too long (${subChain.size}): ${subChain.joinToString(" -> ") { "'$it'" }}")
        }

        if (!namespaceIncludes.add(path)) {
            return null
        }

        return C_IncludeContext(resource.innerResolver, subChain, namespaceIncludes)
    }

    fun subNamespace(): C_IncludeContext {
        return C_IncludeContext(resolver, pathChain)
    }

    companion object {
        private val MAX_DEPTH = 100

        fun createTop(path: String, resolver: C_IncludeResolver) = C_IncludeContext(resolver, listOf(path))
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
        explicitReturnType: R_Type?,
        val statementVars: TypedKeyMap
){
    private val retTypeTracker =
            if (explicitReturnType != null) RetTypeTracker.Explicit(explicitReturnType) else RetTypeTracker.Implicit()

    private var callFrameSize = 0

    private var nextLoopId = 0
    private var nextVarId = 0

    private val rootBlkCtx = C_BlockContext(this, null, null)
    private val rootNameCtx = C_RNameContext(rootBlkCtx)
    val rootExprCtx = C_ExprContext(rootBlkCtx, rootNameCtx, C_VarFactsContext(C_VarFacts.EMPTY))

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

    fun nextLoopId() = C_LoopId(nextLoopId++)
    fun nextVarId(name: String) = C_VarId(nextVarId++, name)

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
                C_Errors.errTypeMismatch(pos, srcType, dstType, "entity_rettype", "Return type mismatch")
    }
}

class C_StatementVars(val declared: Set<String>, val modified: Set<String>) {
    companion object {
        val EMPTY = C_StatementVars(setOf(), setOf())
    }
}

class C_StatementVarsBlock {
    private val declared = mutableSetOf<String>()
    private val modified = mutableSetOf<String>()

    fun declared(names: Set<String>) {
        declared.addAll(names)
    }

    fun modified(names: Set<String>) {
        for (name in names) {
            if (name !in declared) {
                modified.add(name)
            }
        }
    }

    fun modified() = modified.toSet()
}

class C_ScopeEntry(
        val name: String,
        val type: R_Type,
        val modifiable: Boolean,
        val varId: C_VarId,
        val ptr: R_VarPtr
) {
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
        val insideClass = entityType == C_EntityType.CLASS || entityType == C_EntityType.OBJECT
        if (insideClass && !rType.sqlAdapter.isSqlCompatible()) {
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
            S_Type.match(rType, rExpr.type, name.pos, "attr_type:$nameStr", "Default value type mismatch for '$nameStr'")
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
                compileAttributeExpression(rAttr, exprCreator)
            }
        }

        attributes[name] = rAttr
    }

    private fun compileAttributeExpression(rAttr: R_Attrib, exprCreator: (() -> R_Expr)) {
        val rExpr = exprCreator()
        check(rAttr.type.isAssignableFrom(rExpr.type)) {
            val exp = rAttr.type.toStrictString()
            val act = rExpr.type.toStrictString()
            "Attribute '$className.${rAttr.name}' expression type mismatch: expected '$exp', was '$act'"
        }
        rAttr.setExpr(rExpr)
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

class C_CompilerOptions(val gtv: Boolean = true, val deprecatedError: Boolean = false)

object C_Compiler {
    fun compile(
            sourceDir: C_SourceDir,
            mainFile: C_SourcePath,
            options: C_CompilerOptions = C_CompilerOptions()
    ): C_CompilationResult {
        val globalCtx = C_GlobalContext(options)
        var module: R_Module? = null
        var error: C_Error? = null

        try {
            val ast = readMainFile(sourceDir, mainFile)
            val includeResolver = createIncludeResolver(sourceDir, mainFile)
            module = ast.compile(globalCtx, mainFile.str(), includeResolver)
        } catch (e: C_Error) {
            globalCtx.error(e)
            error = e
        }

        val messages = CommonUtils.sortedByCopy(globalCtx.messages()) { ComparablePos(it.pos) }

        val errorMessage = messages.firstOrNull { it.type == C_MessageType.ERROR }
        if (error == null && errorMessage != null) {
            error = C_Error(errorMessage.pos, errorMessage.code, errorMessage.text)
        }

        return C_CompilationResult(module, error, messages)
    }

    fun getIncludedResources(
            sourceDir: C_SourceDir,
            mainFilePath: C_SourcePath,
            transitive: Boolean,
            fail: Boolean
    ): List<C_IncludeResource> {
        val ast = readMainFile(sourceDir, mainFilePath)
        val includeResolver = createIncludeResolver(sourceDir, mainFilePath)

        val globalCtx = C_GlobalContext(C_CompilerOptions())
        val incCtx = C_IncludeContext.createTop(mainFilePath.str(), includeResolver)
        val resources = mutableMapOf<String, C_IncludeResource>()
        ast.collectIncludes(globalCtx, incCtx, transitive, fail, resources)

        val includes = resources.values.toList()
        return includes
    }

    private fun readMainFile(sourceDir: C_SourceDir, mainFile: C_SourcePath): S_ModuleDefinition {
        val mainSourceFile = C_IncludeResolver.resolveFile(sourceDir, mainFile)
        val ast = mainSourceFile.readAst()
        return ast
    }

    private fun createIncludeResolver(sourceDir: C_SourceDir, mainFile: C_SourcePath): C_IncludeResolver {
        val mainDir = mainFile.parent()
        val includeResolver = C_IncludeResolver(sourceDir, mainDir)
        return includeResolver
    }

    private class ComparablePos(sPos: S_Pos): Comparable<ComparablePos> {
        private val path: C_SourcePath = sPos.path()
        private val pos = sPos.pos()

        override fun compareTo(other: ComparablePos): Int {
            var d = path.compareTo(other.path)
            if (d == 0) d = pos.compareTo(other.pos)
            return d
        }
    }
}

class C_CompilationResult(val module: R_Module?, val error: C_Error?, val messages: List<C_Message>)
