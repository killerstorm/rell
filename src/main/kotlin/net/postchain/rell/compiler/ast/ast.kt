/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.parser.RellTokenMatch
import net.postchain.rell.model.*
import net.postchain.rell.module.RELL_VERSION_MODULE_SYSTEM
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.MutableTypedKeyMap
import net.postchain.rell.utils.ThreadLocalContext
import net.postchain.rell.utils.TypedKeyMap
import net.postchain.rell.utils.toImmList
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
        return C_Errors.checkNotNull(rType, name.pos) {
            "unknown_name_type:${name.str}" to "Cannot infer type for '${name.str}'; specify type explicitly"
        }
    }
}

class S_FormalParameter(val attr: S_AttrHeader, val expr: S_Expr?) {
    fun compile(defCtx: C_DefinitionContext): C_FormalParameter {
        val name = attr.name
        val type = attr.compileTypeOpt(defCtx.nsCtx)
        val cParam = C_FormalParameter(name, type, expr?.startPos, expr != null)
        if (expr != null) {
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                val rExpr = compileExpr(defCtx, type)
                cParam.setExpr(rExpr)
            }
        }
        return cParam
    }

    private fun compileExpr(defCtx: C_DefinitionContext, attrType: R_Type?): R_Expr {
        val cExpr = expr!!.compileOpt(defCtx.defExprCtx)
        if (cExpr == null) {
            return C_Utils.ERROR_EXPR
        }

        val rExpr = cExpr.value().toRExpr()
        if (attrType != null) {
            val type = rExpr.type
            S_Type.matchOpt(defCtx.msgCtx, attrType, type, expr.startPos, "param_expr_type",
                    "Wrong type of default value of parameter '${attr.name}'")
        }

        return rExpr
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
            C_Errors.check(names.add(name.str), name.pos) {
                    "entity_keyindex_dup:${name.str}" to "Duplicate attribute: '${name.str}'"
            }
        }

        for (attr in attrs) {
            val name = attr.name
            if (ctx.hasAttribute(name.str)) {
                C_Errors.check(!attr.hasExplicitType(), name.pos) {
                        "entity_keyindex_def:${name.str}" to "Attribute '${name.str}' is defined elsewhere, cannot specify type"
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
    open fun ideIsTestFile(): Boolean = false
}

sealed class S_KeywordModifier(protected val kw: S_String): S_Modifier()

class S_KeywordModifier_Abstract(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.abstract, true)
        target.checkAbstractTest(ctx.msgCtx, kw.pos, target.test)
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

    override fun ideIsTestFile() = name.str == C_Modifier.TEST
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

        C_Errors.check(!external || !ctx.mountName.isEmpty() || name.str !in HEADER_ENTITIES, name.pos) {
            "def_entity_external_unallowed:${name.str}" to
                    "External entity '${name.str}' can be declared only without body (as entity header)"
        }

        C_Errors.check(!external || rFlags.log, name.pos) {
            "def_entity_external_nolog:${names.simpleName}" to
                    "External entity '${names.simpleName}' must have '${C_Constants.LOG_ANNOTATION}' annotation"
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
                    C_Utils.errorRExpr(txType, "Trying to initialize transaction for external entity '${rEntity.appLevelName}'")
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
        val params: List<S_FormalParameter>,
        val body: S_Statement
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.OPERATION)
        ctx.checkNotRepl(name.pos, C_DeclarationType.OPERATION)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.OPERATION, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OPERATION)
        val rOperation = R_Operation(names, mountName)
        val cOperation = C_OperationGlobalFunction(rOperation)

        ctx.appCtx.defsAdder.addOperation(rOperation)
        ctx.nsBuilder.addOperation(name, cOperation)
        ctx.mntBuilder.addOperation(name, rOperation)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cOperation)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(defCtx, rOperation, header)
            }
        }
    }

    private fun compileHeader(defCtx: C_DefinitionContext, cOperation: C_OperationGlobalFunction): C_OperationFunctionHeader {
        val forParams = C_FormalParameters.compile(defCtx, params, true)
        val header = C_OperationFunctionHeader(forParams)
        cOperation.setHeader(header)
        return header
    }

    private fun compileBody(defCtx: C_DefinitionContext, rOperation: R_Operation, header: C_OperationFunctionHeader) {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(defCtx, rOperation.appLevelName, null, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)

        val actParams = header.params.compile(frameCtx)
        val cBody = body.compile(actParams.stmtCtx)
        val rBody = cBody.rStmt
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)

        rOperation.setInternals(actParams.rParams, rBody, callFrame.rFrame)
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
        val params: List<S_FormalParameter>,
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

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.QUERY)
        val rQuery = R_Query(names, mountName)
        val cQuery = C_QueryGlobalFunction(rQuery)

        ctx.appCtx.defsAdder.addQuery(rQuery)
        ctx.nsBuilder.addQuery(name, cQuery)
        ctx.mntBuilder.addQuery(name, rQuery)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cQuery)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(ctx, header, rQuery)
            }
        }
    }

    private fun compileHeader(defCtx: C_DefinitionContext, cQuery: C_QueryGlobalFunction): C_QueryFunctionHeader {
        val header = C_FunctionUtils.compileQueryHeader(defCtx, name, cQuery.rQuery.names, params, retType, body)
        cQuery.setHeader(header)
        return header
    }

    private fun compileBody(ctx: C_MountContext, header: C_QueryFunctionHeader, rQuery: R_Query) {
        if (header.queryBody == null) return

        val rBody = header.queryBody.compile()

        if (ctx.globalCtx.compilerOptions.gtv) {
            ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                checkGtvResult(ctx.msgCtx, rBody.retType)
            }
        }

        rQuery.setBody(rBody)
    }

    private fun checkGtvResult(msgCtx: C_MessageContext, rType: R_Type) {
        checkGtvCompatibility(msgCtx, name.pos, rType, false, "result_nogtv:${name.str}", "Return type of query '${name.str}'")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.QUERY)
    }
}

