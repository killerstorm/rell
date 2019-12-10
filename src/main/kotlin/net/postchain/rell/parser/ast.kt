package net.postchain.rell.parser

import net.postchain.rell.MutableTypedKeyMap
import net.postchain.rell.ThreadLocalContext
import net.postchain.rell.TypedKeyMap
import net.postchain.rell.model.*
import net.postchain.rell.module.RELL_VERSION_MODULE_SYSTEM
import net.postchain.rell.toImmList
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import java.util.function.Supplier
import kotlin.math.min

abstract class S_Pos {
    abstract fun path(): C_SourcePath
    abstract fun line(): Int
    abstract fun pos(): Long
    abstract fun str(): String
    abstract fun strLine(): String
    override fun toString() = str()

    fun toFilePos() = R_FilePos(path().str(), line())
}

class S_BasicPos(private val file: C_SourcePath, private val row: Int, private val col: Int): S_Pos() {
    override fun path() = file
    override fun line() = row
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
    constructor(t: RellTokenMatch): this(t.pos, t.text)
    override fun toString() = str
}

sealed class S_AttrHeader(val name: S_Name): S_Node() {
    abstract fun hasExplicitType(): Boolean
    abstract fun compileType(ctx: C_NamespaceContext): R_Type

    fun compileTypeOpt(ctx: C_NamespaceContext): R_Type? {
        return ctx.globalCtx.consumeError { compileType(ctx) }
    }
}

class S_NameTypeAttrHeader(name: S_Name, private val type: S_Type): S_AttrHeader(name) {
    override fun hasExplicitType() = true
    override fun compileType(ctx: C_NamespaceContext) = type.compile(ctx)
}

class S_NameAttrHeader(name: S_Name): S_AttrHeader(name) {
    override fun hasExplicitType() = false

    override fun compileType(ctx: C_NamespaceContext): R_Type {
        val rType = ctx.getTypeOpt(listOf(name))
        if (rType == null) {
            throw C_Error(name.pos, "unknown_name_type:${name.str}",
                    "Cannot infer type for '${name.str}'; specify type explicitly")
        }
        return rType
    }
}

sealed class S_RelClause: S_Node() {
    abstract fun compileAttributes(ctx: C_EntityContext)
    abstract fun compileRest(ctx: C_EntityContext)
    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)
}

class S_AttributeClause(val attr: S_AttrHeader, val mutable: Boolean, val expr: S_Expr?): S_RelClause() {
    override fun compileAttributes(ctx: C_EntityContext) {
        ctx.addAttribute(attr, mutable, expr)
    }

    override fun compileRest(ctx: C_EntityContext) {}

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, attr.name, IdeOutlineNodeType.ATTRIBUTE)
    }
}

sealed class S_KeyIndexClause(val pos: S_Pos, val attrs: List<S_AttrHeader>): S_RelClause() {
    final override fun compileAttributes(ctx: C_EntityContext) {}

    abstract fun addToContext(ctx: C_EntityContext, pos: S_Pos, names: List<S_Name>)

    final override fun compileRest(ctx: C_EntityContext) {
        val names = mutableSetOf<String>()
        for (attr in attrs) {
            val name = attr.name
            if (!names.add(name.str)) {
                throw C_Error(name.pos, "entity_keyindex_dup:${name.str}",
                        "Duplicate attribute: '${name.str}'")
            }
        }

        for (attr in attrs) {
            val name = attr.name
            if (ctx.hasAttribute(name.str)) {
                if (attr.hasExplicitType()) {
                    throw C_Error(name.pos, "entity_keyindex_def:${name.str}",
                            "Attribute '${name.str}' is defined elsewhere, cannot specify type")
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

class S_KeyClause(pos: S_Pos, attrs: List<S_AttrHeader>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: C_EntityContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addKey(pos, names)
    }
}

class S_IndexClause(pos: S_Pos, attrs: List<S_AttrHeader>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: C_EntityContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addIndex(pos, names)
    }
}

sealed class S_Modifier {
    abstract fun compile(ctx: C_ModifierContext, target: C_ModifierTarget)
}

sealed class S_KeywordModifier(protected val kw: S_String): S_Modifier()

class S_KeywordModifier_Abstract(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.abstract, true)
    }
}

class S_KeywordModifier_Override(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.override, true)
    }
}

