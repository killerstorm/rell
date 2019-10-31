package net.postchain.rell.parser

import net.postchain.rell.MutableTypedKeyMap
import net.postchain.rell.ThreadLocalContext
import net.postchain.rell.TypedKeyMap
import net.postchain.rell.model.*
import net.postchain.rell.module.RELL_VERSION_MODULE_SYSTEM
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
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
    override fun strLine() = "$file:$row"

    override fun equals(other: Any?): Boolean {
        return other is S_BasicPos && row == other.row && col == other.col && file == other.file
    }
}

abstract class S_Node {
    val attachment: Any? = getAttachment()

    companion object {
        private val ATTACHMENT_PROVIDER_LOCAL = ThreadLocalContext<Supplier<Any?>>(Supplier { null })

        @JvmStatic
        fun runWithAttachmentProvider(provider: Supplier<Any?>, code: Runnable) {
            ATTACHMENT_PROVIDER_LOCAL.set(provider) {
                code.run()
            }
        }

        private fun getAttachment(): Any? {
            val provider = ATTACHMENT_PROVIDER_LOCAL.get()
            val res = provider.get()
            return res
        }
    }
}

class S_PosValue<T>(val pos: S_Pos, val value: T) {
    constructor(t: RellTokenMatch, value: T): this(t.pos, value)
}

class S_Name(val pos: S_Pos, val str: String): S_Node() {
    override fun toString() = str

    fun toRName(): R_Name {
        val rName = R_Name.ofOpt(str)
        check(rName != null) { "Invalid name: '$str' ($pos)" }
        return rName
    }
}

class S_String(val pos: S_Pos, val str: String): S_Node() {
    constructor(name: S_Name): this(name.pos, name.str)
    override fun toString() = str
}

class S_NameTypePair(val name: S_Name, val type: S_Type?): S_Node() {
    fun compileType(ctx: C_NamespaceContext): R_Type? {
        if (type != null) {
            return type.compileOpt(ctx)
        }

        val rType = ctx.getTypeOpt(listOf(name))
        if (rType == null) {
            ctx.globalCtx.error(name.pos, "unknown_name_type:${name.str}",
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

sealed class S_Modifier(val pos: S_Pos) {
    abstract fun compile(ctx: C_ModifierContext, target: C_ModifierTarget)
}

class S_Annotation(val name: S_Name, val args: List<S_LiteralExpr>): S_Modifier(name.pos) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        val argValues = args.map { it.value() }
        C_Annotation.compile(ctx, name, argValues, target)
    }
}

class S_Modifiers(val modifiers: List<S_Modifier>) {
    fun compile(modifierCtx: C_ModifierContext, target: C_ModifierTarget) {
        for (modifier in modifiers) {
            modifier.compile(modifierCtx, target)
        }
    }

    fun compile(ctx: C_DefinitionContext, target: C_ModifierTarget) {
        val modifierCtx = C_ModifierContext(ctx.globalCtx, ctx.mountName)
        compile(modifierCtx, target)
    }
}

sealed class S_Definition(val modifiers: S_Modifiers): S_Node() {
    abstract fun compile(ctx: C_DefinitionContext)

    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)

    open fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
    }
}

