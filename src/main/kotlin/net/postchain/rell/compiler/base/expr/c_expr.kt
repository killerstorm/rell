/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.def.C_StructGlobalFunction
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ObjectExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.tools.api.IdeSymbolInfo

class C_ExprHint(val typeHint: C_TypeHint, val callable: Boolean = false) {
    companion object {
        val DEFAULT = C_ExprHint(C_TypeHint.NONE, false)
        val DEFAULT_CALLABLE = C_ExprHint(C_TypeHint.NONE, true)

        fun ofType(type: R_Type?) = C_ExprHint(C_TypeHint.ofType(type))
    }
}

enum class C_ExprKind(val code: String) {
    VALUE("expression"),
    NAMESPACE("namespace"),
    TYPE("type"),
    STRUCT("struct"),
    OBJECT("object"),
    ENUM("enum"),
    FUNCTION("function")
}

class C_ExprMember(val expr: C_Expr, val ideInfo: IdeSymbolInfo)

abstract class C_Expr {
    abstract fun kind(): C_ExprKind
    abstract fun startPos(): S_Pos

    open fun value(): V_Expr = throw errNoValue()
    open fun isCallable() = false

    open fun implicitMatchName(): R_Name? = null

    fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean, exprHint: C_ExprHint): C_Expr {
        val memberNameHand = memberName.compile(ctx)
        val member = member0(ctx, memberNameHand.name, safe, exprHint)
        memberNameHand.setIdeInfo(member.ideInfo)
        return member.expr
    }

    protected open fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        ctx.msgCtx.error(errNoValue())
        return C_ExprMember(C_ExprUtils.errorExpr(ctx, memberName.pos), IdeSymbolInfo.UNKNOWN)
    }

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = value() // May fail with "not a value" - that's OK.
        val vResExpr = vExpr.call(ctx, pos, args, resTypeHint)
        return C_VExpr(vResExpr)
    }

    private fun errNoValue(): C_Error {
        val pos = startPos()
        val kind = kind().code
        return C_Error.stop(pos, "expr_novalue:$kind", "Expression has no value: $kind")
    }
}

class C_VExpr(
        private val vExpr: V_Expr,
        private val implicitAttrMatchName: R_Name? = null
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = vExpr.pos
    override fun value() = vExpr
    override fun isCallable() = vExpr.type is R_FunctionType
    override fun implicitMatchName() = implicitAttrMatchName

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        return vExpr.member(ctx, memberName, safe, exprHint)
    }
}

class C_NamespaceExpr(private val qName: C_QualifiedName, private val nsRef: C_NamespaceRef): C_Expr() {
    override fun kind() = C_ExprKind.NAMESPACE
    override fun startPos() = qName.pos

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        val valueRes = memberValue(memberName)
        val fnRes = memberFunction(memberName)

        val valueCallable = valueRes?.isCallable() ?: false
        val res = C_ExprUtils.valueFunctionExpr0(valueRes, fnRes, valueCallable, exprHint)
        if (res == null) {
            C_Errors.errUnknownName(ctx.msgCtx, qName, memberName)
            return C_ExprUtils.errorMember(ctx, memberName.pos)
        }

        val fullName = qName.add(memberName)
        val memCtx = MemberCtx(ctx.nsValueCtx, memberName, fullName)
        val expr = res.toExpr(memCtx)

        val ideInfo = res.ideSymbolInfo()
        return C_ExprMember(expr, ideInfo)
    }

    private fun memberValue(memberName: C_Name): MemberRes? {
        val valueRef = nsRef.value(memberName)
        if (valueRef != null) {
            return MemberRes_Value(valueRef)
        }

        val typeRef = nsRef.type(memberName)
        if (typeRef != null) {
            return MemberRes_Type(typeRef)
        }

        return null
    }

    private fun memberFunction(memberName: C_Name): MemberRes? {
        val fnRef = nsRef.function(memberName)
        fnRef ?: return null
        return MemberRes_Function(fnRef)
    }

    private class MemberCtx(val valueCtx: C_NamespaceValueContext, val memberName: C_Name, val qName: C_QualifiedName)

    private abstract class MemberRes {
        abstract fun isCallable(): Boolean
        abstract fun ideSymbolInfo(): IdeSymbolInfo
        abstract fun toExpr(ctx: MemberCtx): C_Expr
    }

    private abstract class MemberRes_Generic<T>(val defRef: C_DefRef<T>): MemberRes() {
        final override fun ideSymbolInfo(): IdeSymbolInfo {
            return defRef.ideSymbolInfo()
        }
    }

    private class MemberRes_Value(defRef: C_DefRef<C_NamespaceValue>): MemberRes_Generic<C_NamespaceValue>(defRef) {
        override fun isCallable() = false

        override fun toExpr(ctx: MemberCtx): C_Expr {
            val def = defRef.getDef()
            return def.toExpr(ctx.valueCtx, ctx.qName)
        }
    }

    private class MemberRes_Type(defRef: C_DefRef<R_Type>): MemberRes_Generic<R_Type>(defRef) {
        override fun isCallable() = false

        override fun toExpr(ctx: MemberCtx): C_Expr {
            val def = defRef.getDef()
            return C_TypeExpr(ctx.memberName.pos, def)
        }
    }

    private class MemberRes_Function(defRef: C_DefRef<C_GlobalFunction>): MemberRes_Generic<C_GlobalFunction>(defRef) {
        override fun isCallable() = true

        override fun toExpr(ctx: MemberCtx): C_Expr {
            val def = defRef.getDef()
            return C_FunctionExpr(ctx.memberName, def)
        }
    }
}

