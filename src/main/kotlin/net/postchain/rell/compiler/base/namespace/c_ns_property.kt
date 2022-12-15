/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.namespace

import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_SysFunction
import net.postchain.rell.model.R_Type
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeSymbolInfo

class C_NamespacePropertyContext(val exprCtx: C_ExprContext) {
    val defCtx = exprCtx.defCtx
    val globalCtx = defCtx.globalCtx
    val msgCtx = defCtx.msgCtx
    val modCtx = defCtx.modCtx
}

abstract class C_NamespaceProperty(val ideInfo: IdeSymbolInfo) {
    abstract fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr
}

class C_NamespaceProperty_RtValue(ideInfo: IdeSymbolInfo, private val value: Rt_Value): C_NamespaceProperty(ideInfo) {
    override fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr {
        return V_ConstantValueExpr(ctx.exprCtx, name.pos, value)
    }
}

class C_NamespaceProperty_SysFunction(
    ideInfo: IdeSymbolInfo,
    private val resultType: R_Type,
    private val fn: R_SysFunction,
    private val pure: Boolean,
): C_NamespaceProperty(ideInfo) {
    override fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr {
        return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, resultType, fn, name, pure = pure)
    }
}
