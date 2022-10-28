/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.*
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.modifier.*
import net.postchain.rell.compiler.base.module.*
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.namespace.C_NamespaceMemberBase
import net.postchain.rell.compiler.base.namespace.C_NamespacePropertyContext
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.vexpr.V_ConstantValueEvalContext
import net.postchain.rell.lib.C_Lib_OpContext
import net.postchain.rell.model.*
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.utils.toGtv
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

sealed class S_AttrHeader: S_Node() {
    abstract fun discoverVar(): R_Name
    abstract fun compile(ctx: C_NamespaceContext): C_AttrHeaderHandle
    abstract fun ideOutlineTreeNodeName(): S_Name

    fun compile(ctx: C_NamespaceContext, canInferType: Boolean, ideInfo: IdeSymbolInfo): C_AttrHeader {
        val handle = compile(ctx)
        return handle.compile(canInferType, ideInfo)
    }

    companion object {
        fun checkUnitType(ctx: C_NamespaceContext, pos: S_Pos, rType: R_Type, cName: C_Name): R_Type {
            return C_Types.checkNotUnit(ctx.msgCtx, pos, rType, cName.str) { "attr_var" toCodeMsg "attribute or variable" }
        }
    }
}

class S_NamedAttrHeader(private val name: S_Name, private val type: S_Type): S_AttrHeader() {
    override fun discoverVar(): R_Name {
        return name.getRNameSpecial()
    }

    override fun compile(ctx: C_NamespaceContext): C_AttrHeaderHandle {
        val nameHand = name.compile(ctx)
        val rType = type.compileOpt(ctx) ?: R_CtErrorType
        val rResType = checkUnitType(ctx, type.pos, rType, nameHand.name)
        return C_NamedAttrHeaderHandle(nameHand, rResType)
    }

    override fun ideOutlineTreeNodeName() = name
}

class S_AnonAttrHeader(private val typeName: S_QualifiedName, private val nullable: Boolean): S_AttrHeader() {
    override fun discoverVar(): R_Name {
        return typeName.last.getRNameSpecial()
    }

    override fun compile(ctx: C_NamespaceContext): C_AttrHeaderHandle {
        val typeNameHand = typeName.compile(ctx)
        return C_AnonAttrHeaderHandle(ctx, typeNameHand, nullable)
    }

    override fun ideOutlineTreeNodeName() = typeName.last
}

sealed class S_RelClause: S_Node() {
    abstract fun compile(ctx: C_EntityContext)
    abstract fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder)
}

class S_AttributeClause(private val attr: S_AttributeDefinition): S_RelClause() {
    override fun compile(ctx: C_EntityContext) {
        val attrHeader = attr.header.compile(ctx.defCtx.nsCtx)
        ctx.addAttribute(attr, attrHeader, true)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val name = attr.header.ideOutlineTreeNodeName()
        b.node(this, name, IdeOutlineNodeType.ATTRIBUTE)
    }
}

class S_KeyIndexClause(val pos: S_Pos, val kind: R_KeyIndexKind, val attrs: List<S_AttributeDefinition>): S_RelClause() {
    override fun compile(ctx: C_EntityContext) {
        val cAttrs = attrs.map {
            val cHeader = it.header.compile(ctx.defCtx.nsCtx)
            AttrRec(cHeader, it)
        }

        for (attr in cAttrs) {
            ctx.addAttribute(attr.sAttr, attr.header, false)
        }

        val nameSet = mutableSetOf<R_Name>()
        for (attr in cAttrs) {
            val name = attr.header.rName
            C_Errors.check(ctx.msgCtx, nameSet.add(name), attr.header.pos) {
                "entity_keyindex_dup:$name" toCodeMsg "Duplicate attribute: '$name'"
            }
        }

        if (cAttrs.size > 1) {
            cAttrs.all { it.sAttr.checkMultiAttrKeyIndex(ctx.msgCtx, kind, it.header.rName) }
        }

        val attrNames = cAttrs.map { it.header.rName }

        when (kind) {
            R_KeyIndexKind.KEY -> ctx.addKey(pos, attrNames)
            R_KeyIndexKind.INDEX -> ctx.addIndex(pos, attrNames)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (attr in attrs) {
            val name = attr.header.ideOutlineTreeNodeName()
            b.node(attr, name, IdeOutlineNodeType.KEY_INDEX)
        }
    }

    private class AttrRec(val header: C_AttrHeaderHandle, val sAttr: S_AttributeDefinition)
}

class S_AttributeDefinition(val mutablePos: S_Pos?, val header: S_AttrHeader, val expr: S_Expr?): S_Node() {
    fun checkMultiAttrKeyIndex(msgCtx: C_MessageContext, kind: R_KeyIndexKind, name: R_Name): Boolean {
        return if (mutablePos != null) {
            errKeyIndexTooComplex(msgCtx, kind, name, mutablePos, "mutable")
            false
        } else if (expr != null) {
            errKeyIndexTooComplex(msgCtx, kind, name, expr.startPos, "expr")
            false
        } else {
            true
        }
    }