class S_Annotation(val name: S_Name, val args: List<S_LiteralExpr>): S_Modifier() {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        val argValues = args.map { it.value() }
        C_Modifier.compileAnnotation(ctx, name, argValues, target)
    }
}

class S_Modifiers(val modifiers: List<S_Modifier>) {
    fun compile(modifierCtx: C_ModifierContext, target: C_ModifierTarget) {
        for (modifier in modifiers) {
            modifier.compile(modifierCtx, target)
        }
    }

    fun compile(ctx: C_MountContext, target: C_ModifierTarget) {
        val modifierCtx = C_ModifierContext(ctx.globalCtx, ctx.mountName)
        compile(modifierCtx, target)
    }
}

sealed class S_Definition(val modifiers: S_Modifiers): S_Node() {
    abstract fun compile(ctx: C_MountContext)

    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)

    open fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
    }
}

class S_EntityDefinition(
        modifiers: S_Modifiers,
        val deprecatedKwPos: S_Pos?,
        val name: S_Name,
        val annotations: List<S_Name>,
        val body: List<S_RelClause>?
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        if (deprecatedKwPos != null) {
            ctx.globalCtx.error(deprecatedKwPos, "deprecated_kw:class:entity",
                    "Keyword 'class' is deprecated, use 'entity' instead")
        }

        if (body == null) {
            compileHeader(ctx)
            return
        }

        val modTarget = C_ModifierTarget(C_ModifierTargetType.ENTITY, name, externalChain = true, mount = true, log = true)
        modifiers.compile(ctx, modTarget)

        val extChain = modTarget.externalChain(ctx)
        val extChainRef = extChain?.ref
        val external = extChainRef != null || ctx.modCtx.external
        val rFlags = compileFlags(ctx, external, modTarget)

        val names = ctx.nsCtx.defNames(name.str, extChain)

        if (external && ctx.mountName.isEmpty() && name.str in HEADER_ENTITIES) {
            throw C_Error(name.pos, "def_entity_external_unallowed:${name.str}",
                    "External entity '${name.str}' can be declared only without body (as entity header)")
        }

        if (external && !rFlags.log) {
            throw C_Error(name.pos, "def_entity_external_nolog:${names.simpleName}",
                    "External entity '${names.simpleName}' must have '${C_Constants.LOG_ANNOTATION}' annotation")
        }

        val mountName = ctx.mountName(modTarget, name)
        val rMapping = if (extChainRef == null) {
            R_EntitySqlMapping_Regular(mountName)
        } else {
            R_EntitySqlMapping_External(mountName, extChainRef)
        }

        val rExternalEntity = if (extChainRef == null) null else R_ExternalEntity(extChainRef, true)

        val rEntity = R_Entity(names, mountName, rFlags, rMapping, rExternalEntity)

        ctx.appCtx.defsBuilder.entities.add(C_Entity(name.pos, rEntity))
        ctx.nsBuilder.addEntity(name, rEntity)
        ctx.mntBuilder.addEntity(name.pos, rEntity)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx, extChain, rEntity, body)
        }
    }

    private fun compileHeader(ctx: C_MountContext) {
        var err = false

        val modTarget = C_ModifierTarget(
                C_ModifierTargetType.ENTITY,
                name,
                externalChain = true,
                mount = true,
                mountAllowed = false,
                log = true,
                logAllowed = false
        )
        modifiers.compile(ctx, modTarget)

        if (!annotations.isEmpty()) {
            ctx.globalCtx.error(name.pos, "def_entity_hdr_annotations:${name.str}",
                    "Annotations not allowed for entity header '${name.str}'")
            err = true
        }

        val entGetter = HEADER_ENTITIES[name.str]
        if (entGetter == null) {
            val entities = HEADER_ENTITIES.keys.joinToString()
            ctx.globalCtx.error(name.pos, "def_entity_hdr_name:${name.str}",
                    "Entity header declarations allowed only for entities: $entities")
            err = true
        }

        val extChain = modTarget.externalChain(ctx)
        if (extChain == null && !ctx.modCtx.external) {
            ctx.globalCtx.error(name.pos, "def_entity_hdr_noexternal:${name.str}",
                    "Entity header must be declared as external")
            return
        }

        if (err || entGetter == null) {
            return
        }

        val sysDefs = extChain?.sysDefs ?: ctx.modCtx.sysDefs
        val rEntity = entGetter(sysDefs)
        ctx.nsBuilder.addEntity(name, rEntity, addToModule = false)
    }

    private fun compileFlags(ctx: C_MountContext, external: Boolean, modTarget: C_ModifierTarget): R_EntityFlags {
        val set = mutableSetOf<String>()
        var log = modTarget.log?.get() ?: false

        if (log) {
            set.add(C_Constants.LOG_ANNOTATION)
        }

        for (ann in annotations) {
            ctx.globalCtx.warning(ann.pos, "ann:legacy:${ann.str}", "Deprecated annotation syntax; use @${ann.str} instead")

            val annStr = ann.str
            if (!set.add(annStr)) {
                ctx.globalCtx.error(ann.pos, "entity_ann_dup:$annStr", "Duplicate annotation: '$annStr'")
            }

            if (annStr == C_Constants.LOG_ANNOTATION) {
                log = true
            } else {
                ctx.globalCtx.error(ann.pos, "entity_ann_bad:$annStr", "Invalid annotation: '$annStr'")
            }
        }

        return R_EntityFlags(
                isObject = false,
                canCreate = !external,
                canUpdate = !external,
                canDelete = !log && !external,
                gtv = true,
                log = log
        )
    }

    private fun membersPass(ctx: C_MountContext, extChain: C_ExternalChain?, rEntity: R_Entity, clauses: List<S_RelClause>) {
        val defCtx = C_DefinitionContext(ctx.nsCtx, C_DefinitionType.ENTITY, null, TypedKeyMap())
        val entCtx = C_EntityContext(defCtx, name.str, rEntity.flags.log)

        if (rEntity.flags.log) {
            val sysDefs = extChain?.sysDefs ?: ctx.modCtx.sysDefs
            val txType = sysDefs.transactionEntity.type
            entCtx.addAttribute0("transaction", txType, false, false) {
                if (extChain == null) {
                    C_Ns_OpContext.transactionExpr(entCtx.defCtx, name.pos)
                } else {
                    C_Utils.crashExpr(txType, "Trying to initialize transaction for external entity '${rEntity.appLevelName}'")
                }
            }
        }

        compileClauses(entCtx, clauses)

        val body = entCtx.createEntityBody()
        rEntity.setBody(body)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.ENTITY)
        for (clause in body ?: listOf()) {
            clause.ideBuildOutlineTree(sub)
        }
    }

    companion object {
        private val HEADER_ENTITIES = mutableMapOf(
            C_Constants.BLOCK_ENTITY to { sysDefs: C_SystemDefs -> sysDefs.blockEntity },
            C_Constants.TRANSACTION_ENTITY to { sysDefs: C_SystemDefs -> sysDefs.transactionEntity }
        )

        fun compileClauses(entCtx: C_EntityContext, clauses: List<S_RelClause>) {
            for (clause in clauses) {
                clause.compileAttributes(entCtx)
            }
            for (clause in clauses) {
                clause.compileRest(entCtx)
            }
        }
    }
}

