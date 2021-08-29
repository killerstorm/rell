/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.lib.Rt_TestOpValue
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList
import org.apache.commons.lang3.StringUtils

object R_SysFunctionUtils {
    fun call(fn: R_SysFunction, nameMsg: String?, frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        return if (nameMsg == null) {
            call0(fn, frame, values)
        } else {
            callAndCatch(fn, nameMsg, frame, values)
        }
    }

    private fun call0(fn: R_SysFunction, frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = fn.call(frame.defCtx.callCtx, values)
        return res
    }

    fun callAndCatch(fn: R_SysFunction, name: String, frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = try {
            call0(fn, frame, values)
        } catch (e: Rt_StackTraceError) {
            throw e
        } catch (e: Rt_BaseError) {
            val msg = decorate(name, e.message)
            throw e.updateMessage(msg)
        } catch (e: RellInterpreterCrashException) {
            throw e
        } catch (e: Throwable) {
            val msg = decorate(name, e.message)
            throw Rt_Error("fn:error:$name:${e.javaClass.canonicalName}", msg)
        }
        return res
    }

    private fun decorate(name: String, msg: String?): String {
        val msg2 = StringUtils.defaultIfBlank(msg, "error")
        return "System function '$name': $msg2"
    }
}

class R_FullFunctionCallExpr(
        type: R_Type,
        private val target: R_FunctionCallTarget,
        private val callPos: R_FilePos,
        targetExprs: List<R_Expr>,
        args: List<R_Expr>,
        mapping: List<Int>
): R_Expr(type) {
    private val targetExprs = targetExprs.toImmList()
    private val args = args.toImmList()
    private val mapping = mapping.toImmList()

    init {
        checkEquals(this.mapping.sorted(), this.args.indices.toList())
    }

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val targetValues = targetExprs.map { it.evaluate(frame) }
        val rtTarget = target.evaluateTarget(frame, targetValues)
        rtTarget ?: return Rt_NullValue
        val values = args.map { it.evaluate(frame) }
        val values2 = mapping.map { values[it] }
        val res = rtTarget.call(frame, values2, callPos)
        return res
    }
}

class R_PartialArgMapping(val wild: Boolean, val index: Int)

class R_PartialCallMapping(val exprCount: Int, val wildCount: Int, args: List<R_PartialArgMapping>) {
    val args = args.toImmList()

    init {
        check(exprCount >= 0)
        check(wildCount >= 0)
        checkEquals(this.args.size, exprCount + wildCount)
        checkEquals(this.args.filter { it.wild }.map { it.index }.sorted().toList(), (0 until wildCount).toList())
        checkEquals(this.args.filter { !it.wild }.map { it.index }.sorted().toList(), (0 until exprCount).toList())
    }
}

class R_PartialFunctionCallExpr(
        type: R_Type,
        private val target: R_FunctionCallTarget,
        private val mapping: R_PartialCallMapping,
        targetExprs: List<R_Expr>,
        args: List<R_Expr>
): R_Expr(type) {
    private val targetExprs = targetExprs.toImmList()
    private val args = args.toImmList()

    init {
        checkEquals(this.args.size, mapping.exprCount)
    }

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val targetValues = targetExprs.map { it.evaluate(frame) }
        val rtTarget = target.evaluateTarget(frame, targetValues)
        rtTarget ?: return Rt_NullValue
        val values = args.map { it.evaluate(frame) }
        return rtTarget.createFunctionValue(type, mapping, values)
    }
}

abstract class R_FunctionCallTarget {
    abstract fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget?
}

class R_FunctionCallTarget_UserFunction(
        private val fn: R_RoutineDefinition
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget {
        checkEquals(values.size, 0)
        return Rt_FunctionCallTarget_UserFunction()
    }

    private inner class Rt_FunctionCallTarget_UserFunction: Rt_FunctionCallTarget() {
        override fun call(frame: Rt_CallFrame, values: List<Rt_Value>, callPos: R_FilePos): Rt_Value {
            val res = fn.call(frame, values, callPos)
            return res
        }

        override fun toStrictString() = fn.appLevelName
        override fun toString() = fn.appLevelName
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
        override fun call(frame: Rt_CallFrame, values: List<Rt_Value>, callPos: R_FilePos): Rt_Value {
            val gtvArgs = values.map { it.type().rtToGtv(it, false) }
            return Rt_TestOpValue(op.mountName, gtvArgs)
        }

        override fun toStrictString() = op.appLevelName
        override fun toString() = op.appLevelName
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
        override fun call(frame: Rt_CallFrame, values: List<Rt_Value>, callPos: R_FilePos): Rt_Value {
            return fnValue.call(frame, values, callPos)
        }

        override fun toStrictString() = fnValue.toStrictString()
        override fun toString() = fnValue.toString()

        override fun createFunctionValue(resType: R_Type, mapping: R_PartialCallMapping, args: List<Rt_Value>): Rt_Value {
            return fnValue.combine(resType, mapping, args)
        }
    }
}

class R_FunctionCallTarget_SysGlobalFunction(
        private val fn: R_SysFunction,
        private val fullName: String
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget {
        checkEquals(values.size, 0)
        return Rt_FunctionCallTarget_SysGlobalFunction()
    }

    private inner class Rt_FunctionCallTarget_SysGlobalFunction: Rt_FunctionCallTarget() {
        override fun call(frame: Rt_CallFrame, values: List<Rt_Value>, callPos: R_FilePos): Rt_Value {
            return R_SysFunctionUtils.call(fn, fullName, frame, values)
        }

        override fun toStrictString() = fullName
        override fun toString() = fullName
    }
}

class R_FunctionCallTarget_SysMemberFunction(
        private val safe: Boolean,
        private val fn: R_SysFunction,
        private val fullName: String
): R_FunctionCallTarget() {
    override fun evaluateTarget(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_FunctionCallTarget? {
        checkEquals(values.size, 1)
        val rtBase = values[0]
        return if (safe && rtBase == Rt_NullValue) null else Rt_FunctionCallTarget_SysMemberFunction(rtBase)
    }

    private inner class Rt_FunctionCallTarget_SysMemberFunction(val rtBase: Rt_Value): Rt_FunctionCallTarget() {
        override fun call(frame: Rt_CallFrame, values: List<Rt_Value>, callPos: R_FilePos): Rt_Value {
            val values2 = listOf(rtBase) + values
            return R_SysFunctionUtils.call(fn, fullName, frame, values2)
        }

        override fun toStrictString() = fullName
        override fun toString() = fullName
    }
}

abstract class Rt_FunctionCallTarget {
    abstract fun call(frame: Rt_CallFrame, values: List<Rt_Value>, callPos: R_FilePos): Rt_Value
    abstract fun toStrictString(): String
    abstract override fun toString(): String

    open fun createFunctionValue(resType: R_Type, mapping: R_PartialCallMapping, args: List<Rt_Value>): Rt_Value {
        return Rt_FunctionValue(resType, mapping, this, args)
    }
}
