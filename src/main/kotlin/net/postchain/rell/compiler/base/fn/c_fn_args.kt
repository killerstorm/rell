/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.fn

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_FunctionCallArgs
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.expr.R_PartialArgMapping
import net.postchain.rell.model.expr.R_PartialCallMapping
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList
import org.apache.commons.lang3.mutable.MutableBoolean
import kotlin.math.min

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class C_EffectivePartialArguments(
        exprArgs: List<V_Expr>,
        wildArgs: List<R_Type>,
        combinedArgs: List<R_PartialArgMapping>
) {
    val exprArgs = exprArgs.toImmList()
    val wildArgs = wildArgs.toImmList()
    val combinedArgs = combinedArgs.toImmList()

    fun toRMapping() = R_PartialCallMapping(exprArgs.size, wildArgs.size, combinedArgs)
}

sealed class C_FullCallArguments(protected val ctx: C_ExprContext) {
    abstract fun compileSimpleArgs(functionName: R_Name): List<V_Expr>?
    abstract fun compileComplexArgs(callInfo: C_FunctionCallInfo): V_FunctionCallArgs?
}

sealed class C_PartialCallArguments(protected val ctx: C_ExprContext, val wildcardPos: S_Pos) {
    abstract fun compileEffectiveArgs(callInfo: C_FunctionCallInfo): C_EffectivePartialArguments?
    abstract fun errPartialNotSupported(functionName: String?)
}

object C_FunctionCallArgsUtils {
    fun compileCall(
            ctx: C_ExprContext,
            args: List<S_CallArgument>,
            resTypeHint: C_TypeHint,
            target: C_FunctionCallTarget
    ): V_Expr? {
        val ideInfoProvider = C_CallArgumentIdeInfoProvider_Argument(target)
        val cArgs = C_CallArgument.compileArguments(ctx, args, target.typeHints(), ideInfoProvider)

        return when (val fnCallArgs = C_ArgsListProcessor.processArgs(ctx, cArgs)) {
            null -> null
            is C_InternalCallArguments_Full -> {
                target.compileFull(C_FullCallArguments_Impl(ctx, fnCallArgs))
            }
            is C_InternalCallArguments_Partial -> {
                val resFnType = resTypeHint.getFunctionType()
                target.compilePartial(C_PartialCallArguments_Impl(ctx, fnCallArgs), resFnType)
            }
        }
    }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

private class C_GenericCallArg<T>(val index: Int, val name: C_Name?, val valuePos: S_Pos, val value: T) {
    constructor(arg: C_CallArgument, value: T): this(arg.index, arg.name, arg.value.pos, value)
    constructor(arg: C_GenericCallArg<*>, value: T): this(arg.index, arg.name, arg.valuePos, value)
}

private class C_GenericCallArgs<T>(
        mixed: List<T>,
        positional: List<C_GenericCallArg<T>>,
        named: List<C_NameValue<C_GenericCallArg<T>>>
) {
    val mixed = mixed.toImmList()
    val positional = positional.toImmList()
    val named = named.toImmList()

    fun checkNoNamedArgs(ctx: C_ExprContext, fnName: R_Name?) {
        val arg = named.firstOrNull()
        if (arg != null) {
            C_Errors.errNamedArgsNotSupported(ctx.msgCtx, fnName, arg.name)
        }
    }
}

private sealed class C_CallArgsAdapter<ArgT> {
    abstract fun bindDefaultValue(vExpr: V_Expr): ArgT

    abstract fun adaptArgType(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            param: C_FunctionCallParameter,
            arg: ArgT
    ): ArgT

