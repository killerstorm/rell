/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.base.core.C_DefinitionName
import net.postchain.rell.base.compiler.base.core.C_QualifiedName
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprMember
import net.postchain.rell.base.compiler.base.expr.C_ValueExpr
import net.postchain.rell.base.compiler.base.namespace.C_FunctionExpr
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.namespace.C_NamespacePropertyContext
import net.postchain.rell.base.model.R_FunctionType
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.ide.IdeSymbolInfo

sealed class C_TypeStaticMember(
    protected val defName: C_DefinitionName,
    protected val simpleName: R_Name,
): C_TypeMember(simpleName) {
    abstract override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_TypeStaticMember
    abstract fun toExprMember(ctx: C_ExprContext, qName: C_QualifiedName, selfType: R_Type): C_ExprMember

    companion object {
        fun makeProperty(
            defName: C_DefinitionName,
            simpleName: R_Name,
            prop: C_NamespaceProperty,
            rType: R_Type,
        ): C_TypeStaticMember {
            return C_TypeStaticMember_Property(defName, simpleName, prop, rType)
        }

        fun makeFunction(
            defName: C_DefinitionName,
            simpleName: R_Name,
            functionNaming: C_GlobalFunctionNaming,
            fn: C_LibGlobalFunction,
        ): C_TypeStaticMember {
            return C_TypeStaticMember_Function(defName, simpleName, functionNaming, fn)
        }
    }
}

private class C_TypeStaticMember_Property(
    defName: C_DefinitionName,
    simpleName: R_Name,
    private val prop: C_NamespaceProperty,
    private val rType: R_Type,
): C_TypeStaticMember(defName, simpleName) {
    override fun kindMsg() = "property"
    override fun isValue() = true
    override fun isCallable() = rType is R_FunctionType

    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_TypeStaticMember {
        // Generics not supported.
        return this
    }

    override fun toExprMember(ctx: C_ExprContext, qName: C_QualifiedName, selfType: R_Type): C_ExprMember {
        val propCtx = C_NamespacePropertyContext(ctx)
        val vExpr = prop.toExpr(propCtx, qName)
        val expr = C_ValueExpr(vExpr)
        return C_ExprMember(expr, prop.ideInfo)
    }
}

private class C_TypeStaticMember_Function(
    defName: C_DefinitionName,
    simpleName: R_Name,
    private val functionNaming: C_GlobalFunctionNaming,
    private val fn: C_LibGlobalFunction,
): C_TypeStaticMember(defName, simpleName) {
    override fun kindMsg() = "function"
    override fun isValue() = false
    override fun isCallable() = true

    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_TypeStaticMember {
        val functionNaming2 = functionNaming.replaceSelfType(rep.selfType)
        val fn2 = fn.replaceTypeParams(rep)
        return if (functionNaming2 == functionNaming && fn2 === fn) this else {
            C_TypeStaticMember_Function(defName, simpleName, functionNaming2, fn2)
        }
    }

    override fun toExprMember(ctx: C_ExprContext, qName: C_QualifiedName, selfType: R_Type): C_ExprMember {
        val lazyName = LazyPosString(qName.last.pos, functionNaming.fullNameLazy)
        val expr = C_FunctionExpr(lazyName, fn)
        return C_ExprMember(expr, IdeSymbolInfo.DEF_FUNCTION_SYSTEM)
    }
}