sealed class C_StructExpr(
        private val startPos: S_Pos,
        protected val struct: R_Struct
): C_Expr() {
    final override fun kind() = C_ExprKind.STRUCT
    final override fun startPos() = startPos
    final override fun isCallable() = true

    protected abstract fun baseName(): String

    protected abstract fun memberFunction(ctx: C_ExprContext, memberName: C_Name): C_ExprMember?

    final override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        val res = memberFunction(ctx, memberName)
        if (res == null) {
            C_Errors.errUnknownName(ctx.msgCtx, startPos, "${baseName()}.$memberName")
            return C_ExprUtils.errorMember(ctx, memberName.pos)
        }
        return res
    }

    final override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = C_StructGlobalFunction.compileCall(ctx, struct, startPos, args)
        return C_VExpr(vExpr)
    }
}

class C_NamespaceStructExpr(
        private val name: C_QualifiedName,
        struct: R_Struct,
        private val nsRef: C_NamespaceRef
): C_StructExpr(name.pos, struct) {
    override fun baseName() = name.str()

    override fun memberFunction(ctx: C_ExprContext, memberName: C_Name): C_ExprMember? {
        val fnRef = nsRef.function(memberName)
        fnRef ?: return null
        val expr: C_Expr = C_FunctionRefExpr(memberName, fnRef)
        return C_ExprMember(expr, fnRef.ideSymbolInfo())
    }
}

class C_MirrorStructExpr(pos: S_Pos, struct: R_Struct, private val ns: C_Namespace): C_StructExpr(pos, struct) {
    override fun baseName() = struct.name

    override fun memberFunction(ctx: C_ExprContext, memberName: C_Name): C_ExprMember? {
        val fnProxy = ns.function(memberName.rName)
        fnProxy ?: return null

        val fnRef = C_DefRef(ctx.msgCtx, C_QualifiedName(memberName), fnProxy)
        val expr: C_Expr = C_FunctionRefExpr(memberName, fnRef)
        return C_ExprMember(expr, fnProxy.ideInfo)
    }
}

class C_ObjectExpr(exprCtx: C_ExprContext, qName: C_QualifiedName, rObject: R_ObjectDefinition): C_Expr() {
    private val vExpr = V_ObjectExpr(exprCtx, qName, rObject)

    override fun kind() = C_ExprKind.OBJECT
    override fun startPos() = vExpr.pos
    override fun value(): V_Expr = vExpr

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        return vExpr.member(ctx, memberName, safe, exprHint)
    }
}

class C_EnumExpr(
        private val msgCtx: C_MessageContext,
        private val qName: C_QualifiedName,
        private val rEnum: R_EnumDefinition
): C_Expr() {
    override fun kind() = C_ExprKind.ENUM
    override fun startPos() = qName.pos

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        val valueMember = memberValue(ctx, memberName)
        val fnMember = memberFunction(ctx, memberName)

        val res = C_ExprUtils.valueFunctionExprMember(valueMember, fnMember, exprHint)
        if (res == null) {
            C_Errors.errUnknownName(ctx.msgCtx, qName, memberName)
            return C_ExprUtils.errorMember(ctx, memberName.pos)
        }

        return res
    }

    private fun memberValue(ctx: C_ExprContext, memberName: C_Name): C_ExprMember? {
        val attr = rEnum.attr(memberName.str)
        attr ?: return null

        val rValue = Rt_EnumValue(rEnum.type, attr)
        val vExpr = V_ConstantValueExpr(ctx, startPos(), rValue)
        return C_ExprMember(C_VExpr(vExpr), attr.ideInfo)
    }

    private fun memberFunction(ctx: C_ExprContext, memberName: C_Name): C_ExprMember? {
        val fn = ctx.globalCtx.libFunctions.getTypeStaticFunction(rEnum.type, memberName.rName)
        fn ?: return null
        val fnExpr = C_FunctionExpr(memberName, fn)
        return C_ExprMember(fnExpr, fn.ideInfo)
    }
}

class C_FunctionExpr(private val name: C_Name, private val fn: C_GlobalFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos
    override fun isCallable() = true

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = fn.compileCall(ctx, name, args, resTypeHint)
        return C_VExpr(vExpr)
    }
}

class C_FunctionRefExpr(private val name: C_Name, private val fnRef: C_DefRef<C_GlobalFunction>): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos
    override fun isCallable() = true

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val fn = fnRef.getDef()
        val vExpr = fn.compileCall(ctx, name, args, resTypeHint)
        return C_VExpr(vExpr)
    }
}

class C_TypeExpr(private val pos: S_Pos, private val type: R_Type): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member0(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        val fn = ctx.globalCtx.libFunctions.getTypeStaticFunction(type, memberName.rName)
        if (fn == null) {
            C_Errors.errUnknownName(ctx.msgCtx, type, memberName)
            return C_ExprUtils.errorMember(ctx, memberName.pos)
        }

        val fnExpr: C_Expr = C_FunctionExpr(memberName, fn)
        return C_ExprMember(fnExpr, fn.ideInfo)
    }
}