class S_ClassDefinition(
        modifiers: S_Modifiers,
        val name: S_Name,
        val annotations: List<S_Name>,
        val body: List<S_RelClause>?
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        if (body == null) {
            compileHeader(ctx)
            return
        }

        val modTarget = C_ModifierTarget(C_DefType.CLASS, name, mount = true, log = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val extChain = ctx.extChain
        val extChainRef = extChain?.ref
        val external = extChainRef != null
        val rFlags = compileFlags(ctx, external, modTarget)

        if (external && ctx.mountName.isEmpty() && name.str in HEADER_CLASSES) {
            throw C_Error(name.pos, "def_class_external_unallowed:${name.str}",
                    "External class '${name.str}' can be declared only without body (as class header)")
        }

        if (external && !rFlags.log) {
            throw C_Error(name.pos, "def_class_external_nolog:${names.simpleName}",
                    "External class '${names.simpleName}' must have '${C_Constants.LOG_ANNOTATION}' annotation")
        }

        val mountName = ctx.mountName(modTarget, name)
        val rMapping = if (extChainRef == null) R_ClassSqlMapping_Regular(mountName) else R_ClassSqlMapping_External(mountName, extChainRef)

        val rExternalClass = if (extChainRef == null) null else R_ExternalClass(extChainRef, true)

        val rClass = R_Class(names, mountName, rFlags, rMapping, rExternalClass)

        ctx.appCtx.defsBuilder.classes.add(C_Class(name.pos, rClass))
        ctx.nsBuilder.addClass(name, rClass)
        ctx.mntBuilder.addClass(name.pos, rClass)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx, rClass, body)
        }
    }

    private fun compileHeader(ctx: C_DefinitionContext) {
        var err = false

        val modTarget = C_ModifierTarget(C_DefType.CLASS, name, mount = true, mountAllowed = false, log = true, logAllowed = false)
        modifiers.compile(ctx, modTarget)

        if (!annotations.isEmpty()) {
            ctx.globalCtx.error(name.pos, "def_class_hdr_annotations:${name.str}",
                    "Annotations not allowed for class header '${name.str}'")
            err = true
        }

        val clsGetter = HEADER_CLASSES[name.str]
        if (clsGetter == null) {
            val classes = HEADER_CLASSES.keys.joinToString()
            ctx.globalCtx.error(name.pos, "def_class_hdr_name:${name.str}",
                    "Class header declarations allowed only for classes: $classes")
            err = true
        }

        if (ctx.extChain == null) {
            ctx.globalCtx.error(name.pos, "def_class_hdr_noexternal:${name.str}",
                    "Class header must be declared in external block")
            return
        }

        if (err || clsGetter == null) {
            return
        }

        val rClass = clsGetter(ctx.extChain)
        ctx.nsBuilder.addClass(name, rClass, addToModule = false)
    }

    private fun compileFlags(ctx: C_DefinitionContext, external: Boolean, modTarget: C_ModifierTarget): R_ClassFlags {
        val set = mutableSetOf<String>()
        var log = modTarget.log?.get() ?: false

        if (log) {
            set.add(C_Constants.LOG_ANNOTATION)
        }

        for (ann in annotations) {
            ctx.globalCtx.warning(ann.pos, "ann:legacy:${ann.str}", "Deprecated annotation syntax; use @${ann.str} instead")

            val annStr = ann.str
            if (!set.add(annStr)) {
                ctx.globalCtx.error(ann.pos, "class_ann_dup:$annStr", "Duplicate annotation: '$annStr'")
            }

            if (annStr == C_Constants.LOG_ANNOTATION) {
                log = true
            } else {
                ctx.globalCtx.error(ann.pos, "class_ann_bad:$annStr", "Invalid annotation: '$annStr'")
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

    private fun membersPass(ctx: C_DefinitionContext, rClass: R_Class, clauses: List<S_RelClause>) {
        val entCtx = C_EntityContext(ctx.nsCtx, C_EntityType.CLASS, null, TypedKeyMap())
        val clsCtx = C_ClassContext(entCtx, name.str, rClass.flags.log)

        if (rClass.flags.log) {
            val txCls = if (ctx.extChain == null) ctx.modCtx.appCtx.sysDefs.transactionClass else ctx.extChain.transactionClass
            val txType = txCls.type
            clsCtx.addAttribute0("transaction", txType, false, false) {
                if (ctx.extChain == null) {
                    C_Ns_OpContext.transactionExpr(clsCtx.entCtx)
                } else {
                    C_Utils.crashExpr(txType, "Trying to initialize transaction for external class '${rClass.appLevelName}'")
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
        private val HEADER_CLASSES = mutableMapOf(
            C_Constants.BLOCK_CLASS to { c: C_ExternalChain -> c.blockClass },
            C_Constants.TRANSACTION_CLASS to { c: C_ExternalChain -> c.transactionClass }
        )

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

class S_ObjectDefinition(
        modifiers: S_Modifiers,
        val name: S_Name,
        val clauses: List<S_RelClause>
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(name.pos, C_DefType.OBJECT)

        val classFlags = R_ClassFlags(
                isObject = true,
                canCreate = false,
                canUpdate = true,
                canDelete = false,
                gtv = false,
                log = false
        )

        val modTarget = C_ModifierTarget(C_DefType.OBJECT, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)
        val sqlMapping = R_ClassSqlMapping_Regular(mountName)

        val rClass = R_Class(names, mountName, classFlags, sqlMapping, null)
        val rObject = R_Object(names, rClass)
        ctx.appCtx.defsBuilder.objects.add(rObject)
        ctx.nsBuilder.addObject(name, rObject)
        ctx.mntBuilder.addObject(name, rObject)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx.nsCtx, rObject)
        }
    }

    private fun membersPass(ctx: C_NamespaceContext, rObject: R_Object) {
        val entCtx = C_EntityContext(ctx, C_EntityType.OBJECT, null, TypedKeyMap())
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

class S_RecordDefinition(
        modifiers: S_Modifiers,
        val name: S_Name,
        val attrs: List<S_AttributeClause>
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(name.pos, C_DefType.RECORD)

        val modTarget = C_ModifierTarget(C_DefType.RECORD, name)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val rRecord = R_Record(names)
        ctx.appCtx.defsBuilder.records.add(rRecord)
        ctx.nsBuilder.addRecord(name, rRecord)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx.nsCtx, rRecord)
        }
    }

    private fun membersPass(ctx: C_NamespaceContext, rRecord: R_Record) {
        val entCtx = C_EntityContext(ctx, C_EntityType.RECORD, null, TypedKeyMap())
        val clsCtx = C_ClassContext(entCtx, name.str, false)
        for (clause in attrs) {
            clause.compileAttributes(clsCtx)
        }

        val attributes = clsCtx.createRecordBody()
        rRecord.setAttributes(attributes)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.RECORD)
        for (attr in attrs) {
            attr.ideBuildOutlineTree(sub)
        }
    }
}

class S_EnumDefinition(
        modifiers: S_Modifiers,
        val name: S_Name,
        val attrs: List<S_Name>
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(name.pos, C_DefType.ENUM)

        val modTarget = C_ModifierTarget(C_DefType.ENUM, name)
        modifiers.compile(ctx, modTarget)

        val set = mutableSetOf<String>()
        val rAttrs = mutableListOf<R_EnumAttr>()

        for (attr in attrs) {
            if (set.add(attr.str)) {
                rAttrs.add(R_EnumAttr(attr.str, rAttrs.size))
            } else {
                ctx.globalCtx.error(attr.pos, "enum_dup:${attr.str}", "Duplicate enum constant: '${attr.str}'")
            }
        }

        val names = ctx.nsCtx.defNames(name.str)
        val rEnum = R_Enum(names, rAttrs.toList())
        ctx.nsBuilder.addEnum(name, rEnum)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.ENUM)
        for (attr in attrs) {
            sub.node(attr, attr, IdeOutlineNodeType.ENUM_ATTRIBUTE)
        }
    }
}