class S_ObjectDefinition(
        modifiers: S_Modifiers,
        val name: S_Name,
        val clauses: List<S_RelClause>
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.OBJECT)

        val entityFlags = R_EntityFlags(
                isObject = true,
                canCreate = false,
                canUpdate = true,
                canDelete = false,
                gtv = false,
                log = false
        )

        val modTarget = C_ModifierTarget(C_ModifierTargetType.OBJECT, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)
        val sqlMapping = R_EntitySqlMapping_Regular(mountName)

        val rEntity = R_Entity(names, mountName, entityFlags, sqlMapping, null)
        val rObject = R_Object(names, rEntity)
        ctx.appCtx.defsBuilder.objects.add(rObject)
        ctx.nsBuilder.addObject(name, rObject)
        ctx.mntBuilder.addObject(name, rObject)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx.nsCtx, rObject)
        }
    }

    private fun membersPass(ctx: C_NamespaceContext, rObject: R_Object) {
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OBJECT, null, TypedKeyMap())
        val entCtx = C_EntityContext(defCtx, name.str, false)
        S_EntityDefinition.compileClauses(entCtx, clauses)

        val body = entCtx.createEntityBody()
        rObject.rEntity.setBody(body)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.OBJECT)
        for (clause in clauses) {
            clause.ideBuildOutlineTree(sub)
        }
    }
}

