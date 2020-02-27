/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.MutableTypedKeyMap
import net.postchain.rell.ThreadLocalContext
import net.postchain.rell.TypedKeyMap
import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.parser.RellTokenMatch
import net.postchain.rell.model.*
import net.postchain.rell.module.RELL_VERSION_MODULE_SYSTEM
import net.postchain.rell.toImmList
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import java.util.*
import java.util.function.Supplier
import kotlin.math.min

abstract class S_Pos {
    abstract fun path(): C_SourcePath
    abstract fun line(): Int
    abstract fun column(): Int
    abstract fun pos(): Long
    abstract fun str(): String
    abstract fun strLine(): String
    override fun toString() = str()

    fun toFilePos() = R_FilePos(path().str(), line())
}

class S_BasicPos(private val file: C_SourcePath, private val row: Int, private val col: Int): S_Pos() {
    override fun path() = file
    override fun line() = row
    override fun column() = col
    override fun pos() = Math.min(row, 1_000_000_000) * 1_000_000_000L + Math.min(col, 1_000_000_000)
    override fun str() = "$file($row:$col)"
    override fun strLine() = "$file:$row"

    override fun equals(other: Any?): Boolean {
        return other is S_BasicPos && row == other.row && col == other.col && file == other.file
    }

    override fun hashCode(): Int {
        return Objects.hash(row, col, file)
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
    val rName = R_Name.of(str)
    override fun toString() = str
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
        return ctx.msgCtx.consumeError { compileType(ctx) }
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
        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.mountName)
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
        ctx.checkNotRepl(name.pos, C_DeclarationType.ENTITY)

        if (deprecatedKwPos != null) {
            ctx.msgCtx.error(deprecatedKwPos, "deprecated_kw:class:entity",
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

        ctx.appCtx.defsAdder.addEntity(C_Entity(name.pos, rEntity))
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
            ctx.msgCtx.error(name.pos, "def_entity_hdr_annotations:${name.str}",
                    "Annotations not allowed for entity header '${name.str}'")
            err = true
        }

        val entGetter = HEADER_ENTITIES[name.str]
        if (entGetter == null) {
            val entities = HEADER_ENTITIES.keys.joinToString()
            ctx.msgCtx.error(name.pos, "def_entity_hdr_name:${name.str}",
                    "Entity header declarations allowed only for entities: $entities")
            err = true
        }

        val extChain = modTarget.externalChain(ctx)
        if (extChain == null && !ctx.modCtx.external) {
            ctx.msgCtx.error(name.pos, "def_entity_hdr_noexternal:${name.str}",
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
            ctx.msgCtx.warning(ann.pos, "ann:legacy:${ann.str}", "Deprecated annotation syntax; use @${ann.str} instead")

            val annStr = ann.str
            if (!set.add(annStr)) {
                ctx.msgCtx.error(ann.pos, "entity_ann_dup:$annStr", "Duplicate annotation: '$annStr'")
            }

            if (annStr == C_Constants.LOG_ANNOTATION) {
                log = true
            } else {
                ctx.msgCtx.error(ann.pos, "entity_ann_bad:$annStr", "Invalid annotation: '$annStr'")
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
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.ENTITY)
        val entCtx = C_EntityContext(defCtx, name.str, rEntity.flags.log)

        if (rEntity.flags.log) {
            val sysDefs = extChain?.sysDefs ?: ctx.modCtx.sysDefs
            val txType = sysDefs.transactionEntity.type
            entCtx.addAttribute0("transaction", txType, false, false) {
                if (extChain == null) {
                    val nsValueCtx = C_NamespaceValueContext(entCtx.defCtx)
                    C_Ns_OpContext.transactionExpr(nsValueCtx, name.pos)
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
        ctx.checkNotRepl(name.pos, C_DeclarationType.OBJECT)

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
        ctx.appCtx.defsAdder.addObject(rObject)
        ctx.nsBuilder.addObject(name, rObject)
        ctx.mntBuilder.addObject(name, rObject)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx, rObject)
        }
    }

    private fun membersPass(ctx: C_MountContext, rObject: R_Object) {
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OBJECT)
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
            ctx.msgCtx.error(deprecatedKwPos, "deprecated_kw:record:struct",
                    "Keyword 'record' is deprecated, use 'struct' instead")
        }

        ctx.checkNotExternal(name.pos, C_DeclarationType.STRUCT)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.STRUCT, name)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val rStruct = R_Struct(names)
        ctx.appCtx.defsAdder.addStruct(rStruct)
        ctx.nsBuilder.addStruct(name, rStruct)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(ctx, rStruct)
        }
    }

    private fun membersPass(ctx: C_MountContext, rStruct: R_Struct) {
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.STRUCT)
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
                ctx.msgCtx.error(attr.pos, "enum_dup:${attr.str}", "Duplicate enum constant: '${attr.str}'")
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
        ctx.checkNotRepl(name.pos, C_DeclarationType.OPERATION)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.OPERATION, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)

        val rOperation = R_Operation(names, mountName)
        ctx.appCtx.defsAdder.addOperation(rOperation)
        ctx.nsBuilder.addOperation(name, rOperation)
        ctx.mntBuilder.addOperation(name, rOperation)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            doCompile(ctx, rOperation)
        }
    }

    private fun doCompile(ctx: C_MountContext, rOperation: R_Operation) {
        val statementVars = processStatementVars()
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OPERATION)
        val fnCtx = C_FunctionContext(defCtx, rOperation.appLevelName, null, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)

        val extParams = compileExternalParams(ctx.nsCtx, frameCtx, params, true)

        val rBody = body.compile(extParams.stmtCtx).rStmt
        val callFrame = frameCtx.makeCallFrame()

        rOperation.setInternals(extParams.rParams, rBody, callFrame.rFrame)
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
        ctx.checkNotRepl(name.pos, C_DeclarationType.QUERY)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.QUERY, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)

        val rQuery = R_Query(names, mountName)
        ctx.appCtx.defsAdder.addQuery(rQuery)
        ctx.nsBuilder.addQuery(name, rQuery)
        ctx.mntBuilder.addQuery(name, rQuery)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            doCompile(ctx, rQuery)
        }
    }

    private fun doCompile(ctx: C_MountContext, rQuery: R_Query) {
        val rExplicitRetType = retType?.compile(ctx.nsCtx)
        val statementVars = body.processStatementVars()
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.QUERY)
        val fnCtx = C_FunctionContext(defCtx, rQuery.appLevelName, rExplicitRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)

        val extParams = compileExternalParams(ctx.nsCtx, frameCtx, params, true)

        val bodyCtx = C_FunctionBodyContext(frameCtx, extParams.stmtCtx)
        val rBody = body.compileQuery(name, bodyCtx)
        val callFrame = frameCtx.makeCallFrame()
        val rRetType = fnCtx.actualReturnType()

        if (ctx.globalCtx.compilerOptions.gtv) {
            checkGtvResult(ctx.nsCtx, rRetType)
        }

        val rQueryBody = R_UserQueryBody(extParams.rParams, rBody, callFrame.rFrame)
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
        ctx.msgCtx.error(pos, "$errCode:${type.toStrictString()}", fullMsg)
    }
}