private fun checkGtvCompatibility(
        msgCtx: C_MessageContext,
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
        msgCtx.error(pos, "$errCode:${type.toStrictString()}", fullMsg)
    }
}

abstract class S_FunctionBody {
    protected abstract fun processStatementVars(): TypedKeyMap
    protected abstract fun compileQuery0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement
    protected abstract fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement

    abstract fun returnsValue(): Boolean

    fun compileQuery(ctx: C_FunctionBodyContext): R_QueryBody {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(ctx.defCtx, ctx.defNames.appLevelName, ctx.explicitRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)
        val actParams = ctx.forParams.compile(frameCtx)

        val cBody = compileQuery0(ctx, actParams.stmtCtx)
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)
        val rRetType = fnCtx.actualReturnType()
        return R_UserQueryBody(rRetType, actParams.rParams, cBody.rStmt, callFrame.rFrame)
    }

    fun compileFunction(ctx: C_FunctionBodyContext): R_FunctionBody {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(ctx.defCtx, ctx.defNames.appLevelName, ctx.explicitRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)
        val actParams = ctx.forParams.compile(frameCtx)

        val cBody = compileFunction0(ctx, actParams.stmtCtx)
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)
        val rRetType = fnCtx.actualReturnType()
        return R_FunctionBody(ctx.defNames.pos, rRetType, actParams.rParams, cBody.rStmt, callFrame.rFrame)
    }
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun processStatementVars() = TypedKeyMap()

    override fun returnsValue() = true

    override fun compileQuery0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cExpr = expr.compile(stmtCtx)
        val rExpr = cExpr.value().toRExpr()
        C_Utils.checkUnitType(bodyCtx.namePos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        stmtCtx.fnCtx.matchReturnType(bodyCtx.namePos, rExpr.type)
        return C_Statement(R_ReturnStatement(rExpr), true)
    }

    override fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val rExpr = expr.compile(stmtCtx).value().toRExpr()
        stmtCtx.fnCtx.matchReturnType(bodyCtx.namePos, rExpr.type)

        return if (rExpr.type != R_UnitType) {
            C_Statement(R_ReturnStatement(rExpr), true)
        } else {
            C_Statement(R_ExprStatement(rExpr), false)
        }
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun processStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        body.discoverVars(map)
        return map.immutableCopy()
    }

    override fun returnsValue(): Boolean {
        return body.returnsValue() ?: false
    }

    override fun compileQuery0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cBody = body.compile(stmtCtx)

        C_Errors.check(cBody.returnAlways, bodyCtx.namePos) {
            val nameStr = bodyCtx.defNames.qualifiedName
            "query_noreturn:$nameStr" to "Query '$nameStr': not all code paths return value"
        }

        return cBody
    }

    override fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cBody = body.compile(stmtCtx)

        val retType = stmtCtx.fnCtx.actualReturnType()
        if (retType != R_UnitType) {
            C_Errors.check(cBody.returnAlways, bodyCtx.namePos) {
                val nameStr = bodyCtx.defNames.qualifiedName
                "fun_noreturn:$nameStr" to "Function '$nameStr': not all code paths return value"
            }
        }

        return cBody
    }
}

