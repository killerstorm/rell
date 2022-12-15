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
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind

class C_AttrHeader(
        val pos: S_Pos,
        val name: C_Name,
        val type: R_Type?,
        val isExplicitType: Boolean
) {
    val rName = name.rName
}

sealed class C_AttrHeaderHandle(val pos: S_Pos, val name: C_Name) {
    val rName = name.rName

    abstract fun compile(canInferType: Boolean, ideInfo: IdeSymbolInfo): C_AttrHeader
}

class C_NamedAttrHeaderHandle(
        private val nameHand: C_NameHandle,
        private val type: R_Type
): C_AttrHeaderHandle(nameHand.pos, nameHand.name) {
    override fun compile(canInferType: Boolean, ideInfo: IdeSymbolInfo): C_AttrHeader {
        nameHand.setIdeInfo(ideInfo)
        return C_AttrHeader(nameHand.pos, nameHand.name, type, true)
    }
}

class C_AnonAttrHeaderHandle(
        private val ctx: C_NamespaceContext,
        private val typeNameHand: C_QualifiedNameHandle,
        private val nullable: Boolean
): C_AttrHeaderHandle(typeNameHand.pos, typeNameHand.last.name) {
    override fun compile(canInferType: Boolean, ideInfo: IdeSymbolInfo): C_AttrHeader {
        val typeName = typeNameHand.qName
        val lastNameHand = typeNameHand.last

        val isExplicitType: Boolean
        val attrType: R_Type?

        if (typeNameHand.parts.size >= 2 || nullable) {
            isExplicitType = true
            attrType = compileType() ?: R_CtErrorType
        } else if (canInferType) {
            isExplicitType = false
            attrType = null
            lastNameHand.setIdeInfo(ideInfo)
        } else {
            isExplicitType = false
            attrType = if (lastNameHand.str == "_") {
                lastNameHand.setIdeInfo(ideInfo)
                C_Errors.errAttributeTypeUnknown(ctx.msgCtx, lastNameHand.name)
                R_CtErrorType
            } else {
                compileType() ?: R_CtErrorType
            }
        }

        return C_AttrHeader(typeName.pos, lastNameHand.name, attrType, isExplicitType)
    }

    private fun compileType(): R_Type? {
        val typeDef = ctx.getType(typeNameHand)
        val baseType = typeDef?.toRType(ctx.msgCtx, typeNameHand.pos)

        var type = when {
            baseType == null -> null
            nullable -> C_Types.toNullable(baseType)
            else -> baseType
        }

        val lastNameHand = typeNameHand.last

        if (type != null) {
            type = S_AttrHeader.checkUnitType(ctx, typeNameHand.pos, type, lastNameHand.name)
        }

        return type
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
        val ideInfo = C_AttrUtils.getIdeSymbolInfo(persistent, mutable, keyIndexKind)

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
    fun getIdeSymbolInfo(persistent: Boolean, mutable: Boolean, keyIndexKind: R_KeyIndexKind?): IdeSymbolInfo {
        val kind = if (persistent) {
            when (keyIndexKind) {
                null -> if (mutable) IdeSymbolKind.MEM_ENTITY_ATTR_NORMAL_VAR else IdeSymbolKind.MEM_ENTITY_ATTR_NORMAL
                R_KeyIndexKind.KEY -> if (mutable) IdeSymbolKind.MEM_ENTITY_ATTR_KEY_VAR else IdeSymbolKind.MEM_ENTITY_ATTR_KEY
                R_KeyIndexKind.INDEX -> if (mutable) IdeSymbolKind.MEM_ENTITY_ATTR_INDEX_VAR else IdeSymbolKind.MEM_ENTITY_ATTR_INDEX
            }
        } else {
            if (mutable) IdeSymbolKind.MEM_STRUCT_ATTR_VAR else IdeSymbolKind.MEM_STRUCT_ATTR
        }
        return IdeSymbolInfo(kind)
    }
}
