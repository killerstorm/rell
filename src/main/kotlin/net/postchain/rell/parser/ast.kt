package net.postchain.rell.parser

import net.postchain.rell.MutableTypedKeyMap
import net.postchain.rell.TypedKeyMap
import net.postchain.rell.model.*
import net.postchain.rell.parser.ide.IdeOutlineNodeType
import net.postchain.rell.parser.ide.IdeOutlineTreeBuilder
import java.util.function.Supplier

abstract class S_Pos {
    abstract fun path(): C_SourcePath
    abstract fun pos(): Long
    abstract fun str(): String
    abstract fun strLine(): String
    override fun toString() = str()
}

class S_BasicPos(private val file: C_SourcePath, private val row: Int, private val col: Int): S_Pos() {
    override fun path() = file
    override fun pos() = Math.min(row, 1_000_000_000) * 1_000_000_000L + Math.min(col, 1_000_000_000)
    override fun str() = "$file($row:$col)"
    override fun strLine() = "$file($row)"

    override fun equals(other: Any?): Boolean {
        return other is S_BasicPos && file == other.file && row == other.row && col == other.col
    }
}

abstract class S_Node {
    val attachment: Any? = getAttachment()

    companion object {
        private val ATTACHMENT_PROVIDER_LOCAL = ThreadLocal<Supplier<Any?>>()

        @JvmStatic
        fun runWithAttachmentProvider(provider: Supplier<Any?>, code: Runnable) {
            val oldProvider = ATTACHMENT_PROVIDER_LOCAL.get()
            ATTACHMENT_PROVIDER_LOCAL.set(provider)
            try {
                code.run()
            } finally {
                ATTACHMENT_PROVIDER_LOCAL.set(oldProvider)
            }
        }

        private fun getAttachment(): Any? {
            val provider = ATTACHMENT_PROVIDER_LOCAL.get()
            val res = provider?.get()
            return res
        }
    }
}

class S_PosValue<T>(val pos: S_Pos, val value: T) {
    constructor(t: RellTokenMatch, value: T): this(t.pos, value)
}

class S_Name(val pos: S_Pos, val str: String): S_Node() {
    override fun toString() = str
}

class S_NameTypePair(val name: S_Name, val type: S_Type?): S_Node() {
    fun compileType(ctx: C_NamespaceContext): R_Type {
        if (type != null) {
            return type.compile(ctx)
        }

        val rType = ctx.getTypeOpt(listOf(name))
        if (rType == null) {
            throw C_Error(name.pos, "unknown_name_type:${name.str}",
                    "Type for '${name.str}' not specified and no type called '${name.str}'")
        }

        return rType
    }
}

sealed class S_RelClause: S_Node() {
    abstract fun compileAttributes(ctx: C_ClassContext)
    abstract fun compileRest(ctx: C_ClassContext)
    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)
}

class S_AttributeClause(val attr: S_NameTypePair, val mutable: Boolean, val expr: S_Expr?): S_RelClause() {
    override fun compileAttributes(ctx: C_ClassContext) {
        ctx.addAttribute(attr, mutable, expr)
    }

    override fun compileRest(ctx: C_ClassContext) {}

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, attr.name, IdeOutlineNodeType.ATTRIBUTE)
    }
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

    final override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (attr in attrs) {
            b.node(attr, attr.name, IdeOutlineNodeType.KEY_INDEX)
        }
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

sealed class S_Definition: S_Node() {
    abstract fun compile(ctx: C_DefinitionContext, entityIndex: Int)

    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)

    open fun collectIncludes(
            globalCtx: C_GlobalContext,
            incCtx: C_IncludeContext,
            transitive: Boolean,
            fail: Boolean,
            resources: MutableMap<String, C_IncludeResource>
    ){
    }
}