class S_FunctionDefinition(
        modifiers: S_Modifiers,
        val qualifiedName: List<S_Name>,
        val params: List<S_FormalParameter>,
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

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.FUNCTION)

        if (abstract) {
            compileAbstract(defCtx, name)
        } else if (override) {
            compileOverride(defCtx)
        } else {
            compileRegular(defCtx, name)
        }
    }

    private fun compileRegular(defCtx: C_DefinitionContext, name: S_Name) {
        val rFn = compileDefinition0(defCtx.mntCtx)
        val cFn = C_UserGlobalFunction(rFn, null)
        defCtx.mntCtx.nsBuilder.addFunction(name, cFn)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileRegularHeader(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileRegularBody(header, rFn)
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

    private fun compileRegularHeader(defCtx: C_DefinitionContext, cFn: C_UserGlobalFunction): C_UserFunctionHeader {
        val header = compileHeader0(defCtx, cFn.rFunction.names)
        cFn.setHeader(header)
        return header
    }

    private fun compileHeader0(defCtx: C_DefinitionContext, defNames: R_DefinitionNames): C_UserFunctionHeader {
        return C_FunctionUtils.compileFunctionHeader(defCtx, qualifiedName.last(), defNames, params, retType, body)
    }

    private fun compileRegularBody(header: C_UserFunctionHeader, rFn: R_Function) {
        if (header.fnBody != null) {
            val rBody = header.fnBody.compile()
            rFn.setBody(rBody)
        }
    }

    private fun compileAbstract(defCtx: C_DefinitionContext, name: S_Name) {
        if (!defCtx.modCtx.abstract) {
            val mName = defCtx.modCtx.moduleName.str()
            val qName = C_Utils.nameStr(qualifiedName)
            defCtx.msgCtx.error(qualifiedName[0].pos, "fn:abstract:non_abstract_module:$mName:$qName",
                    "Abstract function can be defined only in abstract module")
        }

        val mntCtx = defCtx.mntCtx
        val rFn = compileDefinition0(mntCtx)

        val descriptor = C_AbstractDescriptor(name.pos, rFn, body != null)
        mntCtx.fileCtx.addAbstractFunction(descriptor)

        val cFn = C_UserGlobalFunction(rFn, descriptor)
        mntCtx.nsBuilder.addFunction(name, cFn)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileRegularHeader(defCtx, cFn)
            descriptor.setHeader(header)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                header.fnBody?.compile() // Make sure no compilation errors.
                descriptor.bind()
            }
        }
    }

    private fun compileOverride(defCtx: C_DefinitionContext) {
        if (defCtx.modCtx.repl) {
            defCtx.msgCtx.error(qualifiedName[0].pos, "fn:override:repl", "Cannot override a function in REPL")
        }

        val descriptor = C_OverrideDescriptor(qualifiedName[0].pos)
        defCtx.mntCtx.fileCtx.addOverrideFunction(descriptor)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            compileOverrideHeader(defCtx, descriptor)
        }
    }

    private fun compileOverrideHeader(defCtx: C_DefinitionContext, overDescriptor: C_OverrideDescriptor) {
        val fn = defCtx.nsCtx.getFunctionOpt(qualifiedName)?.getDef()
        if (fn == null) {
            val qName = C_Utils.nameStr(qualifiedName)
            defCtx.msgCtx.error(qualifiedName[0].pos, "fn:override:not_found:$qName", "Function not found: '$qName'")
        }

        val absDescriptor = if (fn == null) null else {
            val (def, desc) = fn.getAbstractInfo()
            if (desc == null) {
                val qName = def?.appLevelName ?: C_Utils.nameStr(qualifiedName)
                defCtx.msgCtx.error(qualifiedName[0].pos, "fn:override:not_abstract:[$qName]", "Function is not abstract: '$qName'")
            }
            desc
        }

        val names = definitionNames(defCtx.mntCtx)
        val header = compileHeader0(defCtx, names)

        overDescriptor.setAbstract(absDescriptor)
        if (header.fnBody != null) {
            overDescriptor.setBody(header.fnBody)
        }

        defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            compileOverrideBody(defCtx.mntCtx, header, absDescriptor)
        }
    }

    private fun compileOverrideBody(
            ctx: C_MountContext,
            header: C_UserFunctionHeader,
            absDescriptor: C_AbstractDescriptor?
    ) {
        header.fnBody?.compile()
        if (absDescriptor != null) {
            val retType = header.returnType()
            checkOverrideSignature(ctx, absDescriptor, header.params, retType)
        }
    }

    private fun checkOverrideSignature(
            ctx: C_MountContext,
            absDescriptor: C_AbstractDescriptor,
            params: C_FormalParameters,
            overType: R_Type
    ) {
        val overPos = qualifiedName[0].pos
        val absHeader = absDescriptor.header()

        val absType = absHeader.returnType()
        S_Type.matchOpt(ctx.msgCtx, absType, overType, overPos,
                "fn:override:ret_type", "Return type missmatch")

        val absParams = absHeader.params.list
        val overParams = params.list

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

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val name = qualifiedName.last()
        b.node(this, name, IdeOutlineNodeType.FUNCTION)
    }
}

