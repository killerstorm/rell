/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.model

import net.postchain.gtv.Gtv
import net.postchain.rell.base.compiler.base.core.C_CompilerPass
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.utils.C_LateInit
import net.postchain.rell.base.model.stmt.R_Statement
import net.postchain.rell.base.model.stmt.R_StatementResult_Return
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.toGtv
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList

class R_Param(val name: R_Name, val type: R_Type) {
    fun toMetaGtv(): Gtv = mapOf(
            "name" to name.str.toGtv(),
            "type" to type.toMetaGtv()
    ).toGtv()
}

class R_VarParam(val name: R_Name, val type: R_Type, val ptr: R_VarPtr) {
    fun toParam() = R_Param(name, type)
}

sealed class R_RoutineDefinition(base: R_DefinitionBase): R_Definition(base) {
    abstract fun params(): List<R_Param>
    abstract fun call(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value
}

sealed class R_MountedRoutineDefinition(
        base: R_DefinitionBase,
        val mountName: R_MountName
): R_RoutineDefinition(base)

class R_OperationDefinition(
        base: R_DefinitionBase,
        mountName: R_MountName,
        val mirrorStructs: R_MirrorStructs
): R_MountedRoutineDefinition(base, mountName) {
    private val internals = C_LateInit(C_CompilerPass.EXPRESSIONS, ERROR_INTERNALS)

    fun setInternals(varParams: List<R_VarParam>, body: R_Statement, frame: R_CallFrame) {
        val params = varParams.map { it.toParam() }.toImmList()
        internals.set(Internals(params, varParams, body, frame))
    }

    override fun params() = internals.get().params

    fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value? {
        val rtFrame = processCallArgs(exeCtx, args)
        execute(rtFrame)
        return null
    }

    override fun call(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        throw Rt_Exception.common("call:operation", "Calling operation is not allowed")
    }

    private fun processCallArgs(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_CallFrame {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(exeCtx, true, defId)
        val rtFrame = ints.frame.createRtFrame(defCtx, null, null)

        checkCallArgs(this, ints.params, args)
        processArgs(ints.varParams, args, rtFrame)

        return rtFrame
    }

    private fun execute(rtFrame: Rt_CallFrame) {
        val ints = internals.get()
        val res = ints.body.execute(rtFrame)
        if (res != null) {
            check(res is R_StatementResult_Return && res.value == null)
        }
    }

    override fun toMetaGtv(): Gtv {
        return mapOf(
                "mount" to mountName.str().toGtv(),
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }

    private class Internals(
            val params: List<R_Param>,
            val varParams: List<R_VarParam>,
            val body: R_Statement,
            val frame: R_CallFrame
    )

    companion object {
        private val ERROR_INTERNALS = Internals(
                params = listOf(),
                varParams = listOf(),
                body = C_ExprUtils.ERROR_STATEMENT,
                frame = R_CallFrame.ERROR
        )
    }
}

sealed class R_QueryBody(
        val retType: R_Type,
        params: List<R_Param>
) {
    val params = params.toImmList()

    abstract fun call(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, stack: Rt_CallStack?): Rt_Value
}

class R_UserQueryBody(
        retType: R_Type,
        varParams: List<R_VarParam>,
        private val body: R_Statement,
        private val frame: R_CallFrame
): R_QueryBody(retType, varParams.map { it.toParam() }) {
    private val varParams = varParams.toImmList()

    override fun call(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, stack: Rt_CallStack?): Rt_Value {
        val rtFrame = frame.createRtFrame(defCtx, stack, null)

        processArgs(varParams, args, rtFrame)

        val res = body.execute(rtFrame)
        check(res is R_StatementResult_Return) { "${res?.javaClass?.name}" }

        check(res.value != null)
        return res.value
    }

    companion object {
        val ERROR: R_QueryBody = R_UserQueryBody(R_CtErrorType, listOf(), C_ExprUtils.ERROR_STATEMENT, R_CallFrame.ERROR)
    }
}

class R_SysQueryBody(retType: R_Type, params: List<R_Param>, private val fn: R_SysFunction): R_QueryBody(retType, params) {
    override fun call(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, stack: Rt_CallStack?): Rt_Value {
        val callCtx = Rt_CallContext(defCtx, stack, dbUpdateAllowed = false)
        return fn.call(callCtx, args)
    }
}

class R_QueryDefinition(
        base: R_DefinitionBase,
        mountName: R_MountName
): R_MountedRoutineDefinition(base, mountName) {
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_UserQueryBody.ERROR)

    fun setBody(body: R_QueryBody) {
        bodyLate.set(body)
    }

    fun type(): R_Type = bodyLate.get().retType
    override fun params() = bodyLate.get().params

    fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value {
        val body = bodyLate.get()
        checkCallArgs(this, body.params, args)
        val defCtx = Rt_DefinitionContext(exeCtx, false, defId)
        val res = body.call(defCtx, args, null)
        return res
    }

    override fun call(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        val body = bodyLate.get()
        checkCallArgs(this, body.params, args)

        val subDefCtx = Rt_DefinitionContext(callCtx.defCtx.exeCtx, false, defId)
        val res = body.call(subDefCtx, args, callCtx.stack)
        return res
    }

    override fun toMetaGtv(): Gtv {
        return mapOf(
                "mount" to mountName.str().toGtv(),
                "type" to type().toMetaGtv(),
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }
}

class R_FunctionHeader(val type: R_Type, val params: List<R_Param>) {
    companion object {
        val ERROR = R_FunctionHeader(R_CtErrorType, immListOf())
    }
}

class R_FunctionBody(
        val type: R_Type,
        val varParams: List<R_VarParam>,
        val body: R_Statement,
        val frame: R_CallFrame
) {
    val params = varParams.map { it.toParam() }.toImmList()

    companion object {
        val ERROR = R_FunctionBody(
                R_CtErrorType,
                immListOf(),
                C_ExprUtils.ERROR_STATEMENT,
                R_CallFrame.ERROR
        )
    }
}

class R_FunctionBase(private val defName: R_DefinitionName) {
    private val headerLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_FunctionHeader.ERROR)
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_FunctionBody.ERROR)

    fun setHeader(header: R_FunctionHeader) {
        headerLate.set(header)
    }

    fun setBody(body: R_FunctionBody) {
        bodyLate.set(body)
    }

    fun getHeader() = headerLate.get()
    fun getBody() = bodyLate.get()

    fun callTop(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>, dbUpdateAllowed: Boolean): Rt_Value {
        val body = bodyLate.get()
        val defCtx = Rt_DefinitionContext(exeCtx, dbUpdateAllowed, body.frame.defId)
        val callCtx = Rt_CallContext(defCtx, null, dbUpdateAllowed)
        val res = call0(callCtx, args)
        return res
    }

    fun call(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        return call0(callCtx, args)
    }

    private fun call0(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        val body = bodyLate.get()
        val rtSubFrame = createCallFrame(callCtx, body.frame)
        processArgs(body.varParams, args, rtSubFrame)

        val res = body.body.execute(rtSubFrame)

        val retVal = if (res is R_StatementResult_Return) res.value else null
        return retVal ?: Rt_UnitValue
    }

    override fun toString() = defName.appLevelName

    companion object {
        fun createCallFrame(callCtx: Rt_CallContext, targetFrame: R_CallFrame): Rt_CallFrame {
            val dbUpdateAllowed = callCtx.dbUpdateAllowed()
            val subDefCtx = Rt_DefinitionContext(callCtx.defCtx.exeCtx, dbUpdateAllowed, targetFrame.defId)
            return targetFrame.createRtFrame(subDefCtx, callCtx.stack, null)
        }
    }
}

class R_FunctionDefinition(
        base: R_DefinitionBase,
        private val fnBase: R_FunctionBase
): R_RoutineDefinition(base) {
    override fun params() = fnBase.getBody().params

    fun callTop(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>, dbUpdateAllowed: Boolean = false): Rt_Value {
        return fnBase.callTop(exeCtx, args, dbUpdateAllowed)
    }

    override fun call(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        return fnBase.call(callCtx, args)
    }

    override fun toMetaGtv(): Gtv {
        val header = fnBase.getHeader()
        return mapOf(
                "type" to header.type.toMetaGtv(),
                "parameters" to header.params.map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }
}

private fun checkCallArgs(routine: R_RoutineDefinition, params: List<R_Param>, args: List<Rt_Value>) {
    val name = routine.appLevelName

    if (args.size != params.size) {
        throw Rt_Exception.common("fn_wrong_arg_count:$name:${params.size}:${args.size}",
                "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
    }

    for (i in params.indices) {
        val param = params[i]
        val argType = args[i].type()
        if (!param.type.isAssignableFrom(argType)) {
            throw Rt_Exception.common("fn_wrong_arg_type:$name:${param.type.strCode()}:${argType.strCode()}",
                    "Wrong type of argument '${param.name}' for '$name': " +
                            "${argType.strCode()} instead of ${param.type.strCode()}")
        }
    }
}

private fun processArgs(params: List<R_VarParam>, args: List<Rt_Value>, frame: Rt_CallFrame) {
    checkEquals(args.size, params.size)
    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        frame.set(param.ptr, param.type, arg, false)
    }
}
