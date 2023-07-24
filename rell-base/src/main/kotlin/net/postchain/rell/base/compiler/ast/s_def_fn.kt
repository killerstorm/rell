/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.ast

import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.def.C_GlobalAttrHeaderIdeData
import net.postchain.rell.base.compiler.base.expr.C_ExprHint
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.expr.C_StmtContext
import net.postchain.rell.base.compiler.base.fn.C_FormalParameter
import net.postchain.rell.base.compiler.base.utils.*
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.stmt.R_ExprStatement
import net.postchain.rell.base.model.stmt.R_ReturnStatement
import net.postchain.rell.base.utils.MutableTypedKeyMap
import net.postchain.rell.base.utils.TypedKeyMap
import net.postchain.rell.base.utils.ide.IdeSymbolCategory
import net.postchain.rell.base.utils.ide.IdeSymbolKind

class S_FormalParameter(private val attr: S_AttrHeader, private val expr: S_Expr?) {
    fun compile(defCtx: C_DefinitionContext, index: Int): C_FormalParameter {
        val ideData = C_GlobalAttrHeaderIdeData(IdeSymbolCategory.PARAMETER, IdeSymbolKind.LOC_PARAMETER, null)
        val attrHeader = attr.compile(defCtx, false, ideData)

        val name = attrHeader.name
        val type = attrHeader.type ?: R_CtErrorType

        val defaultValue = if (expr == null) null else {
            val rErrorExpr = C_ExprUtils.errorRExpr(type)
            val rExprLate = C_LateInit(C_CompilerPass.EXPRESSIONS, rErrorExpr)
            val rValueLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_DefaultValue(rErrorExpr, false))

            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                val vExpr = compileExpr(defCtx, name.rName, type)
                val rExpr = vExpr.toRExpr()
                rExprLate.set(rExpr)
                rValueLate.set(R_DefaultValue(rExpr, vExpr.info.hasDbModifications))
            }

            C_ParameterDefaultValue(expr.startPos, name.rName, rExprLate.getter, defCtx.initFrameGetter, rValueLate.getter)
        }

        return C_FormalParameter(name, type, attrHeader.ideInfo, index, defaultValue)
    }

    private fun compileExpr(defCtx: C_DefinitionContext, paramName: R_Name, paramType: R_Type): V_Expr {
        val exprCtx = defCtx.initExprCtx
        val cExpr = expr!!.compileOpt(exprCtx, C_ExprHint.ofType(paramType))
        cExpr ?: return C_ExprUtils.errorVExpr(exprCtx, expr.startPos, paramType)

        val vExpr = cExpr.value()

        return if (paramType.isError()) vExpr else {
            val valueType = vExpr.type
            val adapter = paramType.getTypeAdapter(valueType)
            if (adapter != null) adapter.adaptExpr(exprCtx, vExpr) else {
                val code = "def:param:type:$paramName:${paramType.strCode()}:${valueType.strCode()}"
                val msg = "Wrong type of default value of parameter '$paramName': ${valueType.str()} instead of ${paramType.str()}"
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
        val fnCtx = C_FunctionContext(ctx.defCtx, ctx.defName.appLevelName, ctx.explicitRetType, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)
        val actParams = ctx.forParams.compile(frameCtx)

        val cBody = compileQuery0(ctx, actParams.stmtCtx)
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)
        val rRetType = fnCtx.actualReturnType()
        return R_UserQueryBody(rRetType, actParams.rParams, cBody.rStmt, callFrame.rFrame)
    }

    fun compileFunction(ctx: C_FunctionBodyContext): R_FunctionBody {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(ctx.defCtx, ctx.defName.appLevelName, ctx.explicitRetType, statementVars)
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
        val cExpr = expr.compile(stmtCtx, C_ExprHint.ofType(bodyCtx.explicitRetType))
        val vExpr = cExpr.value()

        val type = vExpr.type
        C_Utils.checkUnitType(bodyCtx.namePos, type) { "query_exprtype_unit" toCodeMsg "Query expressions returns nothing" }

        val adapter = stmtCtx.fnCtx.matchReturnType(bodyCtx.namePos, type)
        val vExpr2 = adapter.adaptExpr(stmtCtx.exprCtx, vExpr)

        val rExpr = vExpr2.toRExpr()
        return C_Statement(R_ReturnStatement(rExpr), true)
    }

    override fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val vExpr = expr.compile(stmtCtx, C_ExprHint.ofType(bodyCtx.explicitRetType)).value()
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
            val nameStr = bodyCtx.defName.qualifiedName
            "query_noreturn:$nameStr" toCodeMsg "Query '$nameStr': not all code paths return value"
        }

        return cBody
    }

    override fun compileFunction0(bodyCtx: C_FunctionBodyContext, stmtCtx: C_StmtContext): C_Statement {
        val cBody = body.compile(stmtCtx)

        val retType = stmtCtx.fnCtx.actualReturnType()
        if (retType != R_UnitType) {
            C_Errors.check(cBody.returnAlways, bodyCtx.namePos) {
                val nameStr = bodyCtx.defName.qualifiedName
                "fun_noreturn:$nameStr" toCodeMsg "Function '$nameStr': not all code paths return value"
            }
        }

        return cBody
    }
}
