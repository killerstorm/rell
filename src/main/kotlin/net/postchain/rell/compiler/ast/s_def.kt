/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.*
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.module.C_MidModuleMember
import net.postchain.rell.compiler.base.module.C_MidModuleMember_Basic
import net.postchain.rell.compiler.base.module.C_MidModuleMember_Namespace
import net.postchain.rell.compiler.base.module.C_ModuleSourceContext
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.namespace.C_NamespaceValueContext
import net.postchain.rell.compiler.base.utils.C_Constants
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_LateInit
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_ConstantValueEvalContext
import net.postchain.rell.lib.C_Lib_OpContext
import net.postchain.rell.model.*
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.toGtv
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.MsgString
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class S_AttrHeader(val name: S_Name, private val type: S_Type?): S_Node() {
    fun compileExplicitType(ctx: C_NamespaceContext): R_Type? {
        return if (type == null) null else {
            val rType = type.compileOpt(ctx) ?: R_CtErrorType
            checkUnitType(ctx, type.pos, rType)
        }
    }

    fun compileImplicitType(ctx: C_NamespaceContext): R_Type? {
        val rType = ctx.getTypeOpt(listOf(name))
        return if (rType == null) null else checkUnitType(ctx, name.pos, rType)
    }

    fun compileType(ctx: C_NamespaceContext): R_Type {
        val rExplicitType = compileExplicitType(ctx)
        if (rExplicitType != null) {
            return rExplicitType
        }

        val rImplicitType = compileImplicitType(ctx)
        return if (rImplicitType != null) rImplicitType else {
            C_Errors.errAttributeTypeUnknown(ctx.msgCtx, name)
            R_CtErrorType
        }
    }

    private fun checkUnitType(ctx: C_NamespaceContext, pos: S_Pos, rType: R_Type): R_Type {
        return C_Types.checkNotUnit(ctx.msgCtx, pos, rType, name.str) { "attr_var" to "attribute or variable" }
    }
}

sealed class S_RelClause: S_Node() {
    abstract fun compile(ctx: C_EntityContext)
    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)
}

class S_AttributeClause(val attr: S_AttributeDefinition): S_RelClause() {
    override fun compile(ctx: C_EntityContext) {
        ctx.addAttribute(attr, true)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, attr.header.name, IdeOutlineNodeType.ATTRIBUTE)
    }
}

enum class S_KeyIndexKind(nameMsg: String) {
    KEY("key"),
    INDEX("index"),
    ;

    val nameMsg = MsgString(nameMsg)
}

class S_KeyIndexClause(val pos: S_Pos, val kind: S_KeyIndexKind, val attrs: List<S_AttributeDefinition>): S_RelClause() {
    override fun compile(ctx: C_EntityContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr, false)
        }

        val names = mutableSetOf<String>()
        for (attr in attrs) {
            val name = attr.header.name
            C_Errors.check(names.add(name.str), name.pos) {
                "entity_keyindex_dup:${name.str}" to "Duplicate attribute: '${name.str}'"
            }
        }

        if (attrs.size > 1) {
            attrs.all { it.checkMultiAttrKeyIndex(ctx.msgCtx, kind) }
        }

        val attrNames = attrs.map { it.header.name }

        when (kind) {
            S_KeyIndexKind.KEY -> ctx.addKey(pos, attrNames)
            S_KeyIndexKind.INDEX -> ctx.addIndex(pos, attrNames)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (attr in attrs) {
            b.node(attr, attr.header.name, IdeOutlineNodeType.KEY_INDEX)
        }
    }
}

class S_AttributeDefinition(val mutablePos: S_Pos?, val header: S_AttrHeader, val expr: S_Expr?): S_Node() {
    fun checkMultiAttrKeyIndex(msgCtx: C_MessageContext, kind: S_KeyIndexKind): Boolean {
        return if (mutablePos != null) {
            errKeyIndexTooComplex(msgCtx, kind, mutablePos, "mutable")
            false
        } else if (expr != null) {
            errKeyIndexTooComplex(msgCtx, kind, expr.startPos, "expr")
            false
        } else {
            true
        }
    }

    private fun errKeyIndexTooComplex(msgCtx: C_MessageContext, kind: S_KeyIndexKind, pos: S_Pos, reasonCode: String) {
        msgCtx.error(pos, "attr:key_index:too_complex:${header.name}:$kind:$reasonCode",
                "${kind.nameMsg.capital} definition is too complex; write each attribute definition separately " +
                        "and use only attribute names in the index clause")
    }
}

abstract class S_Definition(val kwPos: S_Pos, val modifiers: S_Modifiers): S_Node() {
    val startPos = modifiers.pos ?: kwPos

