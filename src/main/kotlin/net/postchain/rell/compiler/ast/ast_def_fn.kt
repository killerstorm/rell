package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.MutableTypedKeyMap
import net.postchain.rell.utils.PostchainUtils
import net.postchain.rell.utils.TypedKeyMap
import net.postchain.rell.utils.toImmMap
import kotlin.math.min

class S_FormalParameter(val attr: S_AttrHeader, val expr: S_Expr?) {
    fun compile(defCtx: C_DefinitionContext, index: Int): C_FormalParameter {
        val name = attr.name
        val type = attr.compileType(defCtx.nsCtx)

        val defaultValue = if (expr == null) null else {
            val rErrorExpr = C_Utils.errorRExpr(type)
            val rExprLate = C_LateInit(C_CompilerPass.EXPRESSIONS, rErrorExpr)
            val rValueLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_DefaultValue(rErrorExpr, false))

            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                val vExpr = compileExpr(defCtx, type)
                val rExpr = vExpr.toRExpr()
                rExprLate.set(rExpr)
                rValueLate.set(R_DefaultValue(rExpr, vExpr.info.hasDbModifications))
            }

            C_ParameterDefaultValue(expr.startPos, name.str, rExprLate.getter, defCtx.initFrameGetter, rValueLate.getter)
        }

        return C_FormalParameter(name, type, index, defaultValue)
    }

    private fun compileExpr(defCtx: C_DefinitionContext, paramType: R_Type): V_Expr {
        val exprCtx = defCtx.initExprCtx
        val cExpr = expr!!.compileOpt(exprCtx, C_TypeHint.ofType(paramType))
        cExpr ?: return C_Utils.errorVExpr(exprCtx, expr.startPos, paramType)

        val vExpr = cExpr.value()

        return if (paramType.isError()) vExpr else {
            val valueType = vExpr.type
            val matcher: C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(paramType)
            val m = matcher.match(valueType)
            if (m != null) m.adaptExpr(exprCtx, vExpr) else {
                val code = "def:param:type:${attr.name}:${paramType.toStrictString()}:${valueType.toStrictString()}"
                val msg = "Wrong type of default value of parameter '${attr.name}': $valueType instead of $paramType"
                defCtx.msgCtx.error(expr.startPos, code, msg)
                vExpr
            }
        }
    }
}

abstract class S_FunctionBody {
    protected abstract fun processStatementVars(): TypedKeyMap
    protected abstract fun compileQuery0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement
    protected abstract fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement

    abstract fun returnsValue(): Boolean

    fun compileQuery(ctx: C_FunctionBodyContext): R_QueryBody {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(ctx.defCtx, ctx.defNames.appLevelName, ctx.explicitRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)
        val actParams = ctx.forParams.compile(frameCtx)

        val cBody = compileQuery0(ctx, actParams.stmtCtx)
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)
        val rRetType = fnCtx.actualReturnType()
        return R_UserQueryBody(rRetType, actParams.rParams, cBody.rStmt, callFrame.rFrame)
    }

    fun compileFunction(ctx: C_FunctionBodyContext): R_FunctionBody {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(ctx.defCtx, ctx.defNames.appLevelName, ctx.explicitRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)
        val actParams = ctx.forParams.compile(frameCtx)

        val cBody = compileFunction0(ctx, actParams.stmtCtx)
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)
        val rRetType = fnCtx.actualReturnType()
        return R_FunctionBody(rRetType, actParams.rParams, cBody.rStmt, callFrame.rFrame)
    }
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun processStatementVars() = TypedKeyMap()

    override fun returnsValue() = true

    override fun compileQuery0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cExpr = expr.compile(stmtCtx, C_TypeHint.ofType(bodyCtx.explicitRetType))
        val vExpr = cExpr.value()

        val type = vExpr.type
        C_Utils.checkUnitType(bodyCtx.namePos, type, "query_exprtype_unit", "Query expressions returns nothing")

        val adapter = stmtCtx.fnCtx.matchReturnType(bodyCtx.namePos, type)
        val vExpr2 = adapter.adaptExpr(stmtCtx.exprCtx, vExpr)

        val rExpr = vExpr2.toRExpr()
        return C_Statement(R_ReturnStatement(rExpr), true)
    }

    override fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val vExpr = expr.compile(stmtCtx, C_TypeHint.ofType(bodyCtx.explicitRetType)).value()
        val type = vExpr.type

        val adapter = stmtCtx.fnCtx.matchReturnType(bodyCtx.namePos, type)
        val vExpr2 = adapter.adaptExpr(stmtCtx.exprCtx, vExpr)
        val rExpr = vExpr2.toRExpr()

        return if (rExpr.type != R_UnitType) {
            C_Statement(R_ReturnStatement(rExpr), true)
        } else {
            C_Statement(R_ExprStatement(rExpr), false)
        }
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun processStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        body.discoverVars(map)
        return map.immutableCopy()
    }

    override fun returnsValue(): Boolean {
        return body.returnsValue() ?: false
    }

    override fun compileQuery0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cBody = body.compile(stmtCtx)

        C_Errors.check(cBody.returnAlways, bodyCtx.namePos) {
            val nameStr = bodyCtx.defNames.qualifiedName
            "query_noreturn:$nameStr" to "Query '$nameStr': not all code paths return value"
        }

        return cBody
    }

    override fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cBody = body.compile(stmtCtx)

        val retType = stmtCtx.fnCtx.actualReturnType()
        if (retType != R_UnitType) {
            C_Errors.check(cBody.returnAlways, bodyCtx.namePos) {
                val nameStr = bodyCtx.defNames.qualifiedName
                "fun_noreturn:$nameStr" to "Function '$nameStr': not all code paths return value"
            }
        }

        return cBody
    }
}