class S_ClassDefinition(val name: S_Name, val annotations: List<S_Name>, val body: List<S_RelClause>?): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        if (body == null) {
            compileHeader(ctx)
            return
        }

        val fullName = ctx.nsCtx.registerName(name, C_DefType.CLASS)

        val chain = ctx.chainCtx?.chain
        val external = chain != null
        val rFlags = compileFlags(external)

        if (external && !rFlags.log) {
            throw C_Error(name.pos, "def_class_external_nolog:$fullName",
                    "External class '$fullName' must have '${C_Defs.LOG_ANNOTATION}' annotation")
        }

        if (external && ctx.namespaceName == null && (name.str == C_Defs.BLOCK_CLASS || name.str == C_Defs.TRANSACTION_CLASS)) {
            throw C_Error(name.pos, "def_class_external_unallowed:${name.str}",
                    "External class '${name.str}' can be declared only without body (as class header)")
        }

        val physicalFullName = ctx.fullName(name.str)
        val sqlTable = classNameToSqlTable(physicalFullName)
        val rMapping = if (chain == null) R_ClassSqlMapping_Regular(sqlTable) else R_ClassSqlMapping_External(sqlTable, chain)

        val rExternalClass = if (chain == null) null else R_ExternalClass(chain, physicalFullName, true)

        val rClass = R_Class(fullName, rFlags, rMapping, rExternalClass)
        ctx.nsCtx.addClass(name, rClass)

        ctx.modCtx.onPass(C_ModulePass.MEMBERS) {
            membersPass(ctx, entityIndex, rClass, body)
        }
    }

    private fun compileHeader(ctx: C_DefinitionContext) {
        ctx.nsCtx.registerName(name, C_DefType.CLASS)

        if (!annotations.isEmpty()) {
            throw C_Error(name.pos, "def_class_hdr_annotations:${name.str}",
                    "Annotations not allowed for class header '${name.str}'")
        }

        if (name.str != C_Defs.BLOCK_CLASS && name.str != C_Defs.TRANSACTION_CLASS) {
            val classes = listOf(C_Defs.BLOCK_CLASS, C_Defs.TRANSACTION_CLASS).joinToString()
            throw C_Error(name.pos, "def_class_hdr_name:${name.str}",
                    "Class header declarations allowed only for classes: $classes")
        }

        if (ctx.chainCtx == null) {
            throw C_Error(name.pos, "def_class_hdr_noexternal:${name.str}",
                    "Class header must be declared in external block")
        }

        if (ctx.namespaceName != null) {
            throw C_Error(name.pos, "def_class_hdr_ns:${name.str}",
                    "Class header '${name.str}' cannot be declared in a namespace")
        }

        if (name.str == C_Defs.BLOCK_CLASS) {
            ctx.chainCtx.declareClassBlock()
        } else {
            ctx.chainCtx.declareClassTransaction()
        }
    }

    private fun compileFlags(external: Boolean): R_ClassFlags {
        val set = mutableSetOf<String>()
        var log = false

        for (ann in annotations) {
            val annStr = ann.str
            if (!set.add(annStr)) {
                throw C_Error(ann.pos, "class_ann_dup:$annStr", "Duplicate annotation: '$annStr'")
            }

            if (annStr == C_Defs.LOG_ANNOTATION) {
                log = true
            } else {
                throw C_Error(ann.pos, "class_ann_bad:$annStr", "Invalid annotation: '$annStr'")
            }
        }

        return R_ClassFlags(
                isObject = false,
                canCreate = !external,
                canUpdate = !external,
                canDelete = !log && !external,
                gtv = true,
                log = log
        )
    }

    private fun membersPass(ctx: C_DefinitionContext, entityIndex: Int, rClass: R_Class, clauses: List<S_RelClause>) {
        val entCtx = C_EntityContext(ctx.nsCtx, C_EntityType.CLASS, entityIndex, null, TypedKeyMap())
        val clsCtx = C_ClassContext(entCtx, name.str, rClass.flags.log)

        if (rClass.flags.log) {
            val txType = if (ctx.chainCtx == null) ctx.modCtx.transactionClassType else ctx.chainCtx.transactionClassType()
            clsCtx.addAttribute0("transaction", txType, false, false) {
                if (ctx.chainCtx == null) {
                    C_Ns_OpContext.transactionExpr(clsCtx.entCtx)
                } else {
                    C_Utils.crashExpr(txType, "Trying to initialize transaction for external class '${rClass.name}'")
                }
            }
        }

        compileClauses(clsCtx, clauses)

        val body = clsCtx.createClassBody()
        rClass.setBody(body)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.CLASS)
        for (clause in body ?: listOf()) {
            clause.ideBuildOutlineTree(sub)
        }
    }

    companion object {
        fun compileClauses(clsCtx: C_ClassContext, clauses: List<S_RelClause>) {
            for (clause in clauses) {
                clause.compileAttributes(clsCtx)
            }
            for (clause in clauses) {
                clause.compileRest(clsCtx)
            }
        }
    }
}

