package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.lexer.TokenMatch
import net.postchain.rell.model.*
import net.postchain.rell.module.GTX_OPERATION_HUMAN
import net.postchain.rell.module.GTX_QUERY_HUMAN

class C_Error(val pos: S_Pos, val code: String, val errMsg: String): Exception("$pos $errMsg")

class S_Pos(val row: Int, val col: Int) {
    constructor(t: TokenMatch): this(t.row, t.column)

    override fun toString() = "($row:$col)"
}

class S_Node<T>(val pos: S_Pos, val value: T) {
    constructor(t: TokenMatch, value: T): this(S_Pos(t), value)
}

class S_Name(val pos: S_Pos, val str: String) {
    override fun toString() = str
}

class S_NameTypePair(val name: S_Name, val type: S_Type?) {
    fun compileType(ctx: C_ModuleContext): RType {
        if (type != null) {
            return type.compile(ctx)
        }

        val rType = ctx.getTypeOpt(name.str)
        if (rType == null) {
            throw C_Error(name.pos, "unknown_name_type:${name.str}",
                    "Type for '${name.str}' not specified and no type called '${name.str}'")
        }

        return rType
    }
}

class C_UserFunctionDeclaration(val name: String, val params: List<RExternalParam>, val type: RType)

sealed class C_Function {
    abstract fun compileCall(name: S_Name, args: List<RExpr>): RExpr
    abstract fun compileCallDb(name: S_Name, args: List<DbExpr>): DbExpr
}

class C_SysFunction(val fn: S_SysFunction): C_Function() {
    override fun compileCall(name: S_Name, args: List<RExpr>): RExpr = fn.compileCall(name.pos, args)
    override fun compileCallDb(name: S_Name, args: List<DbExpr>): DbExpr = fn.compileCallDb(name.pos, args)
}

class C_UserFunction(val fn: C_UserFunctionDeclaration, val fnKey: Int): C_Function() {
    override fun compileCall(name: S_Name, args: List<RExpr>): RExpr {
        val params = fn.params.map { it.type }
        S_SysFunction.matchArgs(name, params, args)
        return RUserCallExpr(fn.type, fn.name, fnKey, args)
    }

    override fun compileCallDb(name: S_Name, args: List<DbExpr>): DbExpr {
        throw C_Error(name.pos, "call_userfn_nosql:${name.str}", "Cannot call function '${name.str}' in SQL")
    }
}

class C_GlobalContext(val gtx: Boolean) {
    private var frameBlockIdCtr = 0L

    fun nextFrameBlockId(): RFrameBlockId = RFrameBlockId(frameBlockIdCtr++)
}

class C_ModuleContext(val globalCtx: C_GlobalContext) {
    private val types = mutableMapOf(
            "boolean" to RBooleanType,
            "text" to RTextType,
            "byte_array" to RByteArrayType,
            "integer" to RIntegerType,
            "pubkey" to RByteArrayType,
            "name" to RTextType,
            "timestamp" to RIntegerType,
            "signer" to RSignerType,
            "guid" to RGUIDType,
            "tuid" to RTextType,
            "json" to RJSONType,
            "range" to RRangeType,
            "GTXValue" to RGtxValueType
    )

    private val classes = mutableMapOf<String, RClass>()
    private val records = mutableMapOf<String, RRecordType>()
    private val operations = mutableMapOf<String, ROperation>()
    private val queries = mutableMapOf<String, RQuery>()
    private val functions = mutableMapOf<String, C_Function>()
    private val functionDecls = mutableListOf<C_UserFunctionDeclaration>()
    private val functionDefs = mutableListOf<RFunction>()

    val classesPass = C_ModulePass()
    val functionsPass = C_ModulePass()

    init {
        for (fn in S_LibFunctions.getGlobalFunctions()) {
            check(fn.name !in functions)
            functions[fn.name] = C_SysFunction(fn)
        }
    }