    protected fun matchArgType(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            param: C_FunctionCallParameter,
            argType: R_Type
    ): C_TypeAdapter? {
        val paramType = param.type
        return if (paramType.isError()) C_TypeAdapter_Direct else {
            val matcher = C_ArgTypeMatcher_Simple(paramType)
            val m = matcher.match(argType)
            if (m == null && argType.isNotError()) {
                val paramName = param.nameCodeMsg()
                val fnNameCode = callInfo.functionName ?: "?"
                val code = "expr_call_argtype:$fnNameCode:${paramName.code}:${paramType.strCode()}:${argType.strCode()}"
                val msg = "Wrong argument type for parameter ${paramName.msg}: ${argType.str()} instead of ${paramType.str()}"
                ctx.msgCtx.error(callInfo.callPos, code, msg)
            }
            m
        }
    }
}

private object C_CallArgsAdapter_Full: C_CallArgsAdapter<V_Expr>() {
    override fun bindDefaultValue(vExpr: V_Expr) = vExpr

    override fun adaptArgType(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            param: C_FunctionCallParameter,
            arg: V_Expr
    ): V_Expr {
        val argType = arg.type
        val m = matchArgType(ctx, callInfo, param, argType)
        return if (m == null) arg else m.adaptExpr(ctx, arg)
    }
}

private object C_CallArgsAdapter_Partial: C_CallArgsAdapter<C_PartialCallArgument>() {
    override fun bindDefaultValue(vExpr: V_Expr) = C_PartialCallArgument_Expr(vExpr)