class S_FunctionDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val qualifiedName: List<S_Name>,
        val params: List<S_FormalParameter>,
        val retType: S_Type?,
        val body: S_FunctionBody?
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        if (qualifiedName.isEmpty()) {
            // May happen in Eclipse while the user is coding a new function.
            return
        }

        val name = qualifiedName.last()
        ctx.checkNotExternal(name.pos, C_DeclarationType.FUNCTION)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.FUNCTION, name, abstract = true, override = true)
        modifiers.compile(ctx, modTarget)

        val abstract = modTarget.abstract?.get() ?: false
        val override = modTarget.override?.get() ?: false
        val qName = C_Utils.nameStr(qualifiedName)

        if (qualifiedName.size >= 2 && !override) {
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:qname_no_override:$qName",
                    "Invalid function name: '$qName' (qualified names allowed only for override)")
        }

        if (abstract && override) {
            ctx.msgCtx.error(qualifiedName[0].pos, "fn:abstract_override:$qName", "Both 'abstract' and 'override' specified")
        }

        if (!abstract && body == null) {
            ctx.msgCtx.error(name.pos, "fn:no_body:$qName", "Function '$qName' must have a body (it is not abstract)")
        }

        val defNames = definitionNames(ctx)
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.FUNCTION, defNames.defId)

        if (abstract) {
            compileAbstract(defCtx, name, defNames)
        } else if (override) {
            compileOverride(defCtx)
        } else {
            compileRegular(defCtx, name, defNames)
        }
    }

    private fun compileRegular(defCtx: C_DefinitionContext, name: S_Name, defNames: R_DefinitionNames) {
        val rFn = R_FunctionDefinition(defNames, defCtx.initFrameGetter)
        val cFn = C_UserGlobalFunction(rFn, null)
        defCtx.mntCtx.nsBuilder.addFunction(name, cFn)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileRegularHeader(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileRegularBody(header, rFn)
            }
        }
    }

    private fun definitionNames(ctx: C_MountContext): R_DefinitionNames {
        val qName = qualifiedName.map { it.str }
        return ctx.nsCtx.defNames(qName)
    }

    private fun compileRegularHeader(defCtx: C_DefinitionContext, cFn: C_UserGlobalFunction): C_UserFunctionHeader {
        val header = compileHeader0(defCtx, cFn.rFunction.names)
        cFn.setHeader(header)
        return header
    }

    private fun compileHeader0(defCtx: C_DefinitionContext, defNames: R_DefinitionNames): C_UserFunctionHeader {
        return C_FunctionUtils.compileFunctionHeader(defCtx, qualifiedName.last(), defNames, params, retType, body)
    }

    private fun compileRegularBody(header: C_UserFunctionHeader, rFn: R_FunctionDefinition) {
        if (header.fnBody != null) {
            val rBody = header.fnBody.compile()
            rFn.setBody(rBody)
        }
    }

    private fun compileAbstract(defCtx: C_DefinitionContext, name: S_Name, defNames: R_DefinitionNames) {
        if (!defCtx.modCtx.abstract) {
            val mName = defCtx.modCtx.moduleName.str()
            val qName = C_Utils.nameStr(qualifiedName)
            defCtx.msgCtx.error(qualifiedName[0].pos, "fn:abstract:non_abstract_module:$mName:$qName",
                    "Abstract function can be defined only in abstract module")
        }

        val mntCtx = defCtx.mntCtx
        val rFn = R_FunctionDefinition(defNames, defCtx.initFrameGetter)

        val descriptor = C_AbstractDescriptor(name.pos, rFn, body != null)
        mntCtx.fileCtx.addAbstractFunction(descriptor)

        val cFn = C_UserGlobalFunction(rFn, descriptor)
        mntCtx.nsBuilder.addFunction(name, cFn)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileRegularHeader(defCtx, cFn)
            descriptor.setHeader(header)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                header.fnBody?.compile() // Make sure no compilation errors.
                descriptor.bind()
            }
        }
    }

    private fun compileOverride(defCtx: C_DefinitionContext) {
        if (defCtx.modCtx.repl) {
            defCtx.msgCtx.error(qualifiedName[0].pos, "fn:override:repl", "Cannot override a function in REPL")
        }

        val descriptor = C_OverrideDescriptor(qualifiedName[0].pos)
        defCtx.mntCtx.fileCtx.addOverrideFunction(descriptor)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            compileOverrideHeader(defCtx, descriptor)
        }
    }

    private fun compileOverrideHeader(defCtx: C_DefinitionContext, overDescriptor: C_OverrideDescriptor) {
        val fn = defCtx.nsCtx.getFunctionOpt(qualifiedName)?.getDef()
        if (fn == null) {
            val qName = C_Utils.nameStr(qualifiedName)
            defCtx.msgCtx.error(qualifiedName[0].pos, "fn:override:not_found:$qName", "Function not found: '$qName'")
        }

        val absDescriptor = if (fn == null) null else {
            val desc = fn.getAbstractDescriptor()
            if (desc == null) {
                val qName = fn.getFunctionDefinition()?.appLevelName ?: C_Utils.nameStr(qualifiedName)
                defCtx.msgCtx.error(qualifiedName[0].pos, "fn:override:not_abstract:[$qName]", "Function is not abstract: '$qName'")
            }
            desc
        }

        val names = definitionNames(defCtx.mntCtx)
        val header = compileHeader0(defCtx, names)

        overDescriptor.setAbstract(absDescriptor)
        if (header.fnBody != null) {
            overDescriptor.setBody(header.fnBody)
        }

        defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            compileOverrideBody(defCtx.mntCtx, header, absDescriptor)
        }
    }

    private fun compileOverrideBody(
            ctx: C_MountContext,
            header: C_UserFunctionHeader,
            absDescriptor: C_AbstractDescriptor?
    ) {
        header.fnBody?.compile()
        if (absDescriptor != null) {
            val retType = header.returnType()
            checkOverrideSignature(ctx, absDescriptor, header.params, retType)
        }
    }

    private fun checkOverrideSignature(
            ctx: C_MountContext,
            absDescriptor: C_AbstractDescriptor,
            params: C_FormalParameters,
            overType: R_Type
    ) {
        val overPos = qualifiedName[0].pos
        val absHeader = absDescriptor.header()

        val absType = absHeader.returnType()
        C_Types.matchOpt(ctx.msgCtx, absType, overType, overPos, "fn:override:ret_type", "Return type mismatch")

        val absParams = absHeader.params.list
        val overParams = params.list

        if (absParams.size != overParams.size) {
            ctx.msgCtx.error(overPos, "fn:override:param_cnt:${absParams.size}:${overParams.size}",
                    "Wrong number of parameters: ${overParams.size} instead of ${absParams.size}")
        }

        for (i in 0 until min(overParams.size, absParams.size)) {
            val absParam = absParams[i]
            val overParam = overParams[i]
            if (absParam.type.isNotError() && overParam.type.isNotError() && overParam.type != absParam.type) {
                val code = "fn:override:param_type:$i:${absParam.name.str}"
                val msg = "Parameter '${absParam.name.str}' (${i+1}) type mismatch "
                val err = C_Errors.errTypeMismatch(overParam.name.pos, overParam.type, absParam.type, code, msg)
                ctx.msgCtx.error(err)
            }
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val name = qualifiedName.last()
        b.node(this, name, IdeOutlineNodeType.FUNCTION)
    }
}