    fun checkTypeName(name: S_Name) {
        checkNameConflict("record", name, records)
        checkNameConflict("type", name, types)
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

    fun getType(name: S_Name): RType {
        val nameStr = name.str
        val type = getTypeOpt(nameStr)
        if (type == null) {
            throw C_Error(name.pos, "unknown_type:$nameStr", "Unknown type: '$nameStr'")
        }
        return type
    }

    fun getTypeOpt(name: String): RType? = types[name]

    fun getClass(name: S_Name): RClass {
        val nameStr = name.str
        val cls = classes[nameStr]
        if (cls == null) {
            throw C_Error(name.pos, "unknown_class:$nameStr", "Unknown class: '$nameStr'")
        }
        return cls
    }

    fun getRecord(name: S_Name): RRecordType {
        val nameStr = name.str
        val rec = records[nameStr]
        if (rec == null) {
            throw C_Error(name.pos, "unknown_record:$nameStr", "Unknown record: '$nameStr'")
        }
        return rec
    }

    fun getRecordOpt(name: String): RRecordType? {
        return records[name]
    }

    fun getFunction(name: S_Name): C_Function {
        val nameStr = name.str
        val fn = functions[nameStr]
        if (fn == null) {
            throw C_Utils.errUnknownFunction(name)
        }
        return fn
    }

    fun addClass(cls: RClass) {
        check(cls.name !in types)
        check(cls.name !in classes)
        classes[cls.name] = cls
        types[cls.name] = RInstanceRefType(cls)
    }

    fun addRecord(rec: RRecordType) {
        check(rec.name !in types)
        check(rec.name !in records)
        records[rec.name] = rec
        types[rec.name] = rec
    }

    fun addQuery(query: RQuery) {
        check(query.name !in queries)
        queries[query.name] = query
    }

    fun addOperation(operation: ROperation) {
        check(operation.name !in operations)
        operations[operation.name] = operation
    }

    fun addFunctionDeclaration(declaration: C_UserFunctionDeclaration): Int {
        check(declaration.name !in functions)
        val fnKey = functionDecls.size
        functionDecls.add(declaration)
        functions[declaration.name] = C_UserFunction(declaration, fnKey)
        return fnKey
    }

    fun addFunctionDefinition(function: RFunction) {
        check(functionDefs.size == function.fnKey)
        functionDefs.add(function)
    }

    fun createModule(): RModule {
        classesPass.run()
        processRecords()
        functionsPass.run()
        return RModule(classes.toMap(), records.toMap(), operations.toMap(), queries.toMap(), functionDefs.toList())
    }

    private fun processRecords() {
        val structure = buildRecordsStructure(records.values)
        val graph = structure.graph
        val transGraph = C_GraphUtils.transpose(graph)

        val cyclicRecs = C_GraphUtils.findCyclicVertices(graph).toSet()
        val infiniteRecs = C_GraphUtils.closure(transGraph, cyclicRecs).toSet()
        val mutableRecs = C_GraphUtils.closure(transGraph, structure.mutable).toSet()
        val nonGtxHumanRecs = C_GraphUtils.closure(transGraph, structure.nonGtxHuman).toSet()
        val nonGtxCompactRecs = C_GraphUtils.closure(transGraph, structure.nonGtxCompact).toSet()

        for (record in records.values) {
            val typeFlags = RTypeFlags(record in mutableRecs, record !in nonGtxHumanRecs, record !in nonGtxCompactRecs)
            val flags = RRecordFlags(typeFlags, record in cyclicRecs, record in infiniteRecs)
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
    QUERY,
    OPERATION,
    FUNCTION,
}

class C_EntityContext(
        val modCtx: C_ModuleContext,
        val entityType: C_EntityType,
        explicitReturnType: RType?
){
    private val retTypeTracker =
            if (explicitReturnType != null) RetTypeTracker.Explicit(explicitReturnType) else RetTypeTracker.Implicit()

    private var callFrameSize = 0

    val rootExprCtx = C_ExprContext(this, null, false)

    fun checkDbUpdateAllowed(pos: S_Pos) {
        if (entityType == C_EntityType.QUERY) {
            throw C_Error(pos, "no_db_update", "Database modifications are not allowed in this context")
        }
    }

    fun matchReturnType(pos: S_Pos, type: RType) {
        retTypeTracker.match(pos, type)
    }

    fun actualReturnType(): RType = retTypeTracker.getRetType()

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun makeCallFrame(): RCallFrame {
        val rootBlock = rootExprCtx.makeFrameBlock()
        return RCallFrame(callFrameSize, rootBlock)
    }

    private sealed class RetTypeTracker {
        abstract fun getRetType(): RType
        abstract fun match(pos: S_Pos, type: RType)

        class Implicit: RetTypeTracker() {
            private var impType: RType? = null

            override fun getRetType(): RType {
                val t = impType
                if (t != null) return t
                val res = RUnitType
                impType = res
                return res
            }

            override fun match(pos: S_Pos, type: RType) {
                val t = impType
                if (t == null) {
                    impType = type
                } else if (t == RUnitType) {
                    if (type != RUnitType) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                } else {
                    val comType = RType.commonTypeOpt(t, type)
                    if (comType == null) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                    impType = comType
                }
            }
        }

        class Explicit(val expType: RType): RetTypeTracker() {
            override fun getRetType() = expType

            override fun match(pos: S_Pos, type: RType) {
                val m = if (expType == RUnitType) type == RUnitType else expType.isAssignableFrom(type)
                if (!m) {
                    throw errRetTypeMiss(pos, expType, type)
                }
            }
        }
    }

    companion object {
        private fun errRetTypeMiss(pos: S_Pos, dstType: RType, srcType: RType): C_Error =
                C_Utils.errTypeMissmatch(pos, srcType, dstType, "entity_rettype", "Return type missmatch")
    }
}

class C_ScopeEntry(val name: String, val type: RType, val modifiable: Boolean, val ptr: RVarPtr) {
    fun toVarExpr(): RVarExpr = RVarExpr(type, ptr, name)
}

class C_ExprContext(
        val entCtx: C_EntityContext,
        private val parent: C_ExprContext?,
        val insideLoop: Boolean
){
    private val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    private val locals = mutableMapOf<String, C_ScopeEntry0>()

    val blockId = entCtx.modCtx.globalCtx.nextFrameBlockId()

    fun add(name: S_Name, type: RType, modifiable: Boolean): RVarPtr {
        val nameStr = name.str
        if (lookupOpt(nameStr) != null) {
            throw C_Error(name.pos, "var_dupname:$nameStr", "Duplicate variable: '$nameStr'")
        }

        val ofs = startOffset + locals.size
        entCtx.adjustCallFrameSize(ofs + 1)

        val entry = C_ScopeEntry0(nameStr, type, modifiable, ofs)
        locals.put(nameStr, entry)

        return entry.toVarPtr(blockId)
    }

    fun lookup(name: S_Name): C_ScopeEntry {
        val nameStr = name.str
        val local = lookupOpt(nameStr)
        if (local == null) {
            throw C_Error(name.pos, "unknown_name:$nameStr", "Unknown name: '$nameStr'")
        }
        return local
    }

    fun lookupOpt(name: String): C_ScopeEntry? {
        var ctx: C_ExprContext? = this
        while (ctx != null) {
            val local = ctx.locals[name]
            if (local != null) {
                return local.toScopeEntry(blockId)
            }
            ctx = ctx.parent
        }
        return null
    }

    fun makeFrameBlock(): RFrameBlock {
        val parentId = if (parent != null) parent.blockId else null
        return RFrameBlock(parentId, blockId, startOffset, locals.size)
    }

    companion object {
        private class C_ScopeEntry0(val name: String, val type: RType, val modifiable: Boolean, val offset: Int) {
            fun toVarPtr(blockId: RFrameBlockId): RVarPtr = RVarPtr(blockId, offset)

            fun toScopeEntry(blockId: RFrameBlockId): C_ScopeEntry {
                return C_ScopeEntry(name, type, modifiable, toVarPtr(blockId))
            }
        }
    }
}

sealed class S_RelClause {
    abstract fun compileAttributes(ctx: C_ClassContext)
    abstract fun compileRest(ctx: C_ClassContext)
}

class S_AttributeClause(val attr: S_NameTypePair, val mutable: Boolean, val expr: S_Expr?): S_RelClause() {
    override fun compileAttributes(ctx: C_ClassContext) {
        ctx.addAttribute(attr, mutable, expr)
    }

    override fun compileRest(ctx: C_ClassContext) {}
}

sealed class S_KeyIndexClause(val pos: S_Pos, val attrs: List<S_NameTypePair>): S_RelClause() {
    final override fun compileAttributes(ctx: C_ClassContext) {}

    abstract fun addToContext(ctx: C_ClassContext, pos: S_Pos, names: List<S_Name>)

    final override fun compileRest(ctx: C_ClassContext) {
        val names = mutableSetOf<String>()
        for (attr in attrs) {
            if (!names.add(attr.name.str)) {
                throw C_Error(attr.name.pos, "class_keyindex_dup:${attr.name.str}",
                        "Duplicate attribute: '${attr.name.str}'")
            }
        }

        for (attr in attrs) {
            if (ctx.hasAttribute(attr.name.str)) {
                if (attr.type != null) {
                    throw C_Error(attr.name.pos, "class_keyindex_def:${attr.name.str}",
                            "Attribute '${attr.name.str}' is defined elsewhere, cannot specify type")
                }
            } else {
                ctx.addAttribute(attr, false, null)
            }
        }

        addToContext(ctx, pos, attrs.map { it.name })
    }
}

class S_KeyClause(pos: S_Pos, attrs: List<S_NameTypePair>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: C_ClassContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addKey(pos, names)
    }
}

class S_IndexClause(pos: S_Pos, attrs: List<S_NameTypePair>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: C_ClassContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addIndex(pos, names)
    }
}

sealed class S_Definition(val name: S_Name) {
    abstract fun compile(ctx: C_ModuleContext)
}

class C_ClassContext(private val modCtx: C_ModuleContext, private val dataClass: Boolean) {
    private val entCtx = C_EntityContext(modCtx, C_EntityType.CLASS, null)