    private fun errKeyIndexTooComplex(
            msgCtx: C_MessageContext,
            kind: R_KeyIndexKind,
            name: R_Name,
            pos: S_Pos,
            reasonCode: String
    ) {
        msgCtx.error(pos, "attr:key_index:too_complex:$name:$kind:$reasonCode",
                "${kind.nameMsg.capital} definition is too complex; write each attribute definition separately " +
                        "and use only attribute names in the index clause")
    }
}

abstract class S_Definition(val kwPos: S_Pos, val modifiers: S_Modifiers): S_Node() {
    val startPos = modifiers.pos ?: kwPos

    abstract fun compile(ctx: C_ModuleDefinitionContext): C_MidModuleMember?

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
    final override fun compile(ctx: C_ModuleDefinitionContext): C_MidModuleMember {
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

        val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_ENTITY)
        val cName = name.compile(ctx, ideInfo)

        if (body == null) {
            compileHeader(ctx, cName, ideInfo)
            return
        }

        val mods = C_ModifierValues(C_ModifierTargetType.ENTITY, cName)
        val modExternal = mods.field(C_ModifierFields.EXTERNAL_CHAIN)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        val modLog = mods.field(C_ModifierFields.LOG)
        val modDeprecated = mods.field(C_ModifierFields.DEPRECATED)
        modifiers.compile(ctx, mods)

        val extChain = ctx.externalChain(modExternal)
        val extChainRef = extChain?.ref
        val isExternalChain = extChainRef != null
        val rFlags = compileFlags(ctx, isExternalChain, modLog.hasValue())

        val cDefBase = ctx.defBase(cName, extChain)

        val isExternalChainOrModule = isExternalChain || ctx.modCtx.external

        C_Errors.check(!isExternalChainOrModule || !ctx.mountName.isEmpty() || cName.str !in HEADER_ENTITIES, cName.pos) {
            "def_entity_external_unallowed:$cName" toCodeMsg
            "External entity '$cName' can be declared only without body (as entity header)"
        }

        C_Errors.check(!isExternalChainOrModule || rFlags.log, cName.pos) {
            "def_entity_external_nolog:${cDefBase.simpleName}" toCodeMsg
            "External entity '${cDefBase.simpleName}' must have '${C_Constants.LOG_ANNOTATION}' annotation"
        }

        val mountName = ctx.mountName(modMount, cName)
        val rMapping = if (extChainRef == null) {
            R_EntitySqlMapping_Regular(mountName)
        } else {
            R_EntitySqlMapping_External(mountName, extChainRef)
        }

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.ENTITY, cDefBase.defId)
        val defBase = cDefBase.rBase(defCtx.initFrameGetter)

        val rExternalEntity = if (extChainRef == null) null else R_ExternalEntity(extChainRef, true)

        val rEntity = C_Utils.createEntity(
                ctx.appCtx,
                C_DefinitionType.ENTITY,
                defBase,
                cName.rName,
                mountName,
                rFlags,
                rMapping,
                rExternalEntity
        )