class S_OperationDefinition(
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_NameTypePair>,
        val body: S_Statement
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(name.pos, C_DefType.OPERATION)

        val modTarget = C_ModifierTarget(C_DefType.OPERATION, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)

        val rOperation = R_Operation(names, mountName)
        ctx.appCtx.defsBuilder.operations.add(rOperation)
        ctx.nsBuilder.addOperation(name, rOperation)
        ctx.mntBuilder.addOperation(name, rOperation)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            doCompile(ctx.nsCtx, rOperation)
        }
    }

    private fun doCompile(ctx: C_NamespaceContext, rOperation: R_Operation) {
        val statementVars = processStatementVars()
        val entCtx = C_EntityContext(ctx, C_EntityType.OPERATION, null, statementVars)

        val (exprCtx, rParams) = compileExternalParams(ctx, entCtx, params)
        val rBody = body.compile(exprCtx).rStmt
        val rCallFrame = entCtx.makeCallFrame()

        if (ctx.globalCtx.compilerOptions.gtv) {
            checkGtvParams(params, rParams)
        }

        rOperation.setInternals(rParams, rBody, rCallFrame)
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
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(name.pos, C_DefType.QUERY)

        val modTarget = C_ModifierTarget(C_DefType.QUERY, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)

        val rQuery = R_Query(names, mountName)
        ctx.appCtx.defsBuilder.queries.add(rQuery)
        ctx.nsBuilder.addQuery(name, rQuery)
        ctx.mntBuilder.addQuery(name, rQuery)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            doCompile(ctx.nsCtx, rQuery)
        }
    }

    private fun doCompile(ctx: C_NamespaceContext, rQuery: R_Query) {
        val rExplicitRetType = retType?.compile(ctx)
        val statementVars = body.processStatementVars()
        val entCtx = C_EntityContext(ctx, C_EntityType.QUERY, rExplicitRetType, statementVars)

        val (exprCtx, rParams) = compileExternalParams(ctx, entCtx, params)
        val rBody = body.compileQuery(name, exprCtx)
        val rCallFrame = entCtx.makeCallFrame()
        val rRetType = entCtx.actualReturnType()

        if (ctx.globalCtx.compilerOptions.gtv) {
            checkGtvParams(params, rParams)
            checkGtvResult(rRetType)
        }

        rQuery.setInternals(rRetType, rParams, rBody, rCallFrame)
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
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(name.pos, C_DefType.FUNCTION)

        val modTarget = C_ModifierTarget(C_DefType.FUNCTION, name)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val rFn = R_Function(names)
        ctx.nsBuilder.addFunction(name, rFn)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            compileDefinition(ctx.nsCtx, rFn)
        }
    }

    private fun compileDefinition(ctx: C_NamespaceContext, rFn: R_Function) {
        val rRetType = if (retType != null) retType.compile(ctx) else R_UnitType
        val statementVars = body.processStatementVars()
        val entCtx = C_EntityContext(ctx, C_EntityType.FUNCTION, rRetType, statementVars)

        val (exprCtx, rParams) = compileExternalParams(ctx, entCtx, params)
        rFn.setHeader(rRetType, rParams)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            compileFinish(entCtx, exprCtx, rFn)
        }
    }

    private fun compileFinish(entCtx: C_EntityContext, exprCtx: C_ExprContext, rFn: R_Function) {
        val rBody = body.compileFunction(name, exprCtx)
        val rCallFrame = entCtx.makeCallFrame()
        rFn.setBody(rBody, rCallFrame)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.FUNCTION)
    }
}

