/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.def

import net.postchain.rell.compiler.ast.S_AttrHeader
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_LateInit
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.tools.api.*

class C_AttrHeader(
        val pos: S_Pos,
        val name: C_Name,
        val type: R_Type?,
        val isExplicitType: Boolean,
        val ideInfo: IdeSymbolInfo,
) {
    val rName = name.rName
}

abstract class C_AttrHeaderIdeData {
    abstract fun ideDef(ctx: C_DefinitionContext, pos: S_Pos, attrName: R_Name): C_IdeSymbolDef
}

class C_GlobalAttrHeaderIdeData(
    private val ideCat: IdeSymbolCategory,
    private val ideKind: IdeSymbolKind,
    private val defIdeInfo: IdeSymbolInfo?,
): C_AttrHeaderIdeData() {
    override fun ideDef(ctx: C_DefinitionContext, pos: S_Pos, attrName: R_Name): C_IdeSymbolDef {
        val ideDef = C_DefinitionBase.ideDef(pos, ctx.definitionType, ctx.defName, ideKind, ideCat to attrName)
        return if (defIdeInfo == null) ideDef else C_IdeSymbolDef(defIdeInfo, ideDef.refInfo)
    }
}

class C_LocalAttrHeaderIdeData(private val ideKind: IdeSymbolKind): C_AttrHeaderIdeData() {
    override fun ideDef(ctx: C_DefinitionContext, pos: S_Pos, attrName: R_Name): C_IdeSymbolDef {
        val defInfo = IdeSymbolInfo(ideKind)
        val ideLink = IdeLocalSymbolLink(pos)
        val refInfo = IdeSymbolInfo(ideKind, link = ideLink)
        return C_IdeSymbolDef(defInfo, refInfo)
    }
}

sealed class C_AttrHeaderHandle(val pos: S_Pos, val name: C_Name) {
    val rName = name.rName

    abstract fun compile(ctx: C_DefinitionContext, canInferType: Boolean, ideData: C_AttrHeaderIdeData): C_AttrHeader
}

class C_NamedAttrHeaderHandle(
        private val nameHand: C_NameHandle,
        private val type: R_Type
): C_AttrHeaderHandle(nameHand.pos, nameHand.name) {
    override fun compile(ctx: C_DefinitionContext, canInferType: Boolean, ideData: C_AttrHeaderIdeData): C_AttrHeader {
        val ideDef = ideData.ideDef(ctx, pos, nameHand.rName)
        nameHand.setIdeInfo(ideDef.defInfo)
        return C_AttrHeader(nameHand.pos, nameHand.name, type, true, ideDef.refInfo)
    }
}

class C_AnonAttrHeaderHandle(
        private val ctx: C_NamespaceContext,
        private val typeNameHand: C_QualifiedNameHandle,
        private val nullable: Boolean
): C_AttrHeaderHandle(typeNameHand.pos, typeNameHand.last.name) {
    override fun compile(ctx: C_DefinitionContext, canInferType: Boolean, ideData: C_AttrHeaderIdeData): C_AttrHeader {
        val typeName = typeNameHand.qName
        val lastNameHand = typeNameHand.last

        val ideDef = ideData.ideDef(ctx, lastNameHand.pos, lastNameHand.rName)

        val isExplicitType: Boolean
        val attrType: R_Type?
        val defIdeInfo = ideDef.defInfo

        if (typeNameHand.parts.size >= 2 || nullable) {
            isExplicitType = true
            attrType = compileType(defIdeInfo.defId)
        } else if (canInferType) {
            isExplicitType = false
            attrType = null
            lastNameHand.setIdeInfo(defIdeInfo)
        } else {
            isExplicitType = false
            attrType = if (lastNameHand.str == "_") {
                lastNameHand.setIdeInfo(defIdeInfo)
                C_Errors.errAttributeTypeUnknown(ctx.msgCtx, lastNameHand.name)
                R_CtErrorType
            } else {
                compileType(defIdeInfo.defId)
            }
        }

        return C_AttrHeader(typeName.pos, lastNameHand.name, attrType, isExplicitType, ideDef.refInfo)
    }

    private fun compileType(ideDefId: IdeSymbolId?): R_Type {
        val typeDef = ctx.getType(typeNameHand)
        if (ideDefId != null) {
            ctx.symCtx.setDefId(typeNameHand.last.pos, ideDefId)
        }

        val baseType = typeDef?.toRType(ctx.msgCtx, typeNameHand.pos)

        var type = when {
            baseType == null -> null
            nullable -> C_Types.toNullable(baseType)
            else -> baseType
        }

        val lastNameHand = typeNameHand.last

        if (type != null) {
            type = S_AttrHeader.checkUnitType(ctx.msgCtx, typeNameHand.pos, type, lastNameHand.name)
        }

        return type ?: R_CtErrorType
    }
}

class C_SysAttribute(
        val name: R_Name,
        val type: R_Type,
        val mutable: Boolean,
        val isKey: Boolean,
        val expr: R_Expr?,
        val sqlMapping: String,
        val canSetInCreate: Boolean
) {
    constructor(
            name: String,
            type: R_Type,
            mutable: Boolean = false,
            isKey: Boolean = false,
            expr: R_Expr? = null,
            sqlMapping: String = name,
            canSetInCreate: Boolean = true
    ): this(
            name = R_Name.of(name),
            type = type,
            mutable = mutable,
            isKey = isKey,
            expr = expr,
            sqlMapping = sqlMapping,
            canSetInCreate = canSetInCreate
    )

    fun compile(index: Int, persistent: Boolean): R_Attribute {
        val defaultValue = if (expr == null) null else R_DefaultValue(expr, false)
        val exprGetter = if (defaultValue == null) null else C_LateInit(C_CompilerPass.EXPRESSIONS, defaultValue).getter

        val keyIndexKind = if (isKey) R_KeyIndexKind.KEY else null
        val ideKind = C_AttrUtils.getIdeSymbolKind(persistent, mutable, keyIndexKind)
        val ideInfo = IdeSymbolInfo(ideKind)

        return R_Attribute(
                index,
                name,
                type,
                mutable = mutable,
                keyIndexKind = keyIndexKind,
                ideInfo = ideInfo,
                canSetInCreate = canSetInCreate,
                exprGetter = exprGetter,
                sqlMapping = sqlMapping
        )
    }
}

class C_CompiledAttribute(
        val defPos: S_Pos?,
        val rAttr: R_Attribute
)

object C_AttrUtils {
    fun getIdeSymbolKind(persistent: Boolean, mutable: Boolean, keyIndexKind: R_KeyIndexKind?): IdeSymbolKind {
        return if (persistent) {
            when (keyIndexKind) {
                null -> if (mutable) IdeSymbolKind.MEM_ENTITY_ATTR_NORMAL_VAR else IdeSymbolKind.MEM_ENTITY_ATTR_NORMAL
                R_KeyIndexKind.KEY -> if (mutable) IdeSymbolKind.MEM_ENTITY_ATTR_KEY_VAR else IdeSymbolKind.MEM_ENTITY_ATTR_KEY
                R_KeyIndexKind.INDEX -> if (mutable) IdeSymbolKind.MEM_ENTITY_ATTR_INDEX_VAR else IdeSymbolKind.MEM_ENTITY_ATTR_INDEX
            }
        } else {
            if (mutable) IdeSymbolKind.MEM_STRUCT_ATTR_VAR else IdeSymbolKind.MEM_STRUCT_ATTR
        }
    }
}