class S_ObjectDefinition(val name: S_Name, val clauses: List<S_RelClause>): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        val fullName = ctx.nsCtx.registerName(name, C_DefType.OBJECT)
        ctx.checkNotExternal(name.pos, C_DefType.OBJECT)

        val classFlags = R_ClassFlags(true, false, true, false, false, false)

        val sqlTable = classNameToSqlTable(fullName)
        val sqlMapping = R_ClassSqlMapping_Regular(sqlTable)

        val rClass = R_Class(fullName, classFlags, sqlMapping, null)
        val rObject = R_Object(rClass, entityIndex)
        ctx.nsCtx.addObject(name, rObject)

        ctx.modCtx.onPass(C_ModulePass.MEMBERS) {
            membersPass(ctx.nsCtx, entityIndex, rObject)
        }
    }

    private fun membersPass(ctx: C_NamespaceContext, entityIndex: Int, rObject: R_Object) {
        val entCtx = C_EntityContext(ctx, C_EntityType.OBJECT, entityIndex, null, TypedKeyMap())
        val clsCtx = C_ClassContext(entCtx, name.str, false)
        S_ClassDefinition.compileClauses(clsCtx, clauses)

        val body = clsCtx.createClassBody()
        rObject.rClass.setBody(body)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.OBJECT)
        for (clause in clauses) {
            clause.ideBuildOutlineTree(sub)
        }
    }
}

class S_RecordDefinition(val name: S_Name, val attrs: List<S_AttributeClause>): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        val fullName = ctx.nsCtx.registerName(name, C_DefType.RECORD)
        ctx.checkNotExternal(name.pos, C_DefType.RECORD)

        val rType = R_RecordType(fullName)
        ctx.nsCtx.addRecord(C_Record(name, rType))

        ctx.modCtx.onPass(C_ModulePass.MEMBERS) {
            membersPass(ctx.nsCtx, entityIndex, rType)
        }
    }

    private fun membersPass(ctx: C_NamespaceContext, entityIndex: Int, rType: R_RecordType) {
        val entCtx = C_EntityContext(ctx, C_EntityType.RECORD, entityIndex, null, TypedKeyMap())
        val clsCtx = C_ClassContext(entCtx, name.str, false)
        for (clause in attrs) {
            clause.compileAttributes(clsCtx)
        }

        val attributes = clsCtx.createRecordBody()
        rType.setAttributes(attributes)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.RECORD)
        for (attr in attrs) {
            attr.ideBuildOutlineTree(sub)
        }
    }
}

class S_EnumDefinition(val name: S_Name, val attrs: List<S_Name>): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        val fullName = ctx.nsCtx.registerName(name, C_DefType.ENUM)
        ctx.checkNotExternal(name.pos, C_DefType.ENUM)

        val set = mutableSetOf<String>()
        val rAttrs = mutableListOf<R_EnumAttr>()

        for (attr in attrs) {
            if (!set.add(attr.str)) {
                throw C_Error(attr.pos, "enum_dup:${attr.str}", "Duplicate enum constant: '${attr.str}'")
            }
            rAttrs.add(R_EnumAttr(attr.str, rAttrs.size))
        }

        val rEnum = R_EnumType(fullName, rAttrs.toList())
        ctx.nsCtx.addEnum(name.str, rEnum)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.ENUM)
        for (attr in attrs) {
            sub.node(attr, attr, IdeOutlineNodeType.ENUM_ATTRIBUTE)
        }
    }
}

class S_OpDefinition(val name: S_Name, val params: List<S_NameTypePair>, val body: S_Statement): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        val fullName = ctx.nsCtx.registerName(name, C_DefType.OPERATION)
        ctx.checkNotExternal(name.pos, C_DefType.OPERATION)

        ctx.modCtx.onPass(C_ModulePass.EXPRESSIONS) {
            doCompile(ctx.nsCtx, entityIndex, fullName)
        }
    }

    private fun doCompile(ctx: C_NamespaceContext, entityIndex: Int, fullName: String) {
        val statementVars = processStatementVars()
        val entCtx = C_EntityContext(ctx, C_EntityType.OPERATION, entityIndex, null, statementVars)

        val (exprCtx, rParams) = compileExternalParams(ctx, entCtx, params)
        val rBody = body.compile(exprCtx).rStmt
        val rCallFrame = entCtx.makeCallFrame()

        if (ctx.modCtx.globalCtx.compilerOptions.gtv) {
            checkGtvParams(params, rParams)
        }

        val rOperation = R_Operation(fullName, rParams, rBody, rCallFrame)
        ctx.addOperation(rOperation)
    }

    private fun processStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        body.discoverVars(map)
        return map.immutableCopy()
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.OPERATION)
    }
}

