/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.model

import net.postchain.rell.base.runtime.Rt_CallContext
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.checkEquals

fun interface R_SysFunction {
    fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value
}

abstract class R_SysFunction_1: R_SysFunction {
    abstract fun call(arg: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        checkEquals(args.size, 1)
        val res = call(args[0])
        return res
    }
}

abstract class R_SysFunction_2: R_SysFunction {
    abstract fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        checkEquals(args.size, 2)
        val res = call(args[0], args[1])
        return res
    }
}

abstract class R_SysFunction_N: R_SysFunction {
    abstract fun call(args: List<Rt_Value>): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        val res = call(args)
        return res
    }
}

abstract class R_SysFunctionEx_0: R_SysFunction {
    abstract fun call(ctx: Rt_CallContext): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        checkEquals(args.size, 0)
        val res = call(ctx)
        return res
    }
}

abstract class R_SysFunctionEx_1: R_SysFunction {
    abstract fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        checkEquals(args.size, 1)
        val res = call(ctx, args[0])
        return res
    }
}

abstract class R_SysFunctionEx_2: R_SysFunction {
    abstract fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        checkEquals(args.size, 2)
        val res = call(ctx, args[0], args[1])
        return res
    }
}

abstract class R_SysFunction_Generic<T>: R_SysFunction {
    abstract fun extract(v: Rt_Value): T

    open fun call(type: R_Type, obj: T): Rt_Value = call(obj)
    open fun call(type: R_Type, obj: T, a: Rt_Value): Rt_Value = call(obj, a)
    open fun call(type: R_Type, obj: T, a: Rt_Value, b: Rt_Value): Rt_Value = call(obj, a, b)

    open fun call(obj: T): Rt_Value = call(obj, listOf())
    open fun call(obj: T, a: Rt_Value): Rt_Value = call(obj, listOf(a))
    open fun call(obj: T, a: Rt_Value, b: Rt_Value): Rt_Value = call(obj, listOf(a, b))

    open fun call(obj: T, args: List<Rt_Value>): Rt_Value = throw errArgCnt(args.size)

    final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.isNotEmpty())

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
