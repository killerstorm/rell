/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.ast.S_Expr
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.lib.C_LibModule
import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunction
import net.postchain.rell.base.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.base.compiler.vexpr.V_ExprInfo
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_TextValue
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

private object C_SysFn_TypeOf: C_SpecialLibGlobalFunction() {
    override fun paramCount() = 1 .. 1

    override fun compileCall0(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_GlobalFunctionCall {
        checkEquals(1, args.size)

        val arg = args[0]
        val cArg = arg.compile(ctx)
        val vArg = cArg.value()

        val type = vArg.type
        val str = type.strCode()
        val value = Rt_TextValue(str)

        val exprInfo = V_ExprInfo.simple(value.type(), dependsOnAtExprs = vArg.info.dependsOnAtExprs)
        val vExpr = V_ConstantValueExpr(ctx, name.pos, value, exprInfo)
        return V_GlobalFunctionCall(vExpr)
    }
}