    abstract fun compile(ctx: C_ModuleSourceContext): C_MidModuleMember?

    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)

    open fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
    }

    protected fun checkSysMountNameConflict(
            ctx: C_MountContext,
            pos: S_Pos,
            declType: C_DeclarationType,
            mountName: R_MountName,
            sysDefs: Set<R_MountName>
    ) {
        if (mountName in sysDefs) {
            ctx.msgCtx.error(pos, "mount:conflict:sys:$declType:$mountName",
                    "Mount name conflict: system ${declType.msg} '$mountName' exists")
        }
    }
}

abstract class S_BasicDefinition(pos: S_Pos, modifiers: S_Modifiers): S_Definition(pos, modifiers) {
    final override fun compile(ctx: C_ModuleSourceContext): C_MidModuleMember {
        return C_MidModuleMember_Basic(this)
    }

    abstract fun compileBasic(ctx: C_MountContext)
}

class S_EntityDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val deprecatedKwPos: S_Pos?,
        val name: S_Name,
        val annotations: List<S_Name>,
        val body: List<S_RelClause>?
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotReplOrTest(name.pos, C_DeclarationType.ENTITY)

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

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.ENTITY, names.defId)

        val rExternalEntity = if (extChainRef == null) null else R_ExternalEntity(extChainRef, true)

        val rEntity = C_Utils.createEntity(
                ctx.appCtx,
                C_DefinitionType.ENTITY,
                names,
                defCtx.initFrameGetter,
                mountName,
                rFlags,
                rMapping,
                rExternalEntity
        )

        ctx.appCtx.defsAdder.addEntity(C_Entity(name.pos, rEntity))
        ctx.nsBuilder.addEntity(name, rEntity)
        ctx.mntBuilder.addEntity(name.pos, rEntity)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(defCtx, extChain, rEntity, body)
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

    private fun membersPass(defCtx: C_DefinitionContext, extChain: C_ExternalChain?, rEntity: R_EntityDefinition, clauses: List<S_RelClause>) {
        val sysAttrs = mutableListOf<C_SysAttribute>()

        if (rEntity.flags.log) {
            val sysDefs = extChain?.sysDefs ?: defCtx.modCtx.sysDefs
            val txType = sysDefs.transactionEntity.type
            val expr = if (extChain == null) {
                val nsValueCtx = C_NamespaceValueContext(defCtx.initExprCtx)
                C_Lib_OpContext.transactionRExpr(nsValueCtx, name.pos)
            } else {
                C_Utils.errorRExpr(txType, "Trying to initialize transaction for external entity '${rEntity.appLevelName}'")
            }
            sysAttrs.add(C_SysAttribute("transaction", txType, expr = expr, mutable = false, canSetInCreate = false))
        }

        val entCtx = C_EntityContext(defCtx, name.str, rEntity.flags.log, sysAttrs)

        for (clause in clauses) {
            clause.compile(entCtx)
        }

        val body = entCtx.createEntityBody()
        C_Utils.setEntityBody(rEntity, body)
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
    }
}

class S_ObjectDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val attrs: List<S_AttributeDefinition>
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.OBJECT)
        ctx.checkNotReplOrTest(name.pos, C_DeclarationType.OBJECT)

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

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OBJECT, names.defId)

        val rEntity = C_Utils.createEntity(
                ctx.appCtx,
                C_DefinitionType.OBJECT,
                names,
                defCtx.initFrameGetter,
                mountName,
                entityFlags,
                sqlMapping,
                null
        )

        val rObject = R_ObjectDefinition(names, rEntity)

        ctx.appCtx.defsAdder.addObject(rObject)
        ctx.nsBuilder.addObject(name, rObject)
        ctx.mntBuilder.addObject(name, rObject)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(defCtx, rObject)
        }
    }

    private fun membersPass(defCtx: C_DefinitionContext, rObject: R_ObjectDefinition) {
        val entCtx = C_EntityContext(defCtx, name.str, false, listOf())

        for (attr in attrs) {
            entCtx.addAttribute(attr, true)
        }

        val body = entCtx.createEntityBody()
        C_Utils.setEntityBody(rObject.rEntity, body)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.OBJECT)
        for (attr in attrs) {
            sub.node(attr, attr.header.name, IdeOutlineNodeType.ATTRIBUTE)
        }
    }
}