    private val attributes = mutableMapOf<String, RAttrib>()
    private val keys = mutableListOf<RKey>()
    private val indices = mutableListOf<RIndex>()
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
        if (dataClass && !rType.isAllowedForClassAttribute()) {
            throw C_Error(name.pos, "class_attr_type:$nameStr:${rType.toStrictString()}",
                    "Attribute '$nameStr' has unallowed type: ${rType.toStrictString()}")
        }

        val rAttr = RAttrib(attributes.size, nameStr, rType, mutable, expr != null)

        if (expr == null) {
            rAttr.setExpr(null)
        } else {
            modCtx.functionsPass.add {
                val rExpr = expr.compile(entCtx.rootExprCtx)
                S_Type.match(rType, rExpr.type, name.pos, "attr_type:$nameStr", "Default value type missmatch for '$nameStr'")
                rAttr.setExpr(rExpr)
            }
        }

        attributes[nameStr] = rAttr
    }

    fun addKey(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueKeys, names, "class_key_dup", "Duplicate key")
        keys.add(RKey(names))
    }

    fun addIndex(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueIndices, names, "class_index_dup", "Duplicate index")
        indices.add(RIndex(names))
    }

    fun createClassBody(): RClassBody {
        return RClassBody(keys.toList(), indices.toList(), attributes.toMap())
    }

    fun createRecordBody(): Map<String, RAttrib> {
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

class S_ClassDefinition(name: S_Name, val clauses: List<S_RelClause>): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext) {
        ctx.checkTypeName(name)

