/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.expr

import net.postchain.rell.model.R_FilePos
import net.postchain.rell.model.R_SysFunction
import net.postchain.rell.model.R_Type
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.RellInterpreterCrashException
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList
import org.apache.commons.lang3.StringUtils

object R_SysFunctionUtils {
    fun call(fn: R_SysFunction, nameMsg: LazyString?, frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
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

    private fun callAndCatch(fn: R_SysFunction, name: LazyString, frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = try {
            call0(fn, frame, values)
        } catch (e: Rt_StackTraceError) {
            throw e
        } catch (e: Rt_BaseError) {
            val msg = decorate(name.value, e.message)
            throw e.updateMessage(msg)
        } catch (e: RellInterpreterCrashException) {
            throw e
        } catch (e: Throwable) {
            val msg = decorate(name.value, e.message)
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