class S_StructDefinition(
        modifiers: S_Modifiers,
        val deprecatedKwPos: S_Pos?,
        val name: S_Name,
        val attrs: List<S_AttributeClause>
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        if (deprecatedKwPos != null) {
            ctx.globalCtx.error(deprecatedKwPos, "deprecated_kw:record:struct",
                    "Keyword 'record' is deprecated, use 'struct' instead")
        }

        ctx.checkNotExternal(name.pos, C_DeclarationType.STRUCT)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.STRUCT, name)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val rStruct = R_Struct(names)
        ctx.appCtx.defsBuilder.structs.add(rStruct)
        ctx.nsBuilder.addStruct(name, rStruct)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx.nsCtx, rStruct)
        }
    }

    private fun membersPass(ctx: C_NamespaceContext, rStruct: R_Struct) {
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.STRUCT, null, TypedKeyMap())
        val entCtx = C_EntityContext(defCtx, name.str, false)
        for (clause in attrs) {
            clause.compileAttributes(entCtx)
        }

        val attributes = entCtx.createStructBody()
        rStruct.setAttributes(attributes)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.STRUCT)
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
    override fun compile(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.ENUM)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.ENUM, name)
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
        val params: List<S_AttrHeader>,
        val body: S_Statement
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.OPERATION)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.OPERATION, name, mount = true)
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
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OPERATION, null, statementVars)

        val extParams = compileExternalParams(ctx, defCtx, params, true)

        val rBody = body.compile(extParams.exprCtx).rStmt
        val rCallFrame = defCtx.makeCallFrame()

        rOperation.setInternals(extParams.rParams, rBody, rCallFrame)
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
        val params: List<S_AttrHeader>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.QUERY)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.QUERY, name, mount = true)
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
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.QUERY, rExplicitRetType, statementVars)

        val extParams = compileExternalParams(ctx, defCtx, params, true)

        val rBody = body.compileQuery(name, extParams.exprCtx)
        val rCallFrame = defCtx.makeCallFrame()
        val rRetType = defCtx.actualReturnType()

        if (ctx.globalCtx.compilerOptions.gtv) {
            checkGtvResult(ctx, rRetType)
        }

        val rQueryBody = R_UserQueryBody(extParams.rParams, rBody, rCallFrame)
        rQuery.setInternals(rRetType, rQueryBody)
    }

    private fun checkGtvResult(ctx: C_NamespaceContext, rType: R_Type) {
        checkGtvCompatibility(ctx, name.pos, rType, false, "result_nogtv:${name.str}", "Return type of query '${name.str}'")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.QUERY)
    }
}

private fun checkGtvCompatibility(
        ctx: C_NamespaceContext,
        pos: S_Pos,
        type: R_Type,
        from: Boolean,
        errCode: String,
        errMsg: String
) {
    val flags = type.completeFlags()
    val flag = if (from) flags.gtv.fromGtv else flags.gtv.toGtv
    if (!flag) {
        val fullMsg = "$errMsg is not Gtv-compatible: ${type.toStrictString()}"
        ctx.globalCtx.error(pos, "$errCode:${type.toStrictString()}", fullMsg)
    }
}

abstract class S_FunctionBody {
    abstract fun processStatementVars(): TypedKeyMap
    abstract fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement
    abstract fun compileFunction(qualifiedName: List<S_Name>, ctx: C_ExprContext): R_Statement
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun processStatementVars() = TypedKeyMap()