class S_QueryDefinition(
        val name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        val fullName = ctx.nsCtx.registerName(name, C_DefType.QUERY)
        ctx.checkNotExternal(name.pos, C_DefType.QUERY)

        ctx.modCtx.onPass(C_ModulePass.EXPRESSIONS) {
            doCompile(ctx.nsCtx, entityIndex, fullName)
        }
    }

    private fun doCompile(ctx: C_NamespaceContext, entityIndex: Int, fullName: String) {
        val rExplicitRetType = retType?.compile(ctx)
        val statementVars = body.processStatementVars()
        val entCtx = C_EntityContext(ctx, C_EntityType.QUERY, entityIndex, rExplicitRetType, statementVars)

        val (exprCtx, rParams) = compileExternalParams(ctx, entCtx, params)
        val rBody = body.compileQuery(name, exprCtx)
        val rCallFrame = entCtx.makeCallFrame()
        val rRetType = entCtx.actualReturnType()

        if (ctx.modCtx.globalCtx.compilerOptions.gtv) {
            checkGtvParams(params, rParams)
            checkGtvResult(rRetType)
        }

        val rQuery = R_Query(fullName, rRetType, rParams, rBody, rCallFrame)
        ctx.addQuery(rQuery)
    }

    private fun checkGtvResult(rType: R_Type) {
        checkGtvCompatibility(name.pos, rType, false, "result_nogtv:${name.str}", "Return type of query '${name.str}'")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.QUERY)
    }
}

private fun checkGtvParams(params: List<S_NameTypePair>, rParams: List<R_ExternalParam>) {
    params.forEachIndexed { i, param ->
        val type = rParams[i].type
        val name = param.name.str
        checkGtvCompatibility(param.name.pos, type, true, "param_nogtv:$name", "Type of parameter '$name'")
    }
}

private fun checkGtvCompatibility(pos: S_Pos, type: R_Type, from: Boolean, errCode: String, errMsg: String) {
    val flags = type.completeFlags()
    val flag = if (from ) flags.gtv.fromGtv else flags.gtv.toGtv
    if (!flag) {
        throw C_Errors.errTypeNotGtvCompatible(pos, type, null, errCode, errMsg)
    }
}

abstract class S_FunctionBody {
    abstract fun processStatementVars(): TypedKeyMap
    abstract fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement
    abstract fun compileFunction(name: S_Name, ctx: C_ExprContext): R_Statement
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun processStatementVars() = TypedKeyMap()

    override fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement {
        val cExpr = expr.compile(ctx)
        val rExpr = cExpr.value().toRExpr()
        C_Utils.checkUnitType(name.pos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        ctx.blkCtx.entCtx.matchReturnType(name.pos, rExpr.type)
        return R_ReturnStatement(rExpr)
    }

    override fun compileFunction(name: S_Name, ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        ctx.blkCtx.entCtx.matchReturnType(name.pos, rExpr.type)

        if (rExpr.type != R_UnitType) {
            return R_ReturnStatement(rExpr)
        }

        val blkCtx = ctx.blkCtx
        val subBlkCtx = C_BlockContext(blkCtx.entCtx, blkCtx, blkCtx.loop)
        val rBlock = subBlkCtx.makeFrameBlock()

        return R_BlockStatement(listOf(R_ExprStatement(rExpr), R_ReturnStatement(null)), rBlock)
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun processStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        body.discoverVars(map)
        return map.immutableCopy()
    }

    override fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement {
        val cBody = body.compile(ctx)

        if (!cBody.returnAlways) {
            throw C_Error(name.pos, "query_noreturn:${name.str}", "Query '${name.str}': not all code paths return value")
        }

        return cBody.rStmt
    }

    override fun compileFunction(name: S_Name, ctx: C_ExprContext): R_Statement {
        val cBody = body.compile(ctx)

        val retType = ctx.blkCtx.entCtx.actualReturnType()
        if (retType != R_UnitType) {
            if (!cBody.returnAlways) {
                throw C_Error(name.pos, "fun_noreturn:${name.str}", "Function '${name.str}': not all code paths return value")
            }
        }

        return cBody.rStmt
    }
}

class S_FunctionDefinition(
        val name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        ctx.nsCtx.registerName(name, C_DefType.FUNCTION)
        ctx.checkNotExternal(name.pos, C_DefType.FUNCTION)

        val fn = ctx.nsCtx.addFunctionDeclaration(name.str)
        ctx.modCtx.onPass(C_ModulePass.MEMBERS) {
            compileDefinition(ctx.nsCtx, entityIndex, fn)
        }
    }

