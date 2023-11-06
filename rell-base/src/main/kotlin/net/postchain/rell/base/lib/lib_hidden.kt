/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.ast.S_Expr
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprDefMeta
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.lib.C_LibModule
import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunctionBody
import net.postchain.rell.base.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.model.R_BooleanType
import net.postchain.rell.base.model.R_NullableType
import net.postchain.rell.base.model.R_TextType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_NullValue
import net.postchain.rell.base.runtime.Rt_TextValue
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.runtime.utils.RellInterpreterCrashException
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.checkEquals

private const val TEST_NS_NAME = "_test"

object Lib_RellHidden {
    val MODULE = C_LibModule.make("rell.hidden", Lib_Rell.MODULE) {
        namespace(TEST_NS_NAME) {
            function("crash", result = "unit") {
                param("text")
                body { a ->
                    val s = a.asString()
                    throw RellInterpreterCrashException(s)
                }
            }

            function("throw", "unit") {
                param("text")
                param("text")
                body { a, b ->
                    val code = a.asString()
                    val msg = b.asString()
                    throw Rt_Exception.common("throw:$code", msg)
                }
            }

            function("mount_name", C_SysFn_MountName)
            function("external_chain", C_SysFn_ExternalChain)
        }

        function("_type_of", C_SysFn_TypeOf)

        function("_nullable", pure = true) {
            generic("T")
            result(type = "T?")
            param(type = "T")
            body { a -> a }
        }

        function("_nullable_int", "integer?", pure = true) {
            param(type = "integer?")
            body { a -> a }
        }

        function("_nullable_text", result = "text?", pure = true) {
            param("text?")
            body { a -> a }
        }

        function("_nop", pure = true) {
            generic("T")
            result("T")
            param("T")
            body { a -> a }
        }

        function("_nop_print", pure = true) {
            generic("T")
            result(type = "T")
            param(type = "T")
            bodyContext { ctx, a ->
                ctx.globalCtx.outPrinter.print(a.str())
                a
            }
        }

        function("_strict_str", result = "text") {
            param(type = "anything")
            body { a ->
                val s = a.strCode()
                Rt_TextValue(s)
            }
        }
    }
}

private object C_SysFn_TypeOf: C_SpecialLibGlobalFunctionBody() {
    override fun paramCount() = 1 .. 1

    override fun compileCall(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_Expr {
        checkEquals(1, args.size)

        val arg = args[0]
        val cArg = arg.compile(ctx)
        val vArg = cArg.value()

        val type = vArg.type
        val str = type.strCode()
        val value = Rt_TextValue(str)

        return V_ConstantValueExpr(ctx, name.pos, value, dependsOnAtExprs = vArg.info.dependsOnAtExprs)
    }
}

private abstract class C_SysFn_BaseMeta(private val resultType: R_Type): C_SpecialLibGlobalFunctionBody() {
    final override fun paramCount() = 1 .. 1

    protected abstract fun compileCall0(meta: C_ExprDefMeta): Rt_Value?

    final override fun compileCall(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_Expr {
        checkEquals(1, args.size)

        val arg = args[0]
        val cArg = arg.compile(ctx)
        val meta = cArg.getDefMeta()

        val value = if (meta == null) null else compileCall0(meta)

        if (value == null) {
            cArg.value()
            ctx.msgCtx.error(name.pos, "expr_call:bad_arg:${name.str}", "Bad argument for function '${name.str}'")
            return C_ExprUtils.errorVExpr(ctx, name.pos, R_BooleanType)
        }

        return V_ConstantValueExpr(ctx, name.pos, value, resultType)
    }
}

private object C_SysFn_MountName: C_SysFn_BaseMeta(R_TextType) {
    override fun compileCall0(meta: C_ExprDefMeta): Rt_Value? {
        return if (meta.mountName == null) null else Rt_TextValue(meta.mountName.str())
    }
}

private object C_SysFn_ExternalChain: C_SysFn_BaseMeta(R_NullableType(R_TextType)) {
    override fun compileCall0(meta: C_ExprDefMeta): Rt_Value? {
        meta.externalChain ?: return null
        return if (meta.externalChain.value == null) Rt_NullValue else Rt_TextValue(meta.externalChain.value)
    }
}
