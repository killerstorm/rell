/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.lib.R_TestOpType
import net.postchain.rell.model.*
import net.postchain.rell.utils.RecursionAwareCalculator
import net.postchain.rell.utils.RecursionAwareResult
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

abstract class C_GlobalFunction {
    open fun getFunctionDefinition(): R_FunctionDefinition? = null
    open fun getAbstractDescriptor(): C_AbstractDescriptor? = null

    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr
}

class C_StructGlobalFunction(private val struct: R_Struct): C_GlobalFunction() {
    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        return compileCall(ctx, struct, name.pos, args)
    }

    companion object {
        fun compileCall(ctx: C_ExprContext, struct: R_Struct, fnPos: S_Pos, args: List<S_CallArgument>): V_Expr {
            val createCtx = C_CreateContext(ctx, struct.initFrameGetter, fnPos.toFilePos())

            val callArgs = C_CallArgument.compileAttributes(ctx, args, struct.attributes)
            val attrArgs = C_CallArgument.toAttrArguments(ctx, callArgs, C_CodeMsg("struct", "struct expression"))

            val attrs = C_AttributeResolver.resolveCreate(createCtx, struct.attributes, attrArgs, fnPos)

            val dbModRes = ctx.getDbModificationRestriction()
            if (dbModRes != null) {
                ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                    val dbModAttr = attrs.implicitAttrs.firstOrNull { it.attr.isExprDbModification }
                    if (dbModAttr != null) {
                        val code = "${dbModRes.code}:attr:${dbModAttr.attr.name}"
                        val msg = "${dbModRes.msg} (default value of attribute '${dbModAttr.attr.name}')"
                        ctx.msgCtx.error(fnPos, code, msg)
                    }
                }
            }

            return V_StructExpr(ctx, fnPos, struct, attrs.explicitAttrs, attrs.implicitAttrs, attrs.exprFacts)
        }
    }
}

sealed class C_FunctionHeader(val explicitType: R_Type?, val params: C_FormalParameters, val body: C_FunctionBody?) {
    abstract val declarationType: C_DeclarationType

    fun returnType(): R_Type {
        return explicitType ?: (body?.returnType()?.value ?: R_CtErrorType)
    }
}

class C_UserFunctionHeader(
        explicitType: R_Type?,
        params: C_FormalParameters,
        val fnBody: C_UserFunctionBody?
): C_FunctionHeader(explicitType, params, fnBody) {
    override val declarationType = C_DeclarationType.FUNCTION

    fun functionType(): R_Type {
        val retType = returnType()
        val paramTypes = params.list.map { it.type }
        return R_FunctionType(paramTypes, retType)
    }

    companion object {
        val ERROR = C_UserFunctionHeader(null, C_FormalParameters.EMPTY, null)
    }
}

class C_OperationFunctionHeader(val params: C_FormalParameters) {
    companion object {
        val ERROR = C_OperationFunctionHeader(C_FormalParameters.EMPTY)
    }
}

class C_QueryFunctionHeader(
        explicitType: R_Type?,
        params: C_FormalParameters,
        val queryBody: C_QueryFunctionBody?
): C_FunctionHeader(explicitType, params, queryBody) {
    override val declarationType = C_DeclarationType.QUERY

    companion object {
        val ERROR = C_QueryFunctionHeader(null, C_FormalParameters.EMPTY, null)
    }
}

class C_UserGlobalFunction(
        val rFunction: R_FunctionDefinition,
        private val abstract: C_AbstractDescriptor?
): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_UserFunctionHeader.ERROR)

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate.set(header)
    }

    override fun getFunctionDefinition() = rFunction
    override fun getAbstractDescriptor() = abstract

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        val header = headerLate.get()
        val retType = C_FunctionUtils.compileReturnType(ctx, name, header)
        val callInfo = C_FunctionCallInfo.forDirectFunction(name, header.params)
        val callTarget = C_FunctionCallTarget_UserFunction(ctx, callInfo, rFunction, retType)
        return C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)
    }
}