abstract class S_FunctionBody {
    abstract fun processStatementVars(): TypedKeyMap
    abstract fun compileQuery(name: S_Name, ctx: C_FunctionBodyContext): R_Statement
    abstract fun compileFunction(qualifiedName: List<S_Name>, ctx: C_FunctionBodyContext): R_Statement
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun processStatementVars() = TypedKeyMap()

    override fun compileQuery(name: S_Name, ctx: C_FunctionBodyContext): R_Statement {
        val cExpr = expr.compile(ctx.stmtCtx)
        val rExpr = cExpr.value().toRExpr()
        C_Utils.checkUnitType(name.pos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        ctx.fnCtx.matchReturnType(name.pos, rExpr.type)
        return R_ReturnStatement(rExpr)
    }

    override fun compileFunction(qualifiedName: List<S_Name>, ctx: C_FunctionBodyContext): R_Statement {
        val name = C_Utils.namePosStr(qualifiedName)

        val rExpr = expr.compile(ctx.stmtCtx).value().toRExpr()
        ctx.fnCtx.matchReturnType(name.pos, rExpr.type)

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

    override fun compileQuery(name: S_Name, ctx: C_FunctionBodyContext): R_Statement {
        val cBody = body.compile(ctx.stmtCtx)

        if (!cBody.returnAlways) {
            throw C_Error(name.pos, "query_noreturn:${name.str}", "Query '${name.str}': not all code paths return value")
        }

        return cBody.rStmt
    }

    override fun compileFunction(qualifiedName: List<S_Name>, ctx: C_FunctionBodyContext): R_Statement {
        val cBody = body.compile(ctx.stmtCtx)

        val retType = ctx.fnCtx.actualReturnType()
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
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:qname_no_override:$qName",
                    "Invalid function name: '$qName' (qualified names allowed only for override)")
        }

        if (abstract && override) {
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:abstract_override:$qName", "Both 'abstract' and 'override' specified")
        }

        if (!abstract && body == null) {
            ctx.msgCtx.error(name.pos, "fn:no_body:$qName", "Function '$qName' must have a body (it is not abstract)")
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
            val cHeader = compileRegularHeader(ctx, cFn)
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

    private fun compileRegularHeader(ctx: C_MountContext, cFn: C_UserGlobalFunction): C_FnHeader {
        val h = compileHeader0(ctx, cFn.rFunction.appLevelName)
        cFn.setHeader(h.retType, h.cParams)
        return h
    }

    private fun compileHeader0(ctx: C_MountContext, name: String): C_FnHeader {
        val rRetType = if (retType != null) retType.compile(ctx.nsCtx) else R_UnitType
        val statementVars = body?.processStatementVars() ?: TypedKeyMap()
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.FUNCTION)
        val fnCtx = C_FunctionContext(defCtx, name, rRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)
        val extParams = compileExternalParams(ctx.nsCtx, frameCtx, params, false)
        val bodyCtx = C_FunctionBodyContext(frameCtx, extParams.stmtCtx)
        return C_FnHeader(rRetType, bodyCtx, extParams.rParams, extParams.cParams)
    }

    private fun compileRegularBody(cHeader: C_FnHeader, rFn: R_Function) {
        val rBody = body?.compileFunction(qualifiedName, cHeader.bodyCtx) ?: C_Utils.ERROR_STATEMENT
        rFn.setBody(createFunctionBody(rFn.pos, cHeader, rBody))
    }

    private fun compileAbstract(ctx: C_MountContext, name: S_Name) {
        if (!ctx.modCtx.abstract) {
            val mName = ctx.modCtx.moduleName.str()
            val qName = C_Utils.nameStr(qualifiedName)
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:abstract:non_abstract_module:$mName:$qName",
                    "Abstract function can be defined only in abstract module")
        }

        val rFn = compileDefinition0(ctx)

        val descriptor = C_AbstractDescriptor(name.pos, rFn, body != null)
        ctx.fileCtx.addAbstractFunction(descriptor)

        val cFn = C_UserGlobalFunction(rFn, descriptor)
        ctx.nsBuilder.addFunction(name, cFn)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val cHeader = compileRegularHeader(ctx, cFn)
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
        val rBody = realBody.compileFunction(qualifiedName, cHeader.bodyCtx)
        if (descriptor.isUsingDefaultBody()) {
            rFn.setBody(createFunctionBody(rFn.pos, cHeader, rBody))
        }
    }