class S_NamespaceDefinition(
        modifiers: S_Modifiers,
        val fullName: List<S_Name>,
        val definitions: List<S_Definition>
): S_Definition(modifiers) {
    override fun compile(ctx: C_MountContext) {
        val simpleName = if (fullName.isEmpty()) null else fullName.last()
        val modTarget = C_ModifierTarget(C_ModifierTargetType.NAMESPACE, simpleName,
                externalChain = true, mount = true, emptyMountAllowed = true)
        modifiers.compile(ctx, modTarget)

        val subCtx = createSubMountContext(ctx, modTarget)
        for (def in definitions) {
            def.compile(subCtx)
        }
    }

    private fun createSubMountContext(ctx: C_MountContext, modTarget: C_ModifierTarget): C_MountContext {
        val extChain = modTarget.externalChain(ctx)

        if (fullName.isEmpty()) {
            val subMountName = modTarget.mount?.get() ?: ctx.mountName
            return C_MountContext(ctx.fileCtx, ctx.nsCtx, extChain, ctx.nsBuilder, subMountName)
        }

        var nsBuilder = ctx.nsBuilder
        var nsCtx = ctx.nsCtx

        for (name in fullName) {
            nsBuilder = nsBuilder.addNamespace(name, true)

            val names = nsCtx.defNames(name.str)
            val subScopeBuilder = nsCtx.scopeBuilder.nested(nsBuilder.futureNs())
            nsCtx = C_NamespaceContext(ctx.modCtx, names.qualifiedName, subScopeBuilder)
        }

        val mountName = ctx.mountName(modTarget, fullName)
        return C_MountContext(ctx.fileCtx, nsCtx, extChain, nsBuilder, mountName)
    }

    override fun getImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.getImportedModules(moduleName, res)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        var sub = b
        for (name in fullName) sub = sub.node(this, name, IdeOutlineNodeType.NAMESPACE)
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
            C_Errors.check(path.isNotEmpty(), importPos, "import:no_path", "Module not specified")
            return R_ModuleName(rPath)
        }

        C_Errors.check(relative.ups <= currentModule.parts.size, relative.pos) {
            "import:up:${currentModule.parts.size}:${relative.ups}" to
                    "Cannot go up by ${relative.ups}, current module is '${currentModule}'"
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

        if (module.header.test && !ctx.modCtx.test) {
            ctx.msgCtx.error(pos, "import:module_test:$moduleName", "Cannot import a test module '$moduleName' from a non-test module")
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
                emptyMountAllowed = true,
                test = true
        )
        modifiers.compile(modifierCtx, modTarget)

        val mountName = modTarget.mount?.get() ?: parentMountName

        val abstract = modTarget.abstract?.get() ?: false
        val abstractPos = if (abstract) pos else null

        val external = modTarget.externalModule?.get() ?: false
        val test = modTarget.test?.get() ?: false

        return C_ModuleHeader(mountName, abstractPos, external = external, test = test)
    }

    fun ideIsTestFile(): Boolean {
        return modifiers.modifiers.any { it.ideIsTestFile() }
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

    fun ideIsTestFile(): Boolean {
        return header != null && header.ideIsTestFile()
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

class C_FormalParameters(list: List<C_FormalParameter>) {
    val list = list.toImmList()

    fun compile(frameCtx: C_FrameContext): C_ActualParameters {
        val inited = mutableMapOf<C_VarUid, C_VarFact>()
        val names = mutableSetOf<String>()
        val rParams = mutableListOf<R_VarParam>()

        val blkCtx = frameCtx.rootBlkCtx

        for (param in list) {
            val name = param.name
            val nameStr = name.str
            val type = param.type

            if (!names.add(nameStr)) {
                frameCtx.msgCtx.error(name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
            } else if (type != null) {
                val cVar = blkCtx.addLocalVar(name, type, false)
                inited[cVar.uid] = C_VarFact.YES
                val rVarParam = param.createVarParam(type, cVar.ptr)
                rParams.add(rVarParam)
            }
        }

        val varFacts = C_VarFacts.of(inited = inited.toMap())

        val stmtCtx = C_StmtContext.createRoot(blkCtx)
                .updateFacts(varFacts)

        return C_ActualParameters(stmtCtx, rParams)
    }

    companion object {
        val EMPTY = C_FormalParameters(listOf())

        fun compile(defCtx: C_DefinitionContext, params: List<S_FormalParameter>, gtv: Boolean): C_FormalParameters {
            val cParams = mutableListOf<C_FormalParameter>()

            for (param in params) {
                val cParam = param.compile(defCtx)
                cParams.add(cParam)
            }

            if (gtv && defCtx.globalCtx.compilerOptions.gtv && cParams.isNotEmpty()) {
                defCtx.executor.onPass(C_CompilerPass.VALIDATION) {
                    for (cExtParam in cParams) {
                        checkGtvParam(defCtx.msgCtx, cExtParam)
                    }
                }
            }

            return C_FormalParameters(cParams)
        }

        private fun checkGtvParam(msgCtx: C_MessageContext, param: C_FormalParameter) {
            if (param.type != null) {
                val nameStr = param.name.str
                checkGtvCompatibility(msgCtx, param.name.pos, param.type, true, "param_nogtv:$nameStr", "Type of parameter '$nameStr'")
            }
        }
    }
}

class C_ActualParameters(val stmtCtx: C_StmtContext, rParams: List<R_VarParam>) {
    val rParams = rParams.toImmList()
}