        ctx.appCtx.defsAdder.addEntity(C_Entity(cName.pos, rEntity))
        ctx.nsBuilder.addEntity(cDefBase.nsMemBase(ideInfo, modDeprecated), cName, rEntity)
        ctx.mntBuilder.addEntity(cName.pos, rEntity)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(defCtx, cName, extChain, rEntity, body)
        }
    }

    private fun compileHeader(ctx: C_MountContext, cName: C_Name, ideInfo: IdeSymbolInfo) {
        var err = false

        val mods = C_ModifierValues(C_ModifierTargetType.ENTITY, cName)
        val modExternal = mods.field(C_ModifierFields.EXTERNAL_CHAIN)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        val modLog = mods.field(C_ModifierFields.LOG)
        modifiers.compile(ctx, mods)

        checkHeaderNoModifier(ctx, modMount)
        checkHeaderNoModifier(ctx, modLog)

        if (annotations.isNotEmpty()) {
            ctx.msgCtx.error(name.pos, "def_entity_hdr_annotations:$name",
                    "Annotations not allowed for entity header '$name'")
            err = true
        }

        val entGetter = HEADER_ENTITIES[cName.str]
        if (entGetter == null) {
            val entities = HEADER_ENTITIES.keys.joinToString()
            ctx.msgCtx.error(name.pos, "def_entity_hdr_name:$name",
                    "Entity header declarations allowed only for entities: $entities")
            err = true
        }

        val extChain = ctx.externalChain(modExternal)
        if (extChain == null && !ctx.modCtx.external) {
            ctx.msgCtx.error(name.pos, "def_entity_hdr_noexternal:$name", "Entity header must be declared as external")
            return
        }

        if (err || entGetter == null) {
            return
        }

        val sysDefs = extChain?.sysDefs ?: ctx.modCtx.sysDefs
        val rEntity = entGetter(sysDefs)
        val cNsMemBase = C_NamespaceMemberBase(rEntity.cDefName, ideInfo, null)
        ctx.nsBuilder.addEntity(cNsMemBase, cName, rEntity, addToModule = false)
    }

    private fun checkHeaderNoModifier(ctx: C_MountContext, modValue: C_ModifierValue<*>) {
        val link = modValue.modLink()
        if (link != null) {
            val codeMsg = link.key.codeMsg()
            val code = "def_entity_hdr:modifier:${codeMsg.code}"
            val msg = "${codeMsg.msg.capitalize()} not allowed for an entity header"
            ctx.msgCtx.error(link.pos, code, msg)
        }
    }

    private fun compileFlags(ctx: C_MountContext, externalChain: Boolean, modLog: Boolean): R_EntityFlags {
        val set = mutableSetOf<String>()
        var log = modLog

        if (log) {
            set.add(C_Constants.LOG_ANNOTATION)
        }

        for (ann in annotations) {
            val nameHand = ann.compile(ctx)
            val cAnn = nameHand.name
            ctx.msgCtx.warning(ann.pos, "ann:legacy:$ann", "Deprecated annotation syntax; use @$ann instead")

            if (!set.add(cAnn.str)) {
                ctx.msgCtx.error(ann.pos, "entity_ann_dup:$ann", "Duplicate annotation: '$ann'")
            }

            val ideKind: IdeSymbolKind

            if (cAnn.str == C_Constants.LOG_ANNOTATION) {
                log = true
                ideKind = IdeSymbolKind.MOD_ANNOTATION_LEGACY
            } else {
                ctx.msgCtx.error(ann.pos, "entity_ann_bad:$ann", "Invalid annotation: '$ann'")
                ideKind = IdeSymbolKind.UNKNOWN
            }

            nameHand.setIdeInfo(IdeSymbolInfo(ideKind))
        }

        return R_EntityFlags(
                isObject = false,
                canCreate = !externalChain,
                canUpdate = !log && !externalChain && !ctx.modCtx.external,
                canDelete = !log && !externalChain && !ctx.modCtx.external,
                gtv = true,
                log = log
        )
    }

    private fun membersPass(
            defCtx: C_DefinitionContext,
            cName: C_Name,
            extChain: C_ExternalChain?,
            rEntity: R_EntityDefinition,
            clauses: List<S_RelClause>
    ) {
        val sysAttrs = mutableListOf<C_SysAttribute>()

        if (rEntity.flags.log) {
            val sysDefs = extChain?.sysDefs ?: defCtx.modCtx.sysDefs
            val txType = sysDefs.transactionEntity.type
            val expr = if (extChain == null) {
                val propCtx = C_NamespacePropertyContext(defCtx.initExprCtx)
                C_Lib_OpContext.transactionRExpr(propCtx, cName.pos)
            } else {
                C_ExprUtils.errorRExpr(txType, "Trying to initialize transaction for external entity '${rEntity.appLevelName}'")
            }
            sysAttrs.add(C_SysAttribute("transaction", txType, expr = expr, mutable = false, canSetInCreate = false))
        }

        val entCtx = C_EntityContext(defCtx, cName.str, rEntity.flags.log, sysAttrs, persistent = true)

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

        val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_OBJECT)
        val cName = name.compile(ctx, ideInfo)

        val mods = C_ModifierValues(C_ModifierTargetType.OBJECT, cName)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        val modDeprecated = mods.field(C_ModifierFields.DEPRECATED)
        modifiers.compile(ctx, mods)

        val cDefBase = ctx.defBase(cName)
        val mountName = ctx.mountName(modMount, cName)
        val sqlMapping = R_EntitySqlMapping_Regular(mountName)

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OBJECT, cDefBase.defId)
        val defBase = cDefBase.rBase(defCtx.initFrameGetter)

        val rEntity = C_Utils.createEntity(
                ctx.appCtx,
                C_DefinitionType.OBJECT,
                defBase,
                cName.rName,
                mountName,
                entityFlags,
                sqlMapping,
                null
        )

        val rObject = R_ObjectDefinition(defBase, rEntity)

        ctx.appCtx.defsAdder.addObject(rObject)
        ctx.nsBuilder.addObject(cDefBase.nsMemBase(ideInfo, modDeprecated), cName, rObject)
        ctx.mntBuilder.addObject(name, rObject)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(defCtx, cName, rObject)
        }
    }

    private fun membersPass(defCtx: C_DefinitionContext, cName: C_Name, rObject: R_ObjectDefinition) {
        val entCtx = C_EntityContext(defCtx, cName.str, false, listOf(), persistent = true)

        for (attr in attrs) {
            val attrHeader = attr.header.compile(defCtx.nsCtx)
            entCtx.addAttribute(attr, attrHeader, true)
        }

        val body = entCtx.createEntityBody()
        C_Utils.setEntityBody(rObject.rEntity, body)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.OBJECT)
        for (attr in attrs) {
            val attrName = attr.header.ideOutlineTreeNodeName()
            sub.node(attr, attrName, IdeOutlineNodeType.ATTRIBUTE)
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

        val ideInfo = IdeSymbolInfo.DEF_STRUCT
        val cName = name.compile(ctx, ideInfo)

        val mods = C_ModifierValues(C_ModifierTargetType.STRUCT, cName)
        val modDeprecated = mods.field(C_ModifierFields.DEPRECATED)
        modifiers.compile(ctx, mods)

        val cDefBase = ctx.defBase(cName)
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.STRUCT, cDefBase.defId)
        val defBase = cDefBase.rBase(defCtx.initFrameGetter)

        val rStruct = R_Struct(cDefBase.appLevelName, cDefBase.appLevelName.toGtv(), defBase.initFrameGetter, mirrorStructs = null, ideInfo = ideInfo)
        val rStructDef = R_StructDefinition(defBase, rStruct)

        val attrsLate = C_LateInit<List<C_CompiledAttribute>>(C_CompilerPass.MEMBERS, immListOf())
        val cStruct = C_Struct(cName, rStructDef, attrsLate.getter)

        ctx.appCtx.defsAdder.addStruct(rStruct)
        ctx.nsBuilder.addStruct(cDefBase.nsMemBase(ideInfo, modDeprecated), cStruct)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            membersPass(defCtx, cName, cStruct, attrsLate)
        }
    }

    private fun membersPass(
            defCtx: C_DefinitionContext,
            cName: C_Name,
            cStruct: C_Struct,
            attrsLate: C_LateInit<List<C_CompiledAttribute>>
    ) {
        val entCtx = C_EntityContext(defCtx, cName.str, false, immListOf(), persistent = false)

        for (attr in attrs) {
            val attrHeader = attr.header.compile(defCtx.nsCtx)
            entCtx.addAttribute(attr, attrHeader, true)
        }

        val cAttributes = entCtx.createStructBody()
        attrsLate.set(cAttributes.map { it.value }.toImmList())

        val rAttributes = cAttributes.mapValues { it.value.rAttr }.toImmMap()
        cStruct.structDef.struct.setAttributes(rAttributes)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val sub = b.node(this, name, IdeOutlineNodeType.STRUCT)
        for (attr in attrs) {
            val attrName = attr.header.ideOutlineTreeNodeName()
            sub.node(attr, attrName, IdeOutlineNodeType.ATTRIBUTE)
        }
    }
}

