/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.def

import net.postchain.rell.compiler.ast.S_AttributeDefinition
import net.postchain.rell.compiler.ast.S_EntityDefinition
import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.expr.C_EntityAttrRef
import net.postchain.rell.compiler.base.expr.C_ExprHint
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolCategory
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.toImmMap
import net.postchain.rell.utils.toImmSet

private class C_EntityAttributeClause(
        private val defCtx: C_DefinitionContext,
        private val sysAttr: C_SysAttribute?,
        private val persistent: Boolean
) {
    private val msgCtx = defCtx.msgCtx

    private val defs = mutableListOf<C_AttributeDefinition>()

    fun addDefinition(attrDef: S_AttributeDefinition, attrHeader: C_AttrHeaderHandle, primary: Boolean) {
        val cDef = C_AttributeDefinition(attrDef, attrHeader, primary)
        defs.add(cDef)
    }

    fun compile(index: Int, keyIndexKind: R_KeyIndexKind?): C_CompiledAttribute {
        val (primaryDefs, secondaryDefs) = defs.partition { it.primary }

        if (sysAttr != null) {
            val ideKind = C_AttrUtils.getIdeSymbolKind(persistent, sysAttr.mutable, keyIndexKind)
            val rAttr = sysAttr.compile(index, persistent)
            processOtherDefs(primaryDefs, secondaryDefs, sysAttr.type, ideKind, rAttr.ideInfo)
            return C_CompiledAttribute(null, rAttr)
        }

        val mainDef = primaryDefs.firstOrNull() ?: secondaryDefs.first()
        val conflictDefs = primaryDefs.drop(1)
        val otherDefs = secondaryDefs.filter { it !== mainDef }

        val mutable = mainDef.attrDef.mutablePos != null
        val ideKind = C_AttrUtils.getIdeSymbolKind(persistent, mutable, keyIndexKind)

        val ideData = C_GlobalAttrHeaderIdeData(IdeSymbolCategory.ATTRIBUTE, ideKind, null)
        val mainHeader = mainDef.attrHeader.compile(defCtx, false, ideData)
        val type = mainHeader.type ?: R_CtErrorType

        checkAttrType(mainDef, type)
        processOtherDefs(conflictDefs, otherDefs, type, ideKind, mainHeader.ideInfo)

        if (defCtx.definitionType.isEntityLike()) {
            S_EntityDefinition.checkAttrNameLen(msgCtx, mainDef.name)
        }

        val exprGetter = processAttrExpr(mainDef.name, mainDef.attrDef.expr, type)

        val rAttr = R_Attribute(
                index,
                mainDef.name.rName,
                type,
                mutable = mutable,
                keyIndexKind = keyIndexKind,
                ideInfo = mainHeader.ideInfo,
                exprGetter = exprGetter
        )

        return C_CompiledAttribute(mainDef.attrHeader.pos, rAttr)
    }

    private fun processOtherDefs(
            conflictDefs: List<C_AttributeDefinition>,
            secondaryDefs: List<C_AttributeDefinition>,
            type: R_Type,
            ideKind: IdeSymbolKind,
            mainIdeInfo: IdeSymbolInfo,
    ) {
        for (def in conflictDefs) {
            val ideData = C_GlobalAttrHeaderIdeData(IdeSymbolCategory.ATTRIBUTE, ideKind, null)
            val attrHeader = def.attrHeader.compile(defCtx, false, ideData)
            processAttrExpr(def.name, def.attrDef.expr, attrHeader.type)
            C_Errors.errDuplicateAttribute(msgCtx, def.name)
        }

        for (def in secondaryDefs) {
            processSecondaryDef(def, type, ideKind, mainIdeInfo)
        }
    }

    private fun processAttrExpr(name: C_Name, expr: S_Expr?, type: R_Type?): C_LateGetter<R_DefaultValue>? {
        if (expr == null) {
            return null
        }

        val exprType = type ?: R_CtErrorType
        val errValue = R_DefaultValue(C_ExprUtils.errorRExpr(exprType), false)
        val late = C_LateInit(C_CompilerPass.EXPRESSIONS, errValue)

        defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val exprCtx = defCtx.initExprCtx
            val vExpr0 = expr.compile(exprCtx, C_ExprHint.ofType(exprType)).value()
            val adapter = C_Types.adaptSafe(msgCtx, exprType, vExpr0.type, name.pos) {
                "attr_type:$name" toCodeMsg "Default value type mismatch for '$name'"
            }
            val vExpr = adapter.adaptExpr(exprCtx, vExpr0)
            val rExpr = vExpr.toRExpr()
            late.set(R_DefaultValue(rExpr, vExpr0.info.hasDbModifications))
        }

        return late.getter
    }

    private fun processSecondaryDef(def: C_AttributeDefinition, rPrimaryType: R_Type, ideKind: IdeSymbolKind, mainIdeInfo: IdeSymbolInfo) {
        val ideData = C_GlobalAttrHeaderIdeData(IdeSymbolCategory.ATTRIBUTE, ideKind, mainIdeInfo)
        val header = def.attrHeader.compile(defCtx, true, ideData)

        if (header.type != null && header.type != rPrimaryType && header.isExplicitType) {
            C_Errors.errTypeMismatch(msgCtx, def.name.pos, header.type, rPrimaryType) {
                "entity:attr:type_diff" toCodeMsg
                        "Type of attribute '${def.name}' differs from the primary definition"
            }
        }

        if (def.attrDef.mutablePos != null) {
            msgCtx.error(def.attrDef.mutablePos, "entity:attr:mutable_not_primary:${def.name}",
                    "Mutability can be specified only in the primary definition of the attribute '${def.name}'")
        }

        if (def.attrDef.expr != null) {
            msgCtx.error(def.attrDef.expr.startPos, "entity:attr:expr_not_primary:${def.name}",
                    "Default value can be specified only in the primary definition of the attribute '${def.name}'")
        }

        checkAttrType(def, header.type)
        processAttrExpr(def.name, def.attrDef.expr, header.type)
    }

    private fun checkAttrType(attr: C_AttributeDefinition, type: R_Type?) {
        if (defCtx.definitionType.isEntityLike() && type != null && !type.sqlAdapter.isSqlCompatible()) {
            val name = attr.name
            val typeStr = type.strCode()
            msgCtx.error(name.pos, "entity_attr_type:$name:$typeStr", "Attribute '$name' has unallowed type: $typeStr")
        }
    }

    private class C_AttributeDefinition(
            val attrDef: S_AttributeDefinition,
            val attrHeader: C_AttrHeaderHandle,
            val primary: Boolean
    ) {
        val name = attrHeader.name
    }
}