class C_OperationGlobalFunction(val rOp: R_OperationDefinition): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_OperationFunctionHeader.ERROR)

    fun setHeader(header: C_OperationFunctionHeader) {
        headerLate.set(header)
    }

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        val header = headerLate.get()
        val callInfo = C_FunctionCallInfo.forDirectFunction(name, header.params)
        val callTarget = C_FunctionCallTarget_Operation(ctx, callInfo, rOp)
        val vExpr = C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)

        if (!ctx.defCtx.modCtx.isTestLib()) {
            ctx.msgCtx.error(name.pos, "expr:operation_call:no_test:$name",
                    "Operation calls are allowed only in tests or REPL")
        }

        return vExpr
    }
}

class C_QueryGlobalFunction(val rQuery: R_QueryDefinition): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_QueryFunctionHeader.ERROR)

    fun setHeader(header: C_QueryFunctionHeader) {
        headerLate.set(header)
    }

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        val header = headerLate.get()
        val retType = C_FunctionUtils.compileReturnType(ctx, name, header)
        val callInfo = C_FunctionCallInfo.forDirectFunction(name, header.params)
        val callTarget = C_FunctionCallTarget_UserFunction(ctx, callInfo, rQuery, retType)
        return C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)
    }
}

private class C_FunctionCallTarget_UserFunction(
        ctx: C_ExprContext,
        callInfo: C_FunctionCallInfo,
        private val rFunction: R_RoutineDefinition,
        private val retType: R_Type?
): C_FunctionCallTarget_Regular(ctx, callInfo) {
    override fun retType() = retType
    override fun createVTarget(): V_FunctionCallTarget = V_FunctionCallTarget_UserFunction(rFunction)
}

private class C_FunctionCallTarget_Operation(
        ctx: C_ExprContext,
        callInfo: C_FunctionCallInfo,
        private val rOp: R_OperationDefinition
): C_FunctionCallTarget_Regular(ctx, callInfo) {
    override fun retType() = R_TestOpType
    override fun createVTarget() = V_FunctionCallTarget_Operation(rOp)
}

sealed class C_FunctionBody(bodyCtx: C_FunctionBodyContext) {
    private val retTypeCalculator = bodyCtx.appCtx.functionReturnTypeCalculator

    fun returnType(): RecursionAwareResult<R_Type> {
        val res = retTypeCalculator.calculate(this)
        return res
    }

    abstract fun calculateReturnType(): R_Type

    companion object {
        fun createReturnTypeCalculator(): RecursionAwareCalculator<C_FunctionBody, R_Type> {
            // Experimental threshold with default JRE settings is 500 (depth > 500 ==> StackOverflowError).
            return RecursionAwareCalculator(200, R_CtErrorType) {
                it.calculateReturnType()
            }
        }
    }
}

sealed class C_CommonFunctionBody<T>(bodyCtx: C_FunctionBodyContext, sBody: S_FunctionBody): C_FunctionBody(bodyCtx) {
    private var state: BodyState<T> = BodyState.StartState(bodyCtx, sBody)

    protected abstract fun getErrorBody(): T
    protected abstract fun getReturnType(body: T): R_Type
    protected abstract fun compileBody(ctx: C_FunctionBodyContext, sBody: S_FunctionBody): T

    final override fun calculateReturnType(): R_Type {
        val s = state
        return when (s) {
            is BodyState.StartState -> calculateReturnType0(s)
            is BodyState.EndState -> getReturnType(s.rBody)
        }
    }

    private fun calculateReturnType0(s: BodyState.StartState<T>): R_Type {
        s.bodyCtx.executor.checkPass(C_CompilerPass.EXPRESSIONS)

        if (!s.sBody.returnsValue()) {
            return R_UnitType
        }

        val rBody = doCompile(s)
        val rType = getReturnType(rBody)
        return rType
    }

    fun compile(): T {
        // Needed to put the type calculation result to the cache. If this is not done, in case of a recursion,
        // subsequently getting the return type will calculate it and emit an extra compilation error (recursion).
        returnType()

        val s = state
        val res = when (s) {
            is BodyState.StartState -> doCompile(s)
            is BodyState.EndState -> s.rBody
        }

        return res
    }

    private fun doCompile(s: BodyState.StartState<T>): T {
        check(state === s)
        s.bodyCtx.executor.checkPass(C_CompilerPass.EXPRESSIONS)

        var res = getErrorBody()

        try {
            res = compileBody(s.bodyCtx, s.sBody)
        } finally {
            state = BodyState.EndState(res)
        }

        return res
    }

