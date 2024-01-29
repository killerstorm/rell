/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.fn.C_EffectivePartialArguments
import net.postchain.rell.base.compiler.base.fn.C_FullCallArguments
import net.postchain.rell.base.compiler.base.fn.C_PartialCallTarget
import net.postchain.rell.base.compiler.base.fn.C_PartialCallTargetMatch
import net.postchain.rell.base.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceElement
import net.postchain.rell.base.compiler.base.utils.C_MessageManager
import net.postchain.rell.base.compiler.vexpr.V_FunctionCall
import net.postchain.rell.base.model.R_FunctionType
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_Type

class C_DeprecatedLibFuncCase<CallT: V_FunctionCall>(
    private val case: C_LibFuncCase<CallT>,
    private val deprecated: C_Deprecated,
): C_LibFuncCase<CallT>(case.ideInfo) {
    override val argIdeInfos: Map<R_Name, C_IdeSymbolInfo>
        get() = case.argIdeInfos

    override fun getSpecificName(selfType: R_Type) = case.getSpecificName(selfType)
    override fun getCallTypeHints(selfType: R_Type) = case.getCallTypeHints(selfType)

    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibFuncCase<CallT> {
        val case2 = case.replaceTypeParams(rep)
        return if (case2 === case) this else C_DeprecatedLibFuncCase(case2, deprecated)
    }

    override fun match(
        msgMgr: C_MessageManager,
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: C_FullCallArguments,
        resTypeHint: C_TypeHint,
    ): C_LibFuncCaseMatch<CallT>? {
        val match = case.match(msgMgr, caseCtx, selfType, args, resTypeHint)
        return if (match == null) null else C_DeprecatedLibFuncCaseMatch(match, deprecated)
    }

    override fun getPartialCallTarget(
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
    ): C_PartialCallTarget<CallT>? {
        val target = case.getPartialCallTarget(caseCtx, selfType)
        return if (target == null) null else C_PartialCallTarget_Deprecated(deprecated, target)
    }
}

private class C_DeprecatedLibFuncCaseMatch<CallT: V_FunctionCall>(
    private val match: C_LibFuncCaseMatch<CallT>,
    private val deprecated: C_Deprecated,
): C_LibFuncCaseMatch<CallT>() {
    override fun compileCall(ctx: C_ExprContext, caseCtx: C_LibFuncCaseCtx): CallT {
        C_NamespaceElement.deprecatedMessage(
                ctx.msgCtx,
                caseCtx.linkPos,
                caseCtx.qualifiedNameMsg(),
                C_DeclarationType.FUNCTION,
                deprecated
        )
        return match.compileCall(ctx, caseCtx)
    }
}

private class C_PartialCallTarget_Deprecated<CallT: V_FunctionCall>(
    private val deprecated: C_Deprecated,
    private val target: C_PartialCallTarget<CallT>,
): C_PartialCallTarget<CallT>(target.callPos, target.fullName) {
    override fun codeMsg() = target.codeMsg()

    override fun match(): C_PartialCallTargetMatch<CallT> {
        val match = target.match()
        return C_PartialCallTargetMatch_Deprecated(match)
    }

    override fun match(fnType: R_FunctionType): C_PartialCallTargetMatch<CallT>? {
        val match = target.match(fnType)
        return match?.let { C_PartialCallTargetMatch_Deprecated(it) }
    }

    private inner class C_PartialCallTargetMatch_Deprecated(
        private val match: C_PartialCallTargetMatch<CallT>,
    ): C_PartialCallTargetMatch<CallT>(match.exact) {
        override fun paramTypes() = match.paramTypes()

        override fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): CallT {
            C_NamespaceElement.deprecatedMessage(
                ctx.msgCtx,
                callPos,
                fullName.value,
                C_DeclarationType.FUNCTION,
                deprecated,
            )
            return match.compileCall(ctx, args)
        }
    }
}