class S_NamespaceDefinition(
        modifiers: S_Modifiers,
        val name: S_Name?,
        val definitions: List<S_Definition>
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        val modTarget = C_ModifierTarget(C_DefType.NAMESPACE, name, mount = true, emptyMountAllowed = true)
        modifiers.compile(ctx, modTarget)

        val subDefCtx = createSubDefCtx(ctx, modTarget)
        for (def in definitions) {
            def.compile(subDefCtx)
        }
    }

    private fun createSubDefCtx(ctx: C_DefinitionContext, modTarget: C_ModifierTarget): C_DefinitionContext {
        if (name == null) {
            val subMountName = modTarget.mount?.get() ?: ctx.mountName
            return C_DefinitionContext(ctx.fileCtx, ctx.nsCtx, ctx.extChain, ctx.nsBuilder, subMountName)
        }

        val (subNsBuilder, subNsGetter) = ctx.nsBuilder.addNamespace(name)

        val names = ctx.nsCtx.defNames(name.str)
        val subNsCtx = C_NamespaceContext(ctx.modCtx, ctx.nsCtx, names.moduleLevelName, subNsGetter)

        val subMountName = ctx.mountName(modTarget, name)
        return C_DefinitionContext(ctx.fileCtx, subNsCtx, ctx.extChain, subNsBuilder, subMountName)
    }

    override fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.getImportedModules(moduleName, res)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = if (name == null) b else b.node(this, name, IdeOutlineNodeType.NAMESPACE)
        for (def in definitions) {
            def.ideBuildOutlineTree(sub)
        }
    }
}

class S_ExternalDefinition(
        modifiers: S_Modifiers,
        val pos: S_Pos,
        val name: S_String,
        val definitions: List<S_Definition>
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        ctx.checkNotExternal(pos, C_DefType.EXTERNAL)

        val modTarget = C_ModifierTarget(C_DefType.EXTERNAL, null, mount = true, emptyMountAllowed = true)
        modifiers.compile(ctx, modTarget)

        if (name.str.isEmpty()) {
            ctx.globalCtx.error(name.pos, "def_external_invalid:$name", "Invalid chain name: '$name'")
            return
        }

        val mountName = modTarget.mount?.get() ?: R_MountName.EMPTY

        val extChain = ctx.appCtx.addExternalChain(name)
        val subCtx = C_DefinitionContext(ctx.fileCtx, ctx.nsCtx, extChain, ctx.nsBuilder, mountName)

        for (def in definitions) {
            def.compile(subCtx)
        }
    }

    override fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.getImportedModules(moduleName, res)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (def in definitions) {
            def.ideBuildOutlineTree(b)
        }
    }
}

class S_RelativeImportPath(val pos: S_Pos, val ups: Int)

class S_ImportPath(val relative: S_RelativeImportPath?, val path: List<S_Name>) {
    fun compile(importPos: S_Pos, currentModule: R_ModuleName): R_ModuleName {
        val rPath = path.map { it.toRName() }

        if (relative == null) {
            if (path.isEmpty()) {
                throw C_Error(importPos, "import:no_path", "Module not specified")
            }
            return R_ModuleName(rPath)
        }

        if (relative.ups > currentModule.parts.size) {
            throw C_Error(relative.pos, "import:up:${currentModule.parts.size}:${relative.ups}",
                    "Cannot go up by ${relative.ups}, current module is '${currentModule}'")
        }

        val base = currentModule.parts.subList(0, currentModule.parts.size - relative.ups)
        val full = base + rPath
        return R_ModuleName(full)
    }