class S_EnumDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val attrs: List<S_Name>
): S_Definition(pos, modifiers) {
    // TODO Better design: compile external module in two steps, all definitions must be processed same way.
    override fun compile(ctx: C_ModuleDefinitionContext): C_MidModuleMember {
        val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_ENUM)
        val cName = name.compile(ctx.symCtx, ideInfo)

        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.symCtx)
        val mods = C_ModifierValues(C_ModifierTargetType.ENUM, cName)
        val modDeprecated = mods.field(C_ModifierFields.DEPRECATED)
        modifiers.compile(modifierCtx, mods)

        val set = mutableSetOf<String>()
        val rAttrs = mutableListOf<R_EnumAttr>()

        for (attr in attrs) {
            val attrIdeInfo = IdeSymbolInfo(IdeSymbolKind.MEM_ENUM_VALUE)
            val cAttrName = attr.compile(ctx.symCtx, attrIdeInfo)
            if (set.add(cAttrName.str)) {
                rAttrs.add(R_EnumAttr(cAttrName.rName, rAttrs.size, attrIdeInfo))
            } else {
                ctx.msgCtx.error(attr.pos, "enum_dup:$attr", "Duplicate enum value: '$cAttrName'")
            }
        }

        val fullName = C_StringQualifiedName.ofRNames(ctx.namespacePath.parts + cName.rName)
        val cDefBase = C_Utils.createDefBase(R_ModuleKey(ctx.moduleName, null), fullName)
        val defBase = cDefBase.rBase(R_CallFrame.NONE_INIT_FRAME_GETTER)

        val rEnum = R_EnumDefinition(defBase, rAttrs.toList())
        val memBase = cDefBase.nsMemBase(ideInfo, modDeprecated)
        return C_MidModuleMember_Enum(cName, rEnum, memBase)
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
        private val qualifiedName: S_QualifiedName?,
        private val definitions: List<S_Definition>
): S_Definition(pos, modifiers) {
    override fun compile(ctx: C_ModuleDefinitionContext): C_MidModuleMember {
        val cQualifiedName = qualifiedName?.compile(ctx.symCtx, IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE))
        val subCtx = ctx.namespace(cQualifiedName?.toPath() ?: C_RNamePath.EMPTY)
        val midMembers = definitions.mapNotNull { it.compile(subCtx) }
        return C_MidModuleMember_Namespace(modifiers, cQualifiedName, midMembers)
    }

    override fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.ideGetImportedModules(moduleName, res)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        var sub = b
        for (name in qualifiedName?.parts ?: immListOf()) {
            sub = sub.node(this, name, IdeOutlineNodeType.NAMESPACE)
        }
        for (def in definitions) {
            def.ideBuildOutlineTree(sub)
        }
    }
}

