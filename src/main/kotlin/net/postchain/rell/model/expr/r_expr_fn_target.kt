/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.expr

import net.postchain.rell.compiler.base.utils.C_LateGetter
import net.postchain.rell.lib.test.Rt_TestOpValue
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals

abstract class R_FunctionCallTarget {
    abstract fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget?
}

class R_FunctionCallTarget_RegularUserFunction(
        private val fn: R_RoutineDefinition
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget {
        checkEquals(values.size, 0)
        return Rt_FunctionCallTarget_RegularUserFunction()
    }

    private inner class Rt_FunctionCallTarget_RegularUserFunction: Rt_FunctionCallTarget() {
        override fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value {
            val res = fn.call(callCtx, values)
            return res
        }

        override fun str() = fn.appLevelName
        override fun strCode() = fn.appLevelName
    }
}

class R_FunctionCallTarget_AbstractUserFunction(
        private val baseFn: R_FunctionDefinition,
        private val overrideGetter: C_LateGetter<R_FunctionBase>
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget {
        checkEquals(values.size, 0)
        return Rt_FunctionCallTarget_AbstractUserFunction()
    }

    private inner class Rt_FunctionCallTarget_AbstractUserFunction: Rt_FunctionCallTarget() {
        override fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value {
            val overrideBaseFn = overrideGetter.get()
            val res = overrideBaseFn.call(callCtx, values)
            return res
        }

        override fun str() = baseFn.appLevelName
        override fun strCode() = baseFn.appLevelName
    }
}

class R_FunctionCallTarget_Operation(
        private val op: R_OperationDefinition
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget {
        checkEquals(values.size, 0)
        return Rt_FunctionCallTarget_Operation()
    }

    private inner class Rt_FunctionCallTarget_Operation: Rt_FunctionCallTarget() {
        override fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value {
            val gtvArgs = values.map { it.type().rtToGtv(it, false) }
            return Rt_TestOpValue(op.mountName, gtvArgs)
        }

        override fun str() = op.appLevelName
        override fun strCode() = op.appLevelName
    }
}

class R_FunctionCallTarget_FunctionValue(
        private val safe: Boolean
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget? {
        checkEquals(values.size, 1)
        val fnValue0 = values[0]
        if (safe && fnValue0 == Rt_NullValue) return null
        val fnValue = fnValue0.asFunction()
        return Rt_FunctionCallTarget_FunctionValue(fnValue)
    }

    private inner class Rt_FunctionCallTarget_FunctionValue(val fnValue: Rt_FunctionValue): Rt_FunctionCallTarget() {
        override fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value {
            return fnValue.call(callCtx, values)
        }

        override fun str() = fnValue.toString()
        override fun strCode() = fnValue.strCode()

        override fun createFunctionValue(resType: R_Type, mapping: R_PartialCallMapping, args: List<Rt_Value>): Rt_Value {
            return fnValue.combine(resType, mapping, args)
        }
    }
}

class R_FunctionCallTarget_SysGlobalFunction(
        private val fn: R_SysFunction,
        private val fullName: LazyString
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget {
        checkEquals(values.size, 0)
        return Rt_FunctionCallTarget_SysGlobalFunction()
    }

    private inner class Rt_FunctionCallTarget_SysGlobalFunction: Rt_FunctionCallTarget() {
        override fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value {
            return R_SysFunctionUtils.call(callCtx, fn, fullName, values)
        }

        override fun str() = fullName.value
        override fun strCode() = fullName.value
    }
}

class R_FunctionCallTarget_SysMemberFunction(
        private val safe: Boolean,
        private val fn: R_SysFunction,
        private val fullName: LazyString
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget? {
        checkEquals(values.size, 1)
        val rtBase = values[0]
        return if (safe && rtBase == Rt_NullValue) null else Rt_FunctionCallTarget_SysMemberFunction(rtBase)
    }

    private inner class Rt_FunctionCallTarget_SysMemberFunction(val rtBase: Rt_Value): Rt_FunctionCallTarget() {
        override fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value {
            val values2 = listOf(rtBase) + values
            return R_SysFunctionUtils.call(callCtx, fn, fullName, values2)
        }

        override fun str() = fullName.value
        override fun strCode() = fullName.value
    }
}

abstract class Rt_FunctionCallTarget {
    abstract fun call(callCtx: Rt_CallContext, values: List<Rt_Value>): Rt_Value
    abstract fun str(): String
    abstract fun strCode(): String

    final override fun toString(): String {
        CommonUtils.failIfUnitTest()
        return str()
    }

    open fun createFunctionValue(resType: R_Type, mapping: R_PartialCallMapping, args: List<Rt_Value>): Rt_Value {
        return Rt_FunctionValue(resType, mapping, this, args)
    }
}