    override fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement {
        val cExpr = expr.compile(ctx)
        val rExpr = cExpr.value().toRExpr()
        C_Utils.checkUnitType(name.pos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        ctx.blkCtx.defCtx.matchReturnType(name.pos, rExpr.type)
        return R_ReturnStatement(rExpr)
    }

    override fun compileFunction(qualifiedName: List<S_Name>, ctx: C_ExprContext): R_Statement {
        val name = C_Utils.namePosStr(qualifiedName)

        val rExpr = expr.compile(ctx).value().toRExpr()
        ctx.blkCtx.defCtx.matchReturnType(name.pos, rExpr.type)

        return if (rExpr.type != R_UnitType) {
            R_ReturnStatement(rExpr)
        } else {
            R_ExprStatement(rExpr)
        }
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

    override fun compileFunction(qualifiedName: List<S_Name>, ctx: C_ExprContext): R_Statement {
        val cBody = body.compile(ctx)

        val retType = ctx.blkCtx.defCtx.actualReturnType()
        if (retType != R_UnitType) {
            val name = C_Utils.namePosStr(qualifiedName)
            if (!cBody.returnAlways) {
                throw C_Error(name.pos, "fun_noreturn:${name.str}", "Function '${name.str}': not all code paths return value")
            }
        }

        return cBody.rStmt
    }
}

class S_FunctionDefinition(
        modifiers: S_Modifiers,
        val qualifiedName: List<S_Name>,
        val params: List<S_AttrHeader>,
        val retType: S_Type?,
        val body: S_FunctionBody?
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        val name = qualifiedName.last()
        ctx.checkNotExternal(name.pos, C_DeclarationType.FUNCTION)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.FUNCTION, name, abstract = true, override = true)
        modifiers.compile(ctx, modTarget)

        val abstract = modTarget.abstract?.get() ?: false
        val override = modTarget.override?.get() ?: false
        val qName = C_Utils.nameStr(qualifiedName)

        if (qualifiedName.size >= 2 && !override) {
            ctx.globalCtx.error(qualifiedName[0].pos, "fn:qname_no_override:$qName",
                    "Invalid function name: '$qName' (qualified names allowed only for override)")
        }

        if (abstract && override) {
            ctx.globalCtx.error(qualifiedName[0].pos, "fn:abstract_override:$qName", "Both 'abstract' and 'override' specified")
        }

        if (!abstract && body == null) {
            ctx.globalCtx.error(name.pos, "fn:no_body:$qName", "Function '$qName' must have a body (it is not abstract)")
        }

        if (abstract) {
            compileAbstract(ctx, name)
        } else if (override) {
            compileOverride(ctx)
        } else {
            compileRegular(ctx, name)
        }
    }