    private sealed class BodyState<T> {
        class StartState<T>(val bodyCtx: C_FunctionBodyContext, val sBody: S_FunctionBody): BodyState<T>()
        class EndState<T>(val rBody: T): BodyState<T>()
    }
}

class C_UserFunctionBody(bodyCtx: C_FunctionBodyContext, sBody: S_FunctionBody): C_CommonFunctionBody<R_FunctionBody>(bodyCtx, sBody) {
    override fun getErrorBody() = R_FunctionBody.ERROR
    override fun getReturnType(body: R_FunctionBody) = body.type
    override fun compileBody(ctx: C_FunctionBodyContext, sBody: S_FunctionBody) = sBody.compileFunction(ctx)
}

class C_QueryFunctionBody(bodyCtx: C_FunctionBodyContext, sBody: S_FunctionBody): C_CommonFunctionBody<R_QueryBody>(bodyCtx, sBody) {
    override fun getErrorBody() = R_UserQueryBody.ERROR
    override fun getReturnType(body: R_QueryBody) = body.retType
    override fun compileBody(ctx: C_FunctionBodyContext, sBody: S_FunctionBody) = sBody.compileQuery(ctx)
}

class C_FormalParameter(
        val name: S_Name,
        val type: R_Type,
        private val index: Int,
        private val defaultValue: C_ParameterDefaultValue?
) {
    fun toCallParameter() = C_FunctionCallParameter(name.str, type, index, defaultValue)

    fun createVarParam(ptr: R_VarPtr): R_VarParam {
        return R_VarParam(name.str, type, ptr)
    }

    fun createMirrorAttr(mutable: Boolean): R_Attribute {
        return R_Attribute(
                index,
                name.str,
                type,
                mutable = mutable,
                exprGetter = defaultValue?.rGetter
        )
    }
}

class C_FormalParameters(list: List<C_FormalParameter>) {
    val list = list.toImmList()
    val map = list.associateBy { it.name.str }.toMap().toImmMap()

    val callParameters by lazy {
        val params = list.map { it.toCallParameter() }
        C_FunctionCallParameters(params)
    }

    fun compile(frameCtx: C_FrameContext): C_ActualParameters {
        val inited = mutableMapOf<C_VarUid, C_VarFact>()
        val names = mutableSetOf<String>()
        val rParams = mutableListOf<R_VarParam>()

        val blkCtx = frameCtx.rootBlkCtx

        for (param in list) {
            val name = param.name
            val nameStr = name.str

            if (!names.add(nameStr)) {
                frameCtx.msgCtx.error(name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
            } else if (param.type.isNotError()) {
                val cVarRef = blkCtx.addLocalVar(name, param.type, false, null)
                inited[cVarRef.target.uid] = C_VarFact.YES
                val rVarParam = param.createVarParam(cVarRef.ptr)
                rParams.add(rVarParam)
            }
        }

        val varFacts = C_VarFacts.of(inited = inited.toMap())

        val stmtCtx = C_StmtContext.createRoot(blkCtx)
                .updateFacts(varFacts)

        return C_ActualParameters(stmtCtx, rParams)
    }

    companion object {
        val EMPTY = C_FormalParameters(listOf())

        fun compile(defCtx: C_DefinitionContext, params: List<S_FormalParameter>, gtv: Boolean): C_FormalParameters {
            val cParams = mutableListOf<C_FormalParameter>()

            for ((index, param) in params.withIndex()) {
                val cParam = param.compile(defCtx, index)
                cParams.add(cParam)
            }

            if (gtv && defCtx.globalCtx.compilerOptions.gtv && cParams.isNotEmpty()) {
                defCtx.executor.onPass(C_CompilerPass.VALIDATION) {
                    for (cExtParam in cParams) {
                        checkGtvParam(defCtx.msgCtx, cExtParam)
                    }
                }
            }

            return C_FormalParameters(cParams)
        }

        private fun checkGtvParam(msgCtx: C_MessageContext, param: C_FormalParameter) {
            val nameStr = param.name.str
            C_Utils.checkGtvCompatibility(msgCtx, param.name.pos, param.type, true, "param_nogtv:$nameStr",
                    "Type of parameter '$nameStr'")
        }
    }
}

class C_ActualParameters(val stmtCtx: C_StmtContext, rParams: List<R_VarParam>) {
    val rParams = rParams.toImmList()
}