class S_OperationDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_FormalParameter>,
        val body: S_Statement
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.OPERATION)
        ctx.checkNotReplOrTest(name.pos, C_DeclarationType.OPERATION)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.OPERATION, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)
        checkSysMountNameConflict(ctx, name.pos, C_DeclarationType.OPERATION, mountName, PostchainUtils.STD_OPS)

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OPERATION, names.defId)
        val mirrorStructs = C_Utils.createMirrorStructs(
                ctx.appCtx, names, defCtx.initFrameGetter, defCtx.definitionType, operation = mountName)

        val rOperation = R_OperationDefinition(names, defCtx.initFrameGetter, mountName, mirrorStructs)
        val cOperation = C_OperationGlobalFunction(rOperation)

        ctx.appCtx.defsAdder.addOperation(rOperation)
        ctx.nsBuilder.addOperation(name, cOperation)
        ctx.mntBuilder.addOperation(name, rOperation)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cOperation, mirrorStructs)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(defCtx, rOperation, header)
            }
        }
    }

    private fun compileHeader(
            defCtx: C_DefinitionContext,
            cOperation: C_OperationGlobalFunction,
            mirrorStructs: R_MirrorStructs
    ): C_OperationFunctionHeader {
        val forParams = C_FormalParameters.compile(defCtx, params, true)
        val header = C_OperationFunctionHeader(forParams)
        cOperation.setHeader(header)
        compileMirrorStructAttrs(mirrorStructs, forParams, false)
        compileMirrorStructAttrs(mirrorStructs, forParams, true)
        return header
    }

    private fun compileMirrorStructAttrs(mirrorStructs: R_MirrorStructs, forParams: C_FormalParameters, mutable: Boolean) {
        val struct = mirrorStructs.getStruct(mutable)
        val attrs = forParams.list
                .map { it.createMirrorAttr(mutable) }
                .map { it.name to it }
                .toMap().toImmMap()
        struct.setAttributes(attrs)
    }

    private fun compileBody(defCtx: C_DefinitionContext, rOperation: R_OperationDefinition, header: C_OperationFunctionHeader) {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(defCtx, rOperation.appLevelName, null, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)

        val actParams = header.params.compile(frameCtx)
        val cBody = body.compile(actParams.stmtCtx)
        val rBody = cBody.rStmt
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)

        rOperation.setInternals(actParams.rParams, rBody, callFrame.rFrame)
    }

    private fun processStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        body.discoverVars(map)
        return map.immutableCopy()
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.OPERATION)
    }
}