        val rClass = RClass(name.str)
        ctx.addClass(rClass)

        ctx.classesPass.add {
            classesPass(ctx, rClass)
        }
    }

    private fun classesPass(ctx: C_ModuleContext, rClass: RClass) {
        val clsCtx = C_ClassContext(ctx, true)
        for (clause in clauses) {
            clause.compileAttributes(clsCtx)
        }
        for (clause in clauses) {
            clause.compileRest(clsCtx)
        }

        val body = clsCtx.createClassBody()
        rClass.setBody(body)
    }
}

class S_RecordDefinition(name: S_Name, val attrs: List<S_AttributeClause>): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext) {
        ctx.checkRecordName(name)

        val rType = RRecordType(name.str)
        ctx.addRecord(rType)

        ctx.classesPass.add {
            classesPass(ctx, rType)
        }
    }

    private fun classesPass(ctx: C_ModuleContext, rType: RRecordType) {
        val clsCtx = C_ClassContext(ctx, false)
        for (clause in attrs) {
            clause.compileAttributes(clsCtx)
        }

        val attributes = clsCtx.createRecordBody()
        rType.setAttributes(attributes)
    }
}

class S_OpDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val body: S_Statement
): S_Definition(name)
{
    override fun compile(ctx: C_ModuleContext) {
        ctx.functionsPass.add {
            doCompile(ctx)
        }
    }

    private fun doCompile(ctx: C_ModuleContext) {
        ctx.checkOperationName(name)

        val entCtx = C_EntityContext(ctx, C_EntityType.OPERATION, null)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compile(entCtx.rootExprCtx)
        val rCallFrame = entCtx.makeCallFrame()

        if (ctx.globalCtx.gtx) {
            checkGtxParams(params, rParams, GTX_OPERATION_HUMAN)
        }

        val rOperation = ROperation(name.str, rParams, rBody, rCallFrame)
        ctx.addOperation(rOperation)
    }
}