    private fun compileDefinition(ctx: C_NamespaceContext, entityIndex: Int, fn: C_UserGlobalFunction) {
        val rRetType = if (retType != null) retType.compile(ctx) else R_UnitType
        val statementVars = body.processStatementVars()
        val entCtx = C_EntityContext(ctx, C_EntityType.FUNCTION, entityIndex, rRetType, statementVars)

        val (exprCtx, rParams) = compileExternalParams(ctx, entCtx, params)
        val header = C_UserFunctionHeader(rParams, rRetType)
        fn.setHeader(header)

        ctx.modCtx.onPass(C_ModulePass.EXPRESSIONS) {
            compileFinish(ctx, entCtx, exprCtx, fn)
        }
    }

    private fun compileFinish(
            ctx: C_NamespaceContext,
            entCtx: C_EntityContext,
            exprCtx: C_ExprContext,
            fn: C_UserGlobalFunction
    ){
        val rBody = body.compileFunction(name, exprCtx)
        val rCallFrame = entCtx.makeCallFrame()
        val rFunction = fn.toRFunction(rBody, rCallFrame)
        ctx.addFunctionBody(rFunction)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.FUNCTION)
    }
}

class S_NamespaceDefinition(val name: S_Name, val definitions: List<S_Definition>): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        ctx.nsCtx.registerName(name, C_DefType.NAMESPACE)

        val subCtx = ctx.nsCtx.addNamespace(ctx, name.str)

        for (def in definitions) {
            val index = ctx.modCtx.nextEntityIndex()
            def.compile(subCtx, index)
        }
    }

    override fun collectIncludes(
            globalCtx: C_GlobalContext,
            incCtx: C_IncludeContext,
            transitive: Boolean,
            fail: Boolean,
            resources: MutableMap<String, C_IncludeResource>
    ){
        for (def in definitions) {
            def.collectIncludes(globalCtx, incCtx, transitive, fail, resources)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.NAMESPACE)
        for (def in definitions) {
            def.ideBuildOutlineTree(sub)
        }
    }
}

class S_ExternalDefinition(val pos: S_Pos, val name: String, val definitions: List<S_Definition>): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        ctx.checkNotExternal(pos, C_DefType.EXTERNAL)

        val chain = ctx.modCtx.addExternalChain(pos, name)
        val subChainCtx = C_ExternalChainContext(ctx.nsCtx, chain)
        val subIncCtx = ctx.incCtx.subExternal()
        val subCtx = C_DefinitionContext(ctx.nsCtx, subChainCtx, subIncCtx, null)

        for (def in definitions) {
            val index = ctx.modCtx.nextEntityIndex()
            def.compile(subCtx, index)
        }

        subChainCtx.registerSysClasses()
    }

    override fun collectIncludes(
            globalCtx: C_GlobalContext,
            incCtx: C_IncludeContext,
            transitive: Boolean,
            fail: Boolean,
            resources: MutableMap<String, C_IncludeResource>
    ){
        for (def in definitions) {
            def.collectIncludes(globalCtx, incCtx, transitive, fail, resources)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (def in definitions) {
            def.ideBuildOutlineTree(b)
        }
    }
}

class S_IncludeDefinition(val pos: S_Pos, val pathPos: S_Pos, val path: String): S_Definition() {
    override fun compile(ctx: C_DefinitionContext, entityIndex: Int) {
        val resource = resolve(ctx.incCtx)
        val subIncCtx = ctx.incCtx.subInclude(pathPos, resource)
        if (subIncCtx == null) {
            // Already included in current namespace (transitively, so no error).
            return
        }

        val globalCtx = ctx.nsCtx.modCtx.globalCtx
        val module = readAst(globalCtx, resource)

        globalCtx.processIncludedFile(pos) {
            val subDefCtx = C_DefinitionContext(ctx.nsCtx, ctx.chainCtx, subIncCtx, ctx.namespaceName)
            module.compileDefs(subDefCtx)
        }
    }

