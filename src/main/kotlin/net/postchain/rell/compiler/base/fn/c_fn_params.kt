/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.fn

import net.postchain.rell.compiler.ast.S_FormalParameter
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.C_AttrUtils
import net.postchain.rell.compiler.base.expr.C_StmtContext
import net.postchain.rell.compiler.base.expr.C_VarFact
import net.postchain.rell.compiler.base.expr.C_VarFacts
import net.postchain.rell.compiler.base.utils.C_ParameterDefaultValue
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class C_FormalParameter(
        val name: C_Name,
        val type: R_Type,
        val ideInfo: IdeSymbolInfo,
        private val index: Int,
        private val defaultValue: C_ParameterDefaultValue?
) {
    fun toCallParameter() = C_FunctionCallParameter(name.rName, type, index, defaultValue)

    fun createVarParam(ptr: R_VarPtr): R_VarParam {
        return R_VarParam(name.str, type, ptr)
    }

    fun createMirrorAttr(mutable: Boolean): R_Attribute {
        val keyIndexKind: R_KeyIndexKind? = null
        val ideInfo = C_AttrUtils.getIdeSymbolInfo(false, mutable, keyIndexKind)

        return R_Attribute(
                index,
                name.rName,
                type,
                mutable = mutable,
                keyIndexKind = keyIndexKind,
                ideInfo = ideInfo,
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
                val cVarRef = blkCtx.addLocalVar(name, param.type, false, null, param.ideInfo)
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