    override fun adaptArgType(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            param: C_FunctionCallParameter,
            arg: C_PartialCallArgument
    ): C_PartialCallArgument {
        return when (arg) {
            is C_PartialCallArgument_Wildcard -> arg
            is C_PartialCallArgument_Expr -> {
                val argType = arg.vExpr.type
                val m = matchArgType(ctx, callInfo, param, argType)
                return if (m == null) arg else C_PartialCallArgument_Expr(m.adaptExpr(ctx, arg.vExpr))
            }
        }
    }
}

private class C_FullCallArguments_Impl(
        ctx: C_ExprContext,
        private val args: C_InternalCallArguments_Full
): C_FullCallArguments(ctx) {
    override fun compileSimpleArgs(functionName: R_Name): List<V_Expr> {
        return args.compileSimplePositionalArgs(ctx, functionName)
    }

    override fun compileComplexArgs(callInfo: C_FunctionCallInfo): V_FunctionCallArgs? {
        return args.compileEffectiveArgs(ctx, callInfo)
    }
}

private class C_PartialCallArguments_Impl(
        ctx: C_ExprContext,
        private val args: C_InternalCallArguments_Partial
): C_PartialCallArguments(ctx, args.firstWildcardPos) {
    override fun compileEffectiveArgs(callInfo: C_FunctionCallInfo): C_EffectivePartialArguments? {
        return args.compileEffectiveArgs(ctx, callInfo)
    }

    override fun errPartialNotSupported(functionName: String?) {
        ctx.msgCtx.error(args.firstWildcardPos, C_Errors.msgPartialCallNotAllowed(functionName))
    }
}

private sealed class C_FunctionCallArgument
private class C_FunctionCallArgument_Expr(val vExpr: V_Expr): C_FunctionCallArgument()
private class C_FunctionCallArgument_Wildcard: C_FunctionCallArgument()

private sealed class C_InternalCallArguments

private class C_InternalCallArguments_Full(
        private val genArgs: C_GenericCallArgs<V_Expr>
): C_InternalCallArguments() {
    fun compileSimplePositionalArgs(ctx: C_ExprContext, fnName: R_Name?): List<V_Expr> {
        genArgs.checkNoNamedArgs(ctx, fnName)
        return genArgs.mixed
    }

    fun compileEffectiveArgs(ctx: C_ExprContext, callInfo: C_FunctionCallInfo): V_FunctionCallArgs? {
        val errWatcher = ctx.msgCtx.errorWatcher()
        val args = C_EffectiveArgsBinder(ctx, callInfo, C_CallArgsAdapter_Full, genArgs).getEffectiveArgs()

        val missingParams = callInfo.params.list.filterIndexed { i, _ -> args[i] == null }
        if (missingParams.isNotEmpty()) {
            processMissingParams(ctx, callInfo, missingParams)
        }

        val argsNz = args.filterNotNull().toImmList()
        if (argsNz.size != args.size) return null

        val exprIndexes = argsNz.map { it.index }
        if (exprIndexes.sorted() != callInfo.params.list.indices.toList()) {
            return null
        }

        val exprs = argsNz.sortedBy { it.index }.map { it.value }
        return if (errWatcher.hasNewErrors()) null else V_FunctionCallArgs(exprs, exprIndexes)
    }

    private fun processMissingParams(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            missingParams: List<C_FunctionCallParameter>
    ) {
        val fnNameCode = callInfo.functionNameCode()
        val codeStr = missingParams.joinToString(",") { it.nameCodeMsg().code }
        val missingNames = missingParams.mapNotNull { it.name }
        val msgStr = if (missingNames.isNotEmpty()) missingNames.joinToString(", ") else "${missingNames.size}"
        ctx.msgCtx.error(callInfo.callPos, "expr:call:missing_args:$fnNameCode:$codeStr", "Missing argument(s): $msgStr")
    }
}

private sealed class C_PartialCallArgument
private class C_PartialCallArgument_Expr(val vExpr: V_Expr): C_PartialCallArgument()
private class C_PartialCallArgument_Wildcard: C_PartialCallArgument()

private class C_InternalCallArguments_Partial(
        private val genArgs: C_GenericCallArgs<C_PartialCallArgument>,
        val firstWildcardPos: S_Pos
): C_InternalCallArguments() {
    fun compileEffectiveArgs(ctx: C_ExprContext, callInfo: C_FunctionCallInfo): C_EffectivePartialArguments? {
        val errWatcher = ctx.msgCtx.errorWatcher()

        val args = C_EffectiveArgsBinder(ctx, callInfo, C_CallArgsAdapter_Partial, genArgs).getEffectiveArgs()
        checkEquals(args.size, callInfo.params.list.size)

        val allArgs = addWildArgs(callInfo, args)
        val res = makeEffectiveArgs(callInfo, allArgs)

        return if (errWatcher.hasNewErrors()) null else res
    }

    private fun addWildArgs(
            callInfo: C_FunctionCallInfo,
            args: List<IndexedValue<C_PartialCallArgument>?>
    ): List<IndexedValue<C_PartialCallArgument>> {
        var nextIndex = (args.mapNotNull { it?.index }.maxOrNull() ?: -1) + 1
        return callInfo.params.list.mapIndexed { i, param ->
            val arg = args[i]
            if (arg != null) arg else {
                val index = nextIndex++
                IndexedValue(index, C_PartialCallArgument_Wildcard())
            }
        }.toImmList()
    }

    private fun makeEffectiveArgs(
            callInfo: C_FunctionCallInfo,
            args: List<IndexedValue<C_PartialCallArgument>>
    ): C_EffectivePartialArguments {
        class ArgEntry(val paramIndex: Int, val exprIndex: Int, val arg: C_PartialCallArgument)
        val argEntries = args.withIndex().map { (paramIndex, idxArg) -> ArgEntry(paramIndex, idxArg.index, idxArg.value) }

        val exprArgs = mutableListOf<V_Expr>()
        val wildArgs = mutableListOf<R_Type>()
        val combinedArgs0 = mutableListOf<IndexedValue<R_PartialArgMapping>>()

        val params = callInfo.params.list

        argEntries.sortedBy { it.exprIndex }.forEach {
            when (it.arg) {
                is C_PartialCallArgument_Expr -> {
                    combinedArgs0.add(IndexedValue(it.paramIndex, R_PartialArgMapping(false, exprArgs.size)))
                    exprArgs.add(it.arg.vExpr)
                }
                is C_PartialCallArgument_Wildcard -> {
                    combinedArgs0.add(IndexedValue(it.paramIndex, R_PartialArgMapping(true, wildArgs.size)))
                    wildArgs.add(params[it.paramIndex].type)
                }
            }
        }

        checkEquals(combinedArgs0.map { it.index }.sorted(), params.indices.toList())
        val combinedArgs = combinedArgs0.sortedBy { it.index }.map { it.value }

        return C_EffectivePartialArguments(exprArgs, wildArgs, combinedArgs)
    }
}

private class C_EffectiveArgsBinder<ArgT>(
        private val ctx: C_ExprContext,
        private val callInfo: C_FunctionCallInfo,
        private val adapter: C_CallArgsAdapter<ArgT>,
        private val args: C_GenericCallArgs<ArgT>
) {
    fun getEffectiveArgs(): List<IndexedValue<ArgT>?> {
        val params = callInfo.params.list

        val res = mutableListOf<IndexedValue<ArgT>?>()
        res.addAll(params.map { null })

        for (i in 0 until min(args.positional.size, params.size)) {
            val arg = args.positional[i]
            bindArgument(ctx, callInfo, res, i, arg.value, arg.index)
        }

        if (args.positional.size > params.size) {
            errTooManyArguments(ctx, callInfo)
        }

        for ((name, arg) in args.named) {
            bindNamedArgument(ctx, callInfo, res, name, arg.value, arg.index)
        }

        var defaultIndex = (res.mapNotNull { it?.index }.maxOrNull() ?: -1) + 1

        for ((i, param) in params.withIndex()) {
            if (res[i] == null) {
                val vDefExpr = param.createDefaultValueExpr(ctx, callInfo.callPos)
                if (vDefExpr != null) {
                    val arg = adapter.bindDefaultValue(vDefExpr)
                    res[i] = IndexedValue(defaultIndex++, arg)
                }
            }
        }

        return res
    }

    private fun errTooManyArguments(ctx: C_ExprContext, callInfo: C_FunctionCallInfo) {
        val expCount = callInfo.params.list.size
        val actCount = args.positional.size + args.named.size
        val fnNameCode = callInfo.functionNameCode()
        var msg = "Too many arguments"
        if (callInfo.functionName != null) msg += " for function '${callInfo.functionName}'"
        msg += ": $actCount instead of $expCount"
        ctx.msgCtx.error(callInfo.callPos, "expr:call:too_many_args:$fnNameCode:$expCount:$actCount", msg)
    }

    private fun bindNamedArgument(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            res: MutableList<IndexedValue<ArgT>?>,
            name: C_Name,
            arg: ArgT,
            argIndex: Int
    ) {
        val i = callInfo.params.list.indexOfFirst { it.name == name.rName }
        if (i < 0) {
            val fnNameCode = callInfo.functionNameCode()
            val fnMsg = if (callInfo.functionName == null) "Function" else "Function '${callInfo.functionName}'"
            val msg = "$fnMsg has no parameter '$name'"
            ctx.msgCtx.error(name.pos, "expr:call:unknown_named_arg:$fnNameCode:$name", msg)
        } else if (res[i] != null) {
            val fnNameCode = callInfo.functionNameCode()
            ctx.msgCtx.error(name.pos, "expr:call:named_arg_already_specified:$fnNameCode:$name",
                    "Value for parameter '$name' specified more than once")
        } else {
            bindArgument(ctx, callInfo, res, i, arg, argIndex)
        }
    }

    private fun bindArgument(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            res: MutableList<IndexedValue<ArgT>?>,
            paramIndex: Int,
            arg: ArgT,
            argIndex: Int
    ) {
        val param = callInfo.params.list[paramIndex]
        val arg2 = adapter.adaptArgType(ctx, callInfo, param, arg)
        res[paramIndex] = IndexedValue(argIndex, arg2)
    }
}

private object C_ArgsListProcessor {
    fun processArgs(ctx: C_ExprContext, args: List<C_CallArgument>): C_InternalCallArguments? {
        val failFlag = MutableBoolean()

        val genArgs = makeGenericArgs(ctx, failFlag, args)

        val wildArgs = genArgs.filter {
            when (it.value) {
                is C_FunctionCallArgument_Wildcard -> true
                is C_FunctionCallArgument_Expr -> false
            }
        }

        val res = if (wildArgs.isEmpty()) {
            compileFullArgs(ctx, failFlag, genArgs)
        } else {
            compilePartialArgs(ctx, failFlag, genArgs, wildArgs)
        }

        return if (failFlag.isTrue) null else res
    }