    override fun collectIncludes(
            globalCtx: C_GlobalContext,
            incCtx: C_IncludeContext,
            transitive: Boolean,
            fail: Boolean,
            resources: MutableMap<String, C_IncludeResource>
    ){
        try {
            collectIncludes0(globalCtx, incCtx, transitive, fail, resources)
        } catch (e: C_Error) {
            if (fail) {
                throw e
            }
        }
    }

    private fun collectIncludes0(
            globalCtx: C_GlobalContext,
            ctx: C_IncludeContext,
            transitive: Boolean,
            fail: Boolean,
            resources: MutableMap<String, C_IncludeResource>
    ){
        val resource = resolve(ctx)
        val subIncCtx = ctx.subInclude(pathPos, resource)
        if (subIncCtx == null) {
            return
        }

        if (resource.path !in resources) {
            resources[resource.path] = resource
        }

        if (transitive) {
            val module = readAst(globalCtx, resource)
            globalCtx.processIncludedFile(pos) {
                module.collectIncludes(globalCtx, subIncCtx, transitive, fail, resources)
            }
        }
    }

    private fun resolve(ctx: C_IncludeContext): C_IncludeResource {
        if (path == "" || path == "." || path == ".." || path.endsWith("/") || path.endsWith("\\")) {
            throw C_Error(pathPos, "include_bad_path:$path", "Invalid path: '$path'")
        }

        val fullPath = path + ".rell"
        try {
            return ctx.resolver.resolve(fullPath, path)
        } catch (e: C_CommonError) {
            throw C_Error(pathPos, e.code, e.msg)
        }
    }

    private fun readAst(globalCtx: C_GlobalContext, resource: C_IncludeResource): S_ModuleDefinition {
        try {
            return globalCtx.processIncludedFile(pos) {
                resource.file.readAst()
            }
        } catch (e: C_CommonError) {
            throw C_Error(pos, e.code, e.msg)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        // Do nothing.
    }
}

class S_ModuleDefinition(val definitions: List<S_Definition>): S_Node() {
    fun compile(globalCtx: C_GlobalContext, path: String, includeResolver: C_IncludeResolver): R_Module {
        val ctx = C_ModuleContext(globalCtx)

        val incCtx = C_IncludeContext.createTop(path, includeResolver)
        val defCtx = C_DefinitionContext(ctx.nsCtx, null, incCtx, null)
        compileDefs(defCtx)

        val rModule = ctx.createModule()
        return rModule
    }

    fun compileDefs(defCtx: C_DefinitionContext) {
        for (def in definitions) {
            val index = defCtx.nsCtx.modCtx.nextEntityIndex()
            def.compile(defCtx, index)
        }
    }

    fun collectIncludes(
            globalCtx: C_GlobalContext,
            incCtx: C_IncludeContext,
            transitive: Boolean,
            fail: Boolean,
            resources: MutableMap<String, C_IncludeResource>
    ){
        for (def in definitions) {
            def.collectIncludes(globalCtx, incCtx, transitive, fail, resources)
        }
    }

    fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (def in definitions) {
            def.ideBuildOutlineTree(b)
        }
    }
}

private fun compileExternalParams(
        ctx: C_NamespaceContext,
        entCtx: C_EntityContext,
        params: List<S_NameTypePair>
): Pair<C_ExprContext, List<R_ExternalParam>>
{
    val blkCtx = entCtx.rootExprCtx.blkCtx
    val rParams = compileParams(ctx, params)

    val inited = mutableMapOf<C_VarId, C_VarFact>()

    val rExtParams = rParams.map { (name, rParam) ->
        val (cId, ptr) = blkCtx.add(name, rParam.type, false)
        inited[cId] = C_VarFact.YES
        R_ExternalParam(name.str, rParam.type, ptr)
    }

    val varFacts = C_VarFacts.of(inited = inited.toMap())
    val exprCtx = entCtx.rootExprCtx
    val exprCtx2 = exprCtx.update(factsCtx = exprCtx.factsCtx.sub(varFacts))

    return Pair(exprCtx2, rExtParams.toList())
}

private fun compileParams(ctx: C_NamespaceContext, params: List<S_NameTypePair>): List<Pair<S_Name, R_Variable>> {
    val names = mutableSetOf<String>()

    val res = params.map { param ->
        val nameStr = param.name.str
        if (!names.add(nameStr)) {
            throw C_Error(param.name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        }
        val rType = param.compileType(ctx)
        Pair(param.name, R_Variable(nameStr, rType))
    }

    return res
}

private fun classNameToSqlTable(className: String) = className