class S_QueryDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_FormalParameter>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.QUERY)
        ctx.checkNotReplOrTest(name.pos, C_DeclarationType.QUERY)

        val modTarget = C_ModifierTarget(C_ModifierTargetType.QUERY, name, mount = true)
        modifiers.compile(ctx, modTarget)

        val names = ctx.nsCtx.defNames(name.str)
        val mountName = ctx.mountName(modTarget, name)
        checkSysMountNameConflict(ctx, name.pos, C_DeclarationType.QUERY, mountName, PostchainUtils.STD_QUERIES)

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.QUERY, names.defId)
        val rQuery = R_QueryDefinition(names, defCtx.initFrameGetter, mountName)
        val cQuery = C_QueryGlobalFunction(rQuery)

        ctx.appCtx.defsAdder.addQuery(rQuery)
        ctx.nsBuilder.addQuery(name, cQuery)
        ctx.mntBuilder.addQuery(name, rQuery)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cQuery)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(ctx, header, rQuery)
            }
        }
    }

    private fun compileHeader(defCtx: C_DefinitionContext, cQuery: C_QueryGlobalFunction): C_QueryFunctionHeader {
        val header = C_FunctionUtils.compileQueryHeader(defCtx, name, cQuery.rQuery.names, params, retType, body)
        cQuery.setHeader(header)
        return header
    }

    private fun compileBody(ctx: C_MountContext, header: C_QueryFunctionHeader, rQuery: R_QueryDefinition) {
        if (header.queryBody == null) return

        val rBody = header.queryBody.compile()

        if (ctx.globalCtx.compilerOptions.gtv) {
            ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                checkGtvResult(ctx.msgCtx, rBody.retType)
            }
        }

        rQuery.setBody(rBody)
    }

    private fun checkGtvResult(msgCtx: C_MessageContext, rType: R_Type) {
        C_Utils.checkGtvCompatibility(msgCtx, name.pos, rType, false, "result_nogtv:${name.str}",
                "Return type of query '${name.str}'")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.QUERY)
    }
}