    private fun makeGenericArgs(
            ctx: C_ExprContext,
            failFlag: MutableBoolean,
            args: List<C_CallArgument>
    ): List<C_GenericCallArg<C_FunctionCallArgument>> {
        return args.map {
            val fnArg = when (it.value) {
                is C_CallArgumentValue_Expr -> {
                    val type = it.value.vExpr.type
                    val unitOk = C_Utils.checkUnitType(ctx.msgCtx, it.value.pos, type) {
                        "expr_arg_unit" toCodeMsg "Argument expression returns nothing"
                    }
                    if (!unitOk) {
                        failFlag.setTrue()
                    }
                    C_FunctionCallArgument_Expr(it.value.vExpr)
                }
                is C_CallArgumentValue_Wildcard -> C_FunctionCallArgument_Wildcard()
            }
            C_GenericCallArg(it, fnArg)
        }
    }

    private fun compileFullArgs(
            ctx: C_ExprContext,
            failFlag: MutableBoolean,
            genArgs: List<C_GenericCallArg<C_FunctionCallArgument>>
    ): C_InternalCallArguments {
        val fullArgs = genArgs.mapIndexed { exprIndex, genArg ->
            when (genArg.value) {
                is C_FunctionCallArgument_Expr -> C_GenericCallArg(genArg, genArg.value.vExpr)
                is C_FunctionCallArgument_Wildcard -> throw IllegalStateException() // already checked
            }
        }

        val proto = compileArgs0(ctx, failFlag, fullArgs)
        return C_InternalCallArguments_Full(proto)
    }