    private fun compileRegular(ctx: C_MountContext, name: S_Name) {
        val rFn = compileDefinition0(ctx)
        val cFn = C_UserGlobalFunction(rFn, null)
        ctx.nsBuilder.addFunction(name, cFn)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val cHeader = compileRegularHeader(ctx.nsCtx, cFn)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileRegularBody(cHeader, rFn)
            }
        }
    }

    private fun definitionNames(ctx: C_MountContext): R_DefinitionNames {
        val qName = qualifiedName.map { it.str }
        return ctx.nsCtx.defNames(qName)
    }

    private fun compileDefinition0(ctx: C_MountContext): R_Function {
        val names = definitionNames(ctx)
        return R_Function(names)
    }

    private fun compileRegularHeader(ctx: C_NamespaceContext, cFn: C_UserGlobalFunction): C_FnHeader {
        val h = compileHeader0(ctx)
        cFn.setHeader(h.retType, h.cParams)
        return h
    }

    private fun compileHeader0(ctx: C_NamespaceContext): C_FnHeader {
        val rRetType = if (retType != null) retType.compile(ctx) else R_UnitType
        val statementVars = body?.processStatementVars() ?: TypedKeyMap()
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.FUNCTION, rRetType, statementVars)
        val extParams = compileExternalParams(ctx, defCtx, params, false)
        return C_FnHeader(rRetType, extParams.exprCtx, extParams.rParams, extParams.cParams)
    }

    private fun compileRegularBody(cHeader: C_FnHeader, rFn: R_Function) {
        val exprCtx = cHeader.bodyExprCtx
        val rBody = body?.compileFunction(qualifiedName, exprCtx) ?: C_Utils.ERROR_STATEMENT
        rFn.setBody(createFunctionBody(rFn.pos, cHeader, rBody))
    }

    private fun compileAbstract(ctx: C_MountContext, name: S_Name) {
        if (ctx.modCtx.module.header().abstract == null) {
            val mName = ctx.modCtx.module.name.str()
            val qName = C_Utils.nameStr(qualifiedName)
            ctx.globalCtx.error(qualifiedName[0].pos, "fn:abstract:non_abstract_module:$mName:$qName",
                    "Abstract function can be defined only in abstract module")
        }

        val rFn = compileDefinition0(ctx)

        val descriptor = C_AbstractDescriptor(name.pos, rFn, body != null)
        ctx.fileCtx.addAbstractFunction(descriptor)

        val cFn = C_UserGlobalFunction(rFn, descriptor)
        ctx.nsBuilder.addFunction(name, cFn)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val cHeader = compileRegularHeader(ctx.nsCtx, cFn)
            if (body != null) {
                ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                    compileAbstractBody(cHeader, rFn, descriptor, body)
                }
            }
        }
    }

    private fun compileAbstractBody(
            cHeader: C_FnHeader,
            rFn: R_Function,
            descriptor: C_AbstractDescriptor,
            realBody: S_FunctionBody
    ) {
        val exprCtx = cHeader.bodyExprCtx
        val rBody = realBody.compileFunction(qualifiedName, exprCtx)
        if (descriptor.isUsingDefaultBody()) {
            rFn.setBody(createFunctionBody(rFn.pos, cHeader, rBody))
        }
    }

    private fun compileOverride(ctx: C_MountContext) {
        val descriptor = C_OverrideDescriptor(qualifiedName[0].pos)
        ctx.fileCtx.addOverrideFunction(descriptor)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            compileOverrideHeader(ctx, descriptor)
        }
    }

    private fun compileOverrideHeader(ctx: C_MountContext, overDescriptor: C_OverrideDescriptor) {
        val fn = ctx.nsCtx.getFunctionOpt(qualifiedName)
        if (fn == null) {
            val qName = C_Utils.nameStr(qualifiedName)
            ctx.globalCtx.error(qualifiedName[0].pos, "fn:override:not_found:$qName", "Function not found: '$qName'")
        }

        val absDescriptor = if (fn == null) null else {
            val (def, desc) = fn.getAbstractInfo()
            if (desc == null) {
                val qName = def?.appLevelName ?: C_Utils.nameStr(qualifiedName)
                ctx.globalCtx.error(qualifiedName[0].pos, "fn:override:not_abstract:[$qName]", "Function is not abstract: '$qName'")
            }
            desc
        }

        val cHeader = compileHeader0(ctx.nsCtx)
        overDescriptor.setAbstract(absDescriptor)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            compileOverrideBody(ctx, cHeader, absDescriptor, overDescriptor)
        }
    }

    private fun compileOverrideBody(
            ctx: C_MountContext,
            cHeader: C_FnHeader,
            absDescriptor: C_AbstractDescriptor?,
            overDescriptor: C_OverrideDescriptor
    ) {
        if (absDescriptor != null) {
            checkOverrideSignature(ctx, absDescriptor, cHeader)
        }

        val names = definitionNames(ctx)
        val defPos = names.pos()

        val exprCtx = cHeader.bodyExprCtx
        val rBody = body?.compileFunction(qualifiedName, exprCtx) ?: C_Utils.ERROR_STATEMENT
        overDescriptor.setBody(createFunctionBody(defPos, cHeader, rBody))
    }

    private fun checkOverrideSignature(ctx: C_MountContext, abstractDescriptor: C_AbstractDescriptor, cHeader: C_FnHeader) {
        val overPos = qualifiedName[0].pos
        val absHeader = abstractDescriptor.header()

        S_Type.matchOpt(ctx.globalCtx, absHeader.retType, cHeader.retType, overPos,
                "fn:override:ret_type", "Return type missmatch")

        val absParams = absHeader.cParams
        val overParams = cHeader.cParams

        if (absParams.size != overParams.size) {
            ctx.globalCtx.error(overPos, "fn:override:param_cnt:${absParams.size}:${overParams.size}",
                    "Wrong number of parameters: ${overParams.size} instead of ${absParams.size}")
        }

        for (i in 0 until min(overParams.size, absParams.size)) {
            val absParam = absParams[i]
            val overParam = overParams[i]
            if (absParam.type != null && overParam.type != null && overParam.type != absParam.type) {
                val code = "fn:override:param_type:$i:${absParam.name.str}"
                val msg = "Parameter '${absParam.name.str}' (${i+1}) type missmatch "
                val err = C_Errors.errTypeMismatch(overParam.name.pos, overParam.type, absParam.type, code, msg)
                ctx.globalCtx.error(err)
            }
        }
    }

    private fun createFunctionBody(defPos: R_DefinitionPos, cHeader: C_FnHeader, rBody: R_Statement): R_FunctionBody {
        val rCallFrame = cHeader.bodyExprCtx.defCtx.makeCallFrame()
        return R_FunctionBody(defPos, cHeader.retType, cHeader.rParams, rBody, rCallFrame)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val name = qualifiedName.last()
        b.node(this, name, IdeOutlineNodeType.FUNCTION)
    }

    private class C_FnHeader(
            val retType: R_Type,
            val bodyExprCtx: C_ExprContext,
            val rParams: List<R_VarParam>,
            val cParams: List<C_ExternalParam>
    )
}