class S_QueryDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext) {
        ctx.functionsPass.add {
            doCompile(ctx)
        }
    }

    private fun doCompile(ctx: C_ModuleContext) {
        ctx.checkOperationName(name)

        val rExplicitRetType = retType?.compile(ctx)

        val entCtx = C_EntityContext(ctx, C_EntityType.QUERY, rExplicitRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compileQuery(name, entCtx.rootExprCtx)
        val rCallFrame = entCtx.makeCallFrame()
        val rRetType = entCtx.actualReturnType()

        if (ctx.globalCtx.gtx) {
            checkGtxParams(params, rParams, GTX_QUERY_HUMAN)
            checkGtxResult(rRetType, GTX_QUERY_HUMAN)
        }

        val rQuery = RQuery(name.str, rRetType, rParams, rBody, rCallFrame)
        ctx.addQuery(rQuery)
    }

    private fun checkGtxResult(rType: RType, human: Boolean) {
        val flags = rType.completeFlags()
        val gtx = if (human) flags.gtxHuman else flags.gtxCompact
        if (!gtx) {
            throw C_Error(name.pos, "result_nogtx:${name.str}:${rType.toStrictString()}",
                    "Return type of query '${name.str}' is not GTX-conversible: ${rType.toStrictString()}")
        }
    }
}

private fun checkGtxParams(params: List<S_NameTypePair>, rParams: List<RExternalParam>, human: Boolean) {
    params.forEachIndexed { i, param ->
        val type = rParams[i].type
        val flags = type.completeFlags()
        val gtx = if (human) flags.gtxHuman else flags.gtxCompact
        if (!gtx) {
            val name = param.name.str
            val typeStr = type.toStrictString()
            throw C_Error(param.name.pos, "param_nogtx:$name:$typeStr",
                    "Type of parameter '$name' is not GTX-conversible: $typeStr")
        }
    }
}

abstract class S_FunctionBody {
    abstract fun compileQuery(name: S_Name, ctx: C_ExprContext): RStatement
    abstract fun compileFunction(name: S_Name, ctx: C_ExprContext): RStatement
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun compileQuery(name: S_Name, ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        C_Utils.checkUnitType(name.pos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        ctx.entCtx.matchReturnType(name.pos, rExpr.type)
        return RReturnStatement(rExpr)
    }

    override fun compileFunction(name: S_Name, ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        ctx.entCtx.matchReturnType(name.pos, rExpr.type)

        if (rExpr.type == RUnitType) {
            val subCtx = C_ExprContext(ctx.entCtx, ctx, ctx.insideLoop)
            val rBlock = subCtx.makeFrameBlock()
            return RBlockStatement(listOf(RExprStatement(rExpr), RReturnStatement(null)), rBlock)
        } else {
            return RReturnStatement(rExpr)
        }
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun compileQuery(name: S_Name, ctx: C_ExprContext): RStatement {
        val rBody = body.compile(ctx)

        val ret = body.returns()
        if (!ret) {
            throw C_Error(name.pos, "query_noreturn:${name.str}", "Query '${name.str}': not all code paths return value")
        }

        return rBody
    }

    override fun compileFunction(name: S_Name, ctx: C_ExprContext): RStatement {
        val rBody = body.compile(ctx)

        val retType = ctx.entCtx.actualReturnType()
        if (retType != RUnitType) {
            val ret = body.returns()
            if (!ret) {
                throw C_Error(name.pos, "fun_noreturn:${name.str}", "Function '${name.str}': not all code paths return value")
            }
        }

        return rBody
    }
}

class S_FunctionDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext) {
        ctx.checkFunctionName(name)