    private fun compileOverride(ctx: C_MountContext) {
        if (ctx.modCtx.repl) {
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:override:repl", "Cannot override a function in REPL")
        }

        val descriptor = C_OverrideDescriptor(qualifiedName[0].pos)
        ctx.fileCtx.addOverrideFunction(descriptor)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            compileOverrideHeader(ctx, descriptor)
        }
    }

    private fun compileOverrideHeader(ctx: C_MountContext, overDescriptor: C_OverrideDescriptor) {
        val fn = ctx.nsCtx.getFunctionOpt(qualifiedName)?.getDef()
        if (fn == null) {
            val qName = C_Utils.nameStr(qualifiedName)
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:override:not_found:$qName", "Function not found: '$qName'")
        }

        val absDescriptor = if (fn == null) null else {
            val (def, desc) = fn.getAbstractInfo()
            if (desc == null) {
                val qName = def?.appLevelName ?: C_Utils.nameStr(qualifiedName)
                ctx.msgCtx.error(qualifiedName[0].pos, "fn:override:not_abstract:[$qName]", "Function is not abstract: '$qName'")
            }
            desc
        }

        val cHeader = compileHeader0(ctx, C_Utils.nameStr(qualifiedName))
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

        val rBody = body?.compileFunction(qualifiedName, cHeader.bodyCtx) ?: C_Utils.ERROR_STATEMENT
        overDescriptor.setBody(createFunctionBody(defPos, cHeader, rBody))
    }

    private fun checkOverrideSignature(ctx: C_MountContext, abstractDescriptor: C_AbstractDescriptor, cHeader: C_FnHeader) {
        val overPos = qualifiedName[0].pos
        val absHeader = abstractDescriptor.header()

        S_Type.matchOpt(ctx.msgCtx, absHeader.retType, cHeader.retType, overPos,
                "fn:override:ret_type", "Return type missmatch")

        val absParams = absHeader.cParams
        val overParams = cHeader.cParams

        if (absParams.size != overParams.size) {
            ctx.msgCtx.error(overPos, "fn:override:param_cnt:${absParams.size}:${overParams.size}",
                    "Wrong number of parameters: ${overParams.size} instead of ${absParams.size}")
        }

        for (i in 0 until min(overParams.size, absParams.size)) {
            val absParam = absParams[i]
            val overParam = overParams[i]
            if (absParam.type != null && overParam.type != null && overParam.type != absParam.type) {
                val code = "fn:override:param_type:$i:${absParam.name.str}"
                val msg = "Parameter '${absParam.name.str}' (${i+1}) type missmatch "
                val err = C_Errors.errTypeMismatch(overParam.name.pos, overParam.type, absParam.type, code, msg)
                ctx.msgCtx.error(err)
            }
        }
    }

    private fun createFunctionBody(defPos: R_DefinitionPos, cHeader: C_FnHeader, rBody: R_Statement): R_FunctionBody {
        val callFrame = cHeader.bodyCtx.frameCtx.makeCallFrame()
        return R_FunctionBody(defPos, cHeader.retType, cHeader.rParams, rBody, callFrame.rFrame)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val name = qualifiedName.last()
        b.node(this, name, IdeOutlineNodeType.FUNCTION)
    }

    private class C_FnHeader(
            val retType: R_Type,
            val bodyCtx: C_FunctionBodyContext,
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

        val subNsBuilder = ctx.nsBuilder.addNamespace(name, true)

        val names = ctx.nsCtx.defNames(name.str)
        val subScopeBuilder = ctx.nsCtx.scopeBuilder.nested(subNsBuilder.futureNs())
        val subNsCtx = C_NamespaceContext(ctx.modCtx, names.qualifiedName, subScopeBuilder)

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

class S_RelativeImportModulePath(val pos: S_Pos, val ups: Int)

class S_ImportModulePath(val relative: S_RelativeImportModulePath?, val path: List<S_Name>) {
    fun compile(importPos: S_Pos, currentModule: R_ModuleName): R_ModuleName {
        val rPath = path.map { it.rName }

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

sealed class S_ImportTarget {
    abstract fun addToNamespace(ctx: C_MountContext, def: S_ImportDefinition, module: C_ModuleKey)

    protected fun getNsBuilder(ctx: C_MountContext, alias: S_Name?): C_UserNsProtoBuilder {
        return if (alias == null) ctx.nsBuilder else ctx.nsBuilder.addNamespace(alias, false)
    }
}

class S_DefaultImportTarget: S_ImportTarget() {
    override fun addToNamespace(ctx: C_MountContext, def: S_ImportDefinition, module: C_ModuleKey) {
        val alias = def.alias ?: def.modulePath.getAlias()
        if (alias == null) {
            ctx.msgCtx.error(def.pos, "import:no_alias", "Cannot infer an alias, specify import alias explicitly")
            return
        }
        ctx.nsBuilder.addModuleImport(alias, module)
    }
}

class S_ExactImportTargetItem(val alias: S_Name?, val name: List<S_Name>, val wildcard: Boolean) {
    fun addToNamespace(nsBuilder: C_UserNsProtoBuilder, module: C_ModuleKey) {
        check(name.isNotEmpty())
        if (wildcard) {
            val nsBuilder2 = if (alias == null) nsBuilder else nsBuilder.addNamespace(alias, false)
            nsBuilder2.addWildcardImport(module, name)
        } else {
            val lastName = name.last()
            val realAlias = alias ?: lastName
            nsBuilder.addExactImport(realAlias, module, name.subList(0, name.size - 1), lastName)
        }
    }
}

class S_ExactImportTarget(val items: List<S_ExactImportTargetItem>): S_ImportTarget() {
    override fun addToNamespace(ctx: C_MountContext, def: S_ImportDefinition, module: C_ModuleKey) {
        val nsBuilder = getNsBuilder(ctx, def.alias)
        for (item in items) {
            item.addToNamespace(nsBuilder, module)
        }
    }
}

class S_WildcardImportTarget: S_ImportTarget() {
    override fun addToNamespace(ctx: C_MountContext, def: S_ImportDefinition, module: C_ModuleKey) {
        val nsBuilder = getNsBuilder(ctx, def.alias)
        nsBuilder.addWildcardImport(module, listOf())
    }
}

class S_ImportDefinition(
        modifiers: S_Modifiers,
        val pos: S_Pos,
        val alias: S_Name?,
        val modulePath: S_ImportModulePath,
        val target: S_ImportTarget
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        val moduleName = ctx.msgCtx.consumeError { modulePath.compile(pos, ctx.modCtx.moduleName) }
        if (moduleName == null) return

        val modTarget = C_ModifierTarget(C_ModifierTargetType.IMPORT, null, externalChain = true)
        modifiers.compile(ctx, modTarget)

        val extChain = modTarget.externalChain(ctx)

        val module = try {
            ctx.modCtx.modMgr.linkModule(moduleName, extChain)
        } catch (e: C_CommonError) {
            ctx.msgCtx.error(pos, e.code, e.msg)
            return
        }

        target.addToNamespace(ctx, this, module.key)

        if ((extChain != null || ctx.modCtx.external) && !module.header.external) {
            ctx.msgCtx.error(pos, "import:module_not_external:$moduleName", "Module '$moduleName' is not external")
        }

        ctx.fileCtx.addImport(C_ImportDescriptor(pos, module))
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
        ctx.msgCtx.error(pos, "include", "Include not supported since Rell $RELL_VERSION_MODULE_SYSTEM")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        // Do nothing.
    }
}

class S_ModuleHeader(val modifiers: S_Modifiers, val pos: S_Pos) {
    fun compile(msgCtx: C_MessageContext, parentMountName: R_MountName): C_ModuleHeader {
        val modifierCtx = C_ModifierContext(msgCtx, parentMountName)

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
    fun compileHeader(msgCtx: C_MessageContext, parentMountName: R_MountName): C_ModuleHeader? {
        return header?.compile(msgCtx, parentMountName)
    }

    fun compile(path: C_SourcePath, modCtx: C_ModuleContext): C_CompiledRellFile {
        modCtx.executor.checkPass(C_CompilerPass.DEFINITIONS)

        val fileCtx = C_FileContext(modCtx)
        val mountName = modCtx.mountName
        val nsAssembler = modCtx.createFileNsAssembler()
        val mntCtx = createMountContext(fileCtx, mountName, nsAssembler)

        compileDefs(mntCtx)

        val mntTables = fileCtx.mntBuilder.build()
        val fileContents = fileCtx.createContents()

        return C_CompiledRellFile(path, mntTables, fileContents)
    }

    fun compileDefs(mntCtx: C_MountContext) {
        for (def in definitions) {
            mntCtx.msgCtx.consumeError {
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

    companion object {
        fun createMountContext(fileCtx: C_FileContext, mountName: R_MountName, nsAssembler: C_NsAsm_ComponentAssembler): C_MountContext {
            val modCtx = fileCtx.modCtx
            val nsBuilder = C_UserNsProtoBuilder(nsAssembler)
            val fileScopeBuilder = modCtx.scopeBuilder.nested(nsAssembler.futureNs())
            val nsCtx = C_NamespaceContext(modCtx, null, fileScopeBuilder)
            return C_MountContext(fileCtx, nsCtx, modCtx.extChain, nsBuilder, mountName)
        }
    }
}

private fun compileExternalParams(
        ctx: C_NamespaceContext,
        frameCtx: C_FrameContext,
        params: List<S_AttrHeader>,
        gtv: Boolean
): C_ExternalParams {
    val blkCtx = frameCtx.rootBlkCtx

    val names = mutableSetOf<String>()
    val inited = mutableMapOf<C_VarUid, C_VarFact>()
    val rVarParams = mutableListOf<R_VarParam>()
    val cExtParams = mutableListOf<C_ExternalParam>()

    for (param in params) {
        val name = param.name
        val type = param.compileTypeOpt(ctx)

        val nameStr = name.str
        if (!names.add(nameStr)) {
            ctx.msgCtx.error(name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        } else if (type != null) {
            val cVar = blkCtx.addLocalVar(name, type, false)
            inited[cVar.uid] = C_VarFact.YES
            val rVarParam = R_VarParam(name.str, type, cVar.ptr)
            rVarParams.add(rVarParam)
        }

        val cExtParam = C_ExternalParam(name, type)
        if (gtv && ctx.globalCtx.compilerOptions.gtv) {
            checkGtvParam(ctx, cExtParam)
        }

        cExtParams.add(cExtParam)
    }

    val varFacts = C_VarFacts.of(inited = inited.toMap())

    val stmtCtx = C_StmtContext.createRoot(blkCtx)
            .updateFacts(varFacts)

    return C_ExternalParams(stmtCtx, rVarParams.toImmList(), cExtParams.toImmList())
}

private fun checkGtvParam(ctx: C_NamespaceContext, param: C_ExternalParam) {
    if (param.type != null) {
        val nameStr = param.name.str
        checkGtvCompatibility(ctx, param.name.pos, param.type, true, "param_nogtv:$nameStr", "Type of parameter '$nameStr'")
    }
}

private class C_ExternalParams(
        val stmtCtx: C_StmtContext,
        val rParams: List<R_VarParam>,
        val cParams: List<C_ExternalParam>
)