class S_StructDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val deprecatedKwPos: S_Pos?,
        val name: S_Name,
        val attrs: List<S_AttributeDefinition>
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        if (deprecatedKwPos != null) {
            ctx.msgCtx.error(deprecatedKwPos, "deprecated_kw:record:struct",
                    "Keyword 'record' is deprecated, use 'struct' instead")
        }

        ctx.checkNotExternal(name.pos, C_DeclarationType.STRUCT)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.STRUCT, name)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.STRUCT, names.defId)

        val rStruct = R_Struct(names.appLevelName, names.appLevelName.toGtv(), defCtx.initFrameGetter, mirrorStructs = null)
        val rStructDef = R_StructDefinition(names, rStruct)

        val attrsLate = C_LateInit<List<C_CompiledAttribute>>(C_CompilerPass.MEMBERS, immListOf())
        val cStruct = C_Struct(name, rStructDef, attrsLate.getter)

        ctx.appCtx.defsAdder.addStruct(rStruct)
        ctx.nsBuilder.addStruct(cStruct)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(defCtx, cStruct, attrsLate)
        }
    }

    private fun membersPass(
            defCtx: C_DefinitionContext,
            cStruct: C_Struct,
            attrsLate: C_LateInit<List<C_CompiledAttribute>>
    ) {
        val entCtx = C_EntityContext(defCtx, name.str, false, listOf())

        for (attr in attrs) {
            entCtx.addAttribute(attr, true)
        }

        val cAttributes = entCtx.createStructBody()
        attrsLate.set(cAttributes.map { it.value }.toImmList())

        val rAttributes = cAttributes.mapValues { it.value.rAttr }.toImmMap()
        cStruct.structDef.struct.setAttributes(rAttributes)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.STRUCT)
        for (attr in attrs) {
            sub.node(attr, attr.header.name, IdeOutlineNodeType.ATTRIBUTE)
        }
    }
}

class S_EnumDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val attrs: List<S_Name>
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
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
        val rEnum = R_EnumDefinition(names, R_CallFrame.NONE_INIT_FRAME_GETTER, rAttrs.toList())
        ctx.nsBuilder.addEnum(name, rEnum)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.ENUM)
        for (attr in attrs) {
            sub.node(attr, attr, IdeOutlineNodeType.ENUM_ATTRIBUTE)
        }
    }
}

class S_NamespaceDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val fullName: List<S_Name>,
        val definitions: List<S_Definition>
): S_Definition(pos, modifiers) {
    override fun compile(ctx: C_ModuleSourceContext): C_MidModuleMember {
        val midMembers = definitions.mapNotNull { it.compile(ctx) }
        return C_MidModuleMember_Namespace(modifiers, fullName, midMembers)
    }

    override fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.ideGetImportedModules(moduleName, res)
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

class S_IncludeDefinition(pos: S_Pos): S_Definition(pos, S_Modifiers(listOf())) {
    override fun compile(ctx: C_ModuleSourceContext): C_MidModuleMember? {
        ctx.msgCtx.error(kwPos, "include", "Include not supported since Rell ${RellVersions.MODULE_SYSTEM_VERSION_STR}")
        return null
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        // Do nothing.
    }
}

class S_GlobalConstantDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val type: S_Type?,
        val expr: S_Expr
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        val modTarget = C_ModifierTarget(C_ModifierTargetType.CONSTANT, name)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.CONSTANT, names.defId)
        val errorExpr = C_Utils.errorVExpr(defCtx.initExprCtx, expr.startPos)

        val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_GlobalConstantFunctionHeader.ERROR)
        val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_GlobalConstantBody.ERROR)
        val exprLate = C_LateInit(C_CompilerPass.EXPRESSIONS, errorExpr)

        val cDef = ctx.appCtx.addConstant(ctx.modCtx.rModuleKey, names) { constId ->
            val filePos = name.pos.toFilePos()
            val rDef = R_GlobalConstantDefinition(names, defCtx.initFrameGetter, constId, filePos, bodyLate.getter)
            val typePos = type?.pos ?: name.pos
            val varUid = ctx.modCtx.nextConstVarUid(names.qualifiedName)
            C_GlobalConstantDefinition(rDef, typePos, varUid, headerLate.getter, exprLate.getter)
        }

        ctx.nsBuilder.addConstant(name, cDef)

        if (name.str == "_") {
            ctx.msgCtx.error(name.pos, "def:const:wildcard", "Name '${name.str}' is a wildcard, not allowed for constants")
        }

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = C_FunctionUtils.compileGlobalConstantHeader(defCtx, name, names, type, expr, cDef.rDef.constId)
            headerLate.set(header)

            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                val rType = header.returnType()
                val cBody = header.constBody
                val vExpr = cBody?.compile()
                val rExpr = vExpr?.toRExpr() ?: C_Utils.errorRExpr()
                val rtValue = cBody?.constantValue(V_ConstantValueEvalContext())
                bodyLate.set(R_GlobalConstantBody(rType, rExpr, rtValue))
                exprLate.set(vExpr ?: errorExpr)
            }
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.CONSTANT)
    }
}