class S_NamespaceDefinition(
        modifiers: S_Modifiers,
        val name: S_Name?,
        val definitions: List<S_Definition>
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        val modTarget = C_ModifierTarget(C_ModifierTargetType.NAMESPACE, name,
                externalChain = true, mount = true, emptyMountAllowed = true)
        modifiers.compile(ctx, modTarget)

        val subCtx = createSubMountContext(ctx, modTarget)
        for (def in definitions) {
            def.compile(subCtx)
        }
    }

    private fun createSubMountContext(ctx: C_MountContext, modTarget: C_ModifierTarget): C_MountContext {
        val extChain = modTarget.externalChain(ctx)

        if (name == null) {
            val subMountName = modTarget.mount?.get() ?: ctx.mountName
            return C_MountContext(ctx.fileCtx, ctx.nsCtx, extChain, ctx.nsBuilder, subMountName)
        }

        val (subNsBuilder, subNsGetter) = ctx.nsBuilder.addNamespace(name)

        val names = ctx.nsCtx.defNames(name.str)
        val subNsCtx = C_NamespaceContext(ctx.modCtx, ctx.nsCtx, names.qualifiedName, subNsGetter)

        val subMountName = ctx.mountName(modTarget, name)
        return C_MountContext(ctx.fileCtx, subNsCtx, extChain, subNsBuilder, subMountName)
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
    override fun compile(ctx: C_MountContext) {
        val moduleName = ctx.globalCtx.consumeError { modulePath.compile(pos, ctx.modCtx.module.name) }
        if (moduleName == null) return

        val modTarget = C_ModifierTarget(C_ModifierTargetType.IMPORT, null, externalChain = true)
        modifiers.compile(ctx, modTarget)

        val extChain = modTarget.externalChain(ctx)

        val module = try {
            ctx.modCtx.modMgr.linkModule(moduleName, extChain)
        } catch (e: C_CommonError) {
            ctx.globalCtx.error(pos, e.code, e.msg)
            null
        }

        val actualAlias = getActualAlias()
        if (actualAlias == null) {
            ctx.globalCtx.error(pos, "import:no_alias", "Cannot infer an alias, specify import alias explicitly")
        }

        if (module != null && (extChain != null || ctx.modCtx.external) && !module.header().external) {
            ctx.globalCtx.error(pos, "import:module_not_external:$moduleName", "Module '$moduleName' is not external")
            return
        }

        if (actualAlias != null && module != null) {
            ctx.nsBuilder.addImport(actualAlias, module)
            ctx.fileCtx.addImport(C_ImportDescriptor(pos, module))
        }
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
    override fun compile(ctx: C_MountContext) {
        val globalCtx = ctx.globalCtx
        globalCtx.error(pos, "include", "Include not supported since Rell $RELL_VERSION_MODULE_SYSTEM")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        // Do nothing.
    }
}

class S_ModuleHeader(val modifiers: S_Modifiers, val pos: S_Pos) {
    fun compile(globalCtx: C_GlobalContext, parentMountName: R_MountName): C_ModuleHeader {
        val modifierCtx = C_ModifierContext(globalCtx, parentMountName)

        val modTarget = C_ModifierTarget(
                C_ModifierTargetType.MODULE,
                null,
                abstract = true,
                externalModule = true,
                mount = true,
                emptyMountAllowed = true
        )
        modifiers.compile(modifierCtx, modTarget)

        val mountName = modTarget.mount?.get() ?: parentMountName

        val abstract = modTarget.abstract?.get() ?: false
        val abstractPos = if (abstract) pos else null

        val external = modTarget.externalModule?.get() ?: false

        return C_ModuleHeader(mountName, abstractPos, external)
    }
}

class S_RellFile(val header: S_ModuleHeader?, val definitions: List<S_Definition>): S_Node() {
    fun compileHeader(globalCtx: C_GlobalContext, parentMountName: R_MountName): C_ModuleHeader? {
        return header?.compile(globalCtx, parentMountName)
    }

    fun compile(path: C_SourcePath, modCtx: C_ModuleContext): C_CompiledRellFile {
        modCtx.executor.checkPass(C_CompilerPass.DEFINITIONS)

        val mountName = modCtx.module.header().mountName

        val fileCtx = C_FileContext(modCtx)

        val nsBuilder = C_UserNsProtoBuilder()
        val nsLate = C_LateInit(C_CompilerPass.NAMESPACES, C_Namespace.EMPTY)

        val nsCtx = C_NamespaceContext(modCtx, modCtx.rootNsCtx, null, nsLate.getter)
        val mntCtx = C_MountContext(fileCtx, nsCtx, modCtx.module.extChain, nsBuilder, mountName)

        compileDefs(mntCtx)

        val nsProto = nsBuilder.build()
        val mntTables = fileCtx.mntBuilder.build()
        val fileContents = fileCtx.createContents()

        return C_CompiledRellFile(path, nsProto, mntTables, fileContents, nsLate.setter)
    }

    fun compileDefs(mntCtx: C_MountContext) {
        for (def in definitions) {
            mntCtx.modCtx.globalCtx.consumeError {
                def.compile(mntCtx)
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
        defCtx: C_DefinitionContext,
        params: List<S_AttrHeader>,
        gtv: Boolean
): C_ExternalParams {
    val blkCtx = defCtx.rootExprCtx.blkCtx

    val names = mutableSetOf<String>()
    val inited = mutableMapOf<C_VarId, C_VarFact>()
    val rVarParams = mutableListOf<R_VarParam>()
    val cExtParams = mutableListOf<C_ExternalParam>()

    for (param in params) {
        val name = param.name
        val type = param.compileTypeOpt(ctx)

        val nameStr = name.str
        if (!names.add(nameStr)) {
            ctx.globalCtx.error(name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        } else if (type != null) {
            val (cId, ptr) = blkCtx.add(name, type, false)
            inited[cId] = C_VarFact.YES
            val rVarParam = R_VarParam(name.str, type, ptr)
            rVarParams.add(rVarParam)
        }

        val cExtParam = C_ExternalParam(name, type)
        if (gtv && ctx.globalCtx.compilerOptions.gtv) {
            checkGtvParam(ctx, cExtParam)
        }

        cExtParams.add(cExtParam)
    }

    val varFacts = C_VarFacts.of(inited = inited.toMap())
    val exprCtx = defCtx.rootExprCtx
    val exprCtx2 = exprCtx.update(factsCtx = exprCtx.factsCtx.sub(varFacts))

    return C_ExternalParams(exprCtx2, rVarParams.toImmList(), cExtParams.toImmList())
}

private fun checkGtvParam(ctx: C_NamespaceContext, param: C_ExternalParam) {
    if (param.type != null) {
        val nameStr = param.name.str
        checkGtvCompatibility(ctx, param.name.pos, param.type, true, "param_nogtv:$nameStr", "Type of parameter '$nameStr'")
    }
}

private class C_ExternalParams(
        val exprCtx: C_ExprContext,
        val rParams: List<R_VarParam>,
        val cParams: List<C_ExternalParam>
)