        val rRetType = if (retType != null) retType.compile(ctx) else RUnitType

        val entCtx = C_EntityContext(ctx, C_EntityType.FUNCTION, rRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)

        val declaration = C_UserFunctionDeclaration(name.str, rParams, rRetType)
        val fnKey = ctx.addFunctionDeclaration(declaration)

        ctx.functionsPass.add {
            secondPass(ctx, entCtx, declaration, fnKey)
        }
    }

    private fun secondPass(
            ctx: C_ModuleContext,
            entCtx: C_EntityContext,
            declaration: C_UserFunctionDeclaration,
            fnKey: Int
    ){
        val rBody = body.compileFunction(name, entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rFunction = RFunction(name.str, declaration.params, rBody, rCallFrame, declaration.type, fnKey)
        ctx.addFunctionDefinition(rFunction)
    }
}

class S_ModuleDefinition(val definitions: List<S_Definition>) {
    fun compile(gtx: Boolean): RModule {
        val globalCtx = C_GlobalContext(gtx)
        val ctx = C_ModuleContext(globalCtx)

        for (def in definitions) {
            def.compile(ctx)
        }

        return ctx.createModule()
    }
}

private fun compileExternalParams(
        ctx: C_ModuleContext,
        exprCtx: C_ExprContext,
        params: List<S_NameTypePair>
) : List<RExternalParam>
{
    val rParams = compileParams(ctx, params)

    val rExtParams = rParams.map { (name, rParam) ->
        val ptr = exprCtx.add(name, rParam.type, false)
        RExternalParam(name.str, rParam.type, ptr)
    }

    return rExtParams.toList()
}

private fun compileParams(ctx: C_ModuleContext, params: List<S_NameTypePair>): List<Pair<S_Name, RVariable>> {
    val names = mutableSetOf<String>()

    val res = params.map { param ->
        val nameStr = param.name.str
        if (!names.add(nameStr)) {
            throw C_Error(param.name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        }
        val rType = param.compileType(ctx)
        Pair(param.name, RVariable(nameStr, rType))
    }

    return res
}

private fun buildRecordsStructure(records: Collection<RRecordType>): RecordsStructure {
    val structMap = records.map { Pair(it, calcRecStruct(it)) }.toMap()
    val graph = structMap.mapValues { (k, v) -> v.dependencies.toList() }
    val mutable = structMap.filter { (k, v) -> v.directFlags.mutable }.keys
    val nonGtxHuman = structMap.filter { (k, v) -> !v.directFlags.gtxHuman }.keys
    val nonGtxCompact = structMap.filter { (k, v) -> !v.directFlags.gtxCompact }.keys
    return RecordsStructure(mutable, nonGtxHuman, nonGtxCompact, graph)
}

private fun calcRecStruct(type: RType): RecStruct {
    val flags = mutableListOf(type.directFlags())
    val deps = mutableSetOf<RRecordType>()

    for (subType in type.componentTypes()) {
        val subStruct = discoverRecStruct(subType)
        flags.add(subStruct.directFlags)
        deps.addAll(subStruct.dependencies)
    }

    val resFlags = RTypeFlags.combine(flags)
    return RecStruct(resFlags, deps.toSet())
}

private fun discoverRecStruct(type: RType): RecStruct {
    if (type is RRecordType) {
        return RecStruct(type.directFlags(), setOf(type))
    }
    return calcRecStruct(type)
}

private class RecordsStructure(
        val mutable: Set<RRecordType>,
        val nonGtxHuman: Set<RRecordType>,
        val nonGtxCompact: Set<RRecordType>,
        val graph: Map<RRecordType, List<RRecordType>>
)

private class RecStruct(val directFlags: RTypeFlags, val dependencies: Set<RRecordType>)