    private fun compilePartialArgs(
            ctx: C_ExprContext,
            failFlag: MutableBoolean,
            genArgs: List<C_GenericCallArg<C_FunctionCallArgument>>,
            wildArgs: List<C_GenericCallArg<C_FunctionCallArgument>>
    ): C_InternalCallArguments {
        val lastArg = genArgs.last()
        val realArgs = if (lastArg.name == null && lastArg.index == wildArgs.last().index) {
            if (wildArgs.size > 1) {
                val code = "expr:call:last_wildcard_not_alone"
                val msg = "Wildcard not allowed as the last argument if there are other wildcard arguments"
                ctx.msgCtx.error(lastArg.valuePos, code, msg)
            }
            genArgs.subList(0, genArgs.size - 1)
        } else {
            genArgs
        }

        val partialArgs = realArgs.map {
            val v = it.value
            val v2 = when (v) {
                is C_FunctionCallArgument_Expr -> C_PartialCallArgument_Expr(v.vExpr)
                is C_FunctionCallArgument_Wildcard -> C_PartialCallArgument_Wildcard()
            }
            C_GenericCallArg(it, v2)
        }

        val firstWildPos = wildArgs.first().valuePos
        val proto = compileArgs0(ctx, failFlag, partialArgs)
        return C_InternalCallArguments_Partial(proto, firstWildPos)
    }

    private fun <T> compileArgs0(
            ctx: C_ExprContext,
            failFlag: MutableBoolean,
            args: List<C_GenericCallArg<T>>
    ): C_GenericCallArgs<T> {
        val mixed = mutableListOf<T>()
        val positional = mutableListOf<C_GenericCallArg<T>>()
        val namedNames = mutableSetOf<String>()
        val named = mutableListOf<C_NameValue<C_GenericCallArg<T>>>()

        val errPositionalAfterNamed = ctx.msgCtx.firstErrorReporter()

        for (arg in args) {
            mixed.add(arg.value)

            if (arg.name == null) {
                if (named.isNotEmpty()) {
                    errPositionalAfterNamed.error(arg.valuePos, "expr:call:positional_after_named",
                            "Unnamed argument after a named argument")
                    failFlag.setTrue()
                } else {
                    positional.add(arg)
                }
            } else {
                val name = arg.name
                if (!namedNames.add(name.str)) {
                    ctx.msgCtx.error(name.pos, "expr:call:named_arg_dup:$name",
                            "Named argument '$name' specified more than once")
                } else {
                    named.add(C_NameValue(name, arg))
                }
            }
        }

        return C_GenericCallArgs(mixed, positional, named)
    }
}