class S_IncludeDefinition(pos: S_Pos): S_Definition(pos, S_Modifiers(listOf())) {
    override fun compile(ctx: C_ModuleDefinitionContext): C_MidModuleMember? {
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
        val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_CONSTANT)
        val cName = name.compile(ctx, ideInfo)

        val mods = C_ModifierValues(C_ModifierTargetType.CONSTANT, cName)
        val modDeprecated = mods.field(C_ModifierFields.DEPRECATED)
        modifiers.compile(ctx, mods)

        val cDefBase = ctx.defBase(cName)
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.CONSTANT, cDefBase.defId)
        val errorExpr = C_ExprUtils.errorVExpr(defCtx.initExprCtx, expr.startPos)

        val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_GlobalConstantFunctionHeader.ERROR)
        val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_GlobalConstantBody.ERROR)
        val exprLate = C_LateInit(C_CompilerPass.EXPRESSIONS, errorExpr)

        val cDef = ctx.appCtx.addConstant(ctx.modCtx.rModuleKey, cDefBase.defName) { constId ->
            val filePos = cName.pos.toFilePos()
            val defBase = cDefBase.rBase(defCtx.initFrameGetter)
            val rDef = R_GlobalConstantDefinition(defBase, constId, filePos, bodyLate.getter)
            val typePos = type?.pos ?: cName.pos
            val varUid = ctx.modCtx.nextConstVarUid(cDefBase.qualifiedName)
            C_GlobalConstantDefinition(rDef, typePos, varUid, headerLate.getter, exprLate.getter)
        }

        ctx.nsBuilder.addConstant(cDefBase.nsMemBase(ideInfo, modDeprecated), cName, cDef)

        if (cName.str == "_") {
            ctx.msgCtx.error(cName.pos, "def:const:wildcard", "Name '$cName' is a wildcard, not allowed for constants")
        }

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = C_FunctionUtils.compileGlobalConstantHeader(defCtx, cName, cDefBase.defName, type, expr, cDef.rDef.constId)
            headerLate.set(header)

            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                val rType = header.returnType()
                val cBody = header.constBody
                val vExpr = cBody?.compile()
                val rExpr = vExpr?.toRExpr() ?: C_ExprUtils.errorRExpr()
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