class C_EntityContext(
        val defCtx: C_DefinitionContext,
        private val entityName: String,
        private val logAnnotation: Boolean,
        sysAttributes: List<C_SysAttribute>,
        private val persistent: Boolean
) {
    val msgCtx = defCtx.msgCtx

    private val attrMap = mutableMapOf<R_Name, C_EntityAttributeClause>()

    private val keys = mutableListOf<R_Key>()
    private val indices = mutableListOf<R_Index>()
    private val uniqueKeys = mutableSetOf<Set<R_Name>>()
    private val uniqueIndices = mutableSetOf<Set<R_Name>>()

    init {
        for (sysAttr in sysAttributes) {
            attrMap[sysAttr.name] = C_EntityAttributeClause(defCtx, sysAttr, persistent)
        }
    }

    fun addAttribute(attrDef: S_AttributeDefinition, attrHeader: C_AttrHeaderHandle, primary: Boolean) {
        validateAttr(attrDef, attrHeader)

        val clause = attrMap.computeIfAbsent(attrHeader.rName) { C_EntityAttributeClause(defCtx, null, persistent) }
        clause.addDefinition(attrDef, attrHeader, primary)
    }

    private fun validateAttr(attrDef: S_AttributeDefinition, attrHeader: C_AttrHeaderHandle) {
        val nameStr = attrHeader.rName.str

        val defType = defCtx.definitionType
        if (defType.isEntityLike() && !C_EntityAttrRef.isAllowedRegularAttrName(nameStr)) {
            msgCtx.error(attrHeader.pos, "unallowed_attr_name:$nameStr", "Unallowed attribute name: '$nameStr'")
        }

        if (attrDef.mutablePos != null && logAnnotation) {
            val ann = C_Constants.LOG_ANNOTATION
            msgCtx.error(attrHeader.pos, "entity_attr_mutable_log:$entityName:$nameStr",
                    "Entity '$entityName' cannot have mutable attributes because of the '$ann' annotation")
        }

        if (defType == C_DefinitionType.OBJECT && attrDef.expr == null) {
            msgCtx.error(attrHeader.pos, "object_attr_novalue:$entityName:$nameStr",
                    "Object attribute '$entityName.$nameStr' must have a default value")
        }
    }

    fun addKey(pos: S_Pos, attrs: List<R_Name>) {
        addUniqueKeyIndex(pos, uniqueKeys, attrs, R_KeyIndexKind.KEY)
        keys.add(R_Key(attrs))
    }

    fun addIndex(pos: S_Pos, attrs: List<R_Name>) {
        addUniqueKeyIndex(pos, uniqueIndices, attrs, R_KeyIndexKind.INDEX)
        indices.add(R_Index(attrs))
    }

    fun createEntityBody(): R_EntityBody {
        val cAttributes = compileAttributes()
        val rAttributes = cAttributes.mapValues { it.value.rAttr }
        return R_EntityBody(keys.toList(), indices.toList(), rAttributes)
    }

    fun createStructBody(): Map<R_Name, C_CompiledAttribute> {
        return compileAttributes()
    }

    private fun compileAttributes(): Map<R_Name, C_CompiledAttribute> {
        val keyAttrs = keys.flatMap { it.attribs }.toImmSet()
        val indexAttrs = indices.flatMap { it.attribs }.toImmSet()

        val cAttrs = mutableListOf<C_CompiledAttribute>()

        for ((name, attr) in attrMap) {
            val keyIndexKind = when {
                name in keyAttrs -> R_KeyIndexKind.KEY
                name in indexAttrs -> R_KeyIndexKind.INDEX
                else -> null
            }
            val compiledAttr = attr.compile(cAttrs.size, keyIndexKind)
            cAttrs.add(compiledAttr)
        }

        return cAttrs.map { it.rAttr.rName to it }.toMap().toImmMap()
    }

    private fun addUniqueKeyIndex(pos: S_Pos, set: MutableSet<Set<R_Name>>, names: List<R_Name>, kind: R_KeyIndexKind) {
        if (defCtx.definitionType == C_DefinitionType.OBJECT) {
            msgCtx.error(pos, "object:key_index:${entityName}:$kind", "Object cannot have ${kind.nameMsg.normal}")
            return
        }

        val nameSet = names.toSet()
        if (!set.add(nameSet)) {
            val nameLst = names.sorted()
            val errCode = "entity:key_index:dup_attr:$kind"
            val errMsg = "Duplicate ${kind.nameMsg.normal}"
            msgCtx.error(pos, "$errCode:${nameLst.joinToString(",")}", "$errMsg: ${nameLst.joinToString()}")
        }
    }
}
