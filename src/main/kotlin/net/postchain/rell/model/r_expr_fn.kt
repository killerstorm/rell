/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.compiler.C_DefinitionContext
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.runtime.*
import org.apache.commons.lang3.StringUtils

sealed class R_CallExpr(type: R_Type, val args: List<R_Expr>): R_Expr(type) {
    abstract fun call(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val values = args.map { it.evaluate(frame) }
        val res = call(frame, values)
        return res
    }
}

class R_SysCallExpr(
        type: R_Type,
        private val fn: R_SysFunction,
        args: List<R_Expr>,
        private val name: String?
): R_CallExpr(type, args) {
    override fun call(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = if (name == null) {
            call0(frame, values)
        } else try {
            call0(frame, values)
        } catch (e: Rt_StackTraceError) {
            throw e
        } catch (e: Rt_BaseError) {
            val msg = decorate(e.message)
            throw e.updateMessage(msg)
        } catch (e: Throwable) {
            val msg = decorate(e.message)
            throw RuntimeException(msg, e)
        }
        return res
    }

    private fun decorate(msg: String?): String {
        val msg2 = StringUtils.defaultIfBlank(msg, "error")
        return "System function '$name': $msg2"
    }

    private fun call0(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = fn.call(frame.defCtx.callCtx, values)
        return res
    }
}

class R_UserCallExpr(
        type: R_Type,
        private val fn: R_Routine,
        args: List<R_Expr>,
        private val filePos: R_FilePos
): R_CallExpr(type, args) {
    override fun call(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = fn.call(frame, values, filePos)
        return res
    }
}

abstract class R_SysFunction {
    abstract fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value
}

abstract class R_SysFunction_0: R_SysFunction() {
    abstract fun call(): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        val res = call()
        return res
    }
}

abstract class R_SysFunction_1: R_SysFunction() {
    abstract fun call(arg: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val res = call(args[0])
        return res
    }
}

abstract class R_SysFunction_2: R_SysFunction() {
    abstract fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 2)
        val res = call(args[0], args[1])
        return res
    }
}

abstract class R_SysFunction_3: R_SysFunction() {
    abstract fun call(arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 3)
        val res = call(args[0], args[1], args[2])
        return res
    }
}

abstract class R_SysFunctionEx_0: R_SysFunction() {
    abstract fun call(ctx: Rt_CallContext): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        val res = call(ctx)
        return res
    }
}

abstract class R_SysFunctionEx_1: R_SysFunction() {
    abstract fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val res = call(ctx, args[0])
        return res
    }
}

abstract class R_SysFunctionEx_2: R_SysFunction() {
    abstract fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 2)
        val res = call(ctx, args[0], args[1])
        return res
    }
}

abstract class R_SysFunctionEx_3: R_SysFunction() {
    abstract fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 3)
        val res = call(ctx, args[0], args[1], args[2])
        return res
    }
}

abstract class R_SysFunction_Generic<T>: R_SysFunction() {
    abstract fun extract(v: Rt_Value): T

    open fun call(type: R_Type, obj: T): Rt_Value = call(obj)
    open fun call(type: R_Type, obj: T, a: Rt_Value): Rt_Value = call(obj, a)
    open fun call(type: R_Type, obj: T, a: Rt_Value, b: Rt_Value): Rt_Value = call(obj, a, b)

    open fun call(obj: T): Rt_Value = call(obj, listOf())
    open fun call(obj: T, a: Rt_Value): Rt_Value = call(obj, listOf(a))
    open fun call(obj: T, a: Rt_Value, b: Rt_Value): Rt_Value = call(obj, listOf(a, b))

    open fun call(obj: T, args: List<Rt_Value>): Rt_Value = throw errArgCnt(args.size)

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size >= 1)

        val objVal = args[0]
        val type = objVal.type()
        val obj = extract(objVal)

        if (args.size == 1) {
            return call(type, obj)
        } else if (args.size == 2) {
            return call(type, obj, args[1])
        } else if (args.size == 3) {
            return call(type, obj, args[1], args[2])
        } else {
            throw errArgCnt(args.size)
        }
    }

    private fun errArgCnt(n: Int) = IllegalStateException("Wrong number of arguments for ${javaClass.simpleName}: $n")
}

abstract class R_SysFunction_Common: R_SysFunction_Generic<Rt_Value>() {
    override fun extract(v: Rt_Value): Rt_Value = v
}