    fun getAlias(): S_Name? {
        return if (path.isEmpty()) null else path[path.size - 1]
    }
}

class S_ImportDefinition(
        modifiers: S_Modifiers,
        val pos: S_Pos,
        val alias: S_Name?,
        val modulePath: S_ImportPath
): S_Definition(modifiers) {
    override fun compile(ctx: C_DefinitionContext) {
        val moduleName = try {
            modulePath.compile(pos, ctx.modCtx.module.name)
        } catch (e: C_Error) {
            ctx.globalCtx.error(e)
            return
        }

        val modTarget = C_ModifierTarget(C_DefType.IMPORT, null)
        modifiers.compile(ctx, modTarget)

        val module = try {
            ctx.modCtx.modMgr.linkModule(moduleName)
        } catch (e: C_CommonError) {
            ctx.globalCtx.error(pos, e.code, e.msg)
            return
        }

        val actualAlias = getActualAlias()
        if (actualAlias == null) {
            ctx.globalCtx.error(pos, "import:no_alias", "Cannot infer an alias, specify import alias explicitly")
            return
        }

        ctx.nsBuilder.addImport(actualAlias, module)
    }

    override fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        val impModuleName = try {
            modulePath.compile(pos, moduleName)
        } catch (e: C_Error) {
            return
        }
        res.add(impModuleName)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val alias = getActualAlias()
        if (alias != null) {
            b.node(this, alias, IdeOutlineNodeType.IMPORT)
        }
    }

    private fun getActualAlias() = alias ?: modulePath.getAlias()
}

class S_IncludeDefinition(val pos: S_Pos): S_Definition(S_Modifiers(listOf())) {
    override fun compile(ctx: C_DefinitionContext) {
        val globalCtx = ctx.globalCtx
        globalCtx.error(pos, "include", "Include not supported since Rell $RELL_VERSION_MODULE_SYSTEM")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        // Do nothing.
    }
}

class S_ModuleHeader(val modifiers: S_Modifiers, val pos: S_Pos) {
    fun compile(globalCtx: C_GlobalContext, parentMountName: R_MountName): R_MountName? {
        val modifierCtx = C_ModifierContext(globalCtx, parentMountName)
        val modTarget = C_ModifierTarget(C_DefType.MODULE, null, mount = true, emptyMountAllowed = true)
        modifiers.compile(modifierCtx, modTarget)
        return modTarget.mount?.get()
    }
}

class S_RellFile(val header: S_ModuleHeader?, val definitions: List<S_Definition>): S_Node() {
    fun compileHeader(globalCtx: C_GlobalContext, parentMountName: R_MountName): R_MountName {
        val res = header?.compile(globalCtx, parentMountName)
        return res ?: parentMountName
    }

    fun compile(modCtx: C_ModuleContext): C_CompiledRellFile {
        modCtx.executor.checkPass(C_CompilerPass.DEFINITIONS)

        val mountName = modCtx.module.mountName()

        val fileCtx = C_FileContext(modCtx)

        val nsBuilder = C_UserNsProtoBuilder()
        val nsLate = C_LateInit(C_CompilerPass.NAMESPACES, C_Namespace.EMPTY)

        val nsCtx = C_NamespaceContext(modCtx, modCtx.rootNsCtx, null, nsLate.getter)
        val defCtx = C_DefinitionContext(fileCtx, nsCtx, null, nsBuilder, mountName)

        compileDefs(defCtx)

        val nsProto = nsBuilder.build()
        val mntTables = fileCtx.mntBuilder.build()

        return C_CompiledRellFile(nsProto, mntTables, nsLate.setter)
    }

    fun compileDefs(defCtx: C_DefinitionContext) {
        for (def in definitions) {
            defCtx.modCtx.globalCtx.consumeError {
                def.compile(defCtx)
            }
        }
    }

    fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.getImportedModules(moduleName, res)
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
    val res = mutableListOf<Pair<S_Name, R_Variable>>()

    for (param in params) {
        val nameStr = param.name.str
        val rType = param.compileType(ctx)
        if (rType == null) continue
        if (names.add(nameStr)) {
            res.add(Pair(param.name, R_Variable(nameStr, rType)))
        } else {
            ctx.globalCtx.error(param.name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        }
    }

    return res
}
