/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lmodel.L_Function
import net.postchain.rell.base.lmodel.L_FunctionHeader
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_FunctionHeader
import net.postchain.rell.base.utils.toImmList

interface Ld_FunctionMaker: Ld_CommonFunctionMaker {
    fun alias(name: String, deprecated: C_MessageType? = null)
    fun result(type: String)
}

class Ld_FunctionDsl(
    private val funMaker: Ld_FunctionMaker,
    bodyMaker: Ld_FunctionBodyDsl,
): Ld_CommonFunctionDsl(funMaker, bodyMaker) {
    fun result(type: String) {
        funMaker.result(type)
    }

    fun alias(name: String, deprecated: C_MessageType? = null) {
        funMaker.alias(name, deprecated)
    }
}

class Ld_FunctionBuilder(
    private val simpleName: R_Name,
    outerTypeParams: Set<R_Name>,
    bodyBuilder: Ld_FunctionBodyBuilder,
): Ld_CommonFunctionBuilder(outerTypeParams, bodyBuilder), Ld_FunctionMaker {
    private val aliasesBuilder = Ld_AliasesBuilder(simpleName)
    private var resultType: Ld_Type? = null

    override fun alias(name: String, deprecated: C_MessageType?) {
        aliasesBuilder.alias(name, deprecated)
    }

    override fun result(type: String) {
        Ld_Exception.check(resultType == null) {
            "function:result_already_defined:$type" to "Result type already set"
        }
        resultType = Ld_Type.parse(type)
    }

    fun build(bodyRes: Ld_FunctionBodyRef): Ld_Function {
        val cf = commonBuild(bodyRes)

        val header = Ld_FunctionHeader(
            typeParams = cf.header.typeParams,
            resultType = requireNotNull(resultType) { "Result type not set" },
            params = cf.header.params,
        )

        return Ld_Function(
            aliases = aliasesBuilder.build(),
            header = header,
            deprecated = cf.deprecated,
            body = cf.body,
        )
    }

    companion object {
        fun build(
            simpleName: R_Name,
            result: String?,
            params: List<String>?,
            pure: Boolean?,
            outerTypeParams: Set<R_Name>,
            block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
        ): Ld_Function {
            val bodyBuilder = Ld_FunctionBodyBuilder(simpleName, pure)
            val funBuilder = Ld_FunctionBuilder(simpleName, outerTypeParams, bodyBuilder)
            val bodyDslBuilder = Ld_FunctionBodyDslBuilder(bodyBuilder)
            val dsl = Ld_FunctionDsl(funBuilder, bodyDslBuilder)

            if (result != null) {
                dsl.result(result)
            }

            if (params != null) {
                for (param in params) {
                    dsl.param(param)
                }
                funBuilder.paramsDefined()
            }

            val bodyRes = block(dsl)
            return funBuilder.build(bodyRes)
        }
    }
}

class Ld_FunctionHeader(
    private val typeParams: List<Ld_TypeParam>,
    private val resultType: Ld_Type,
    private val params: List<Ld_FunctionParam>,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_FunctionHeader {
        val (mTypeParams, mTypeParamMap) = Ld_TypeParam.finishList(ctx, typeParams)
        val subCtx = ctx.subCtx(typeParams = mTypeParamMap)

        val mResultType = resultType.finish(subCtx)
        val lParams = params.map { it.finish(subCtx) }.toImmList()
        val mParams = lParams.map { it.mParam }
        val mHeader = M_FunctionHeader(mTypeParams, mResultType, mParams)

        return L_FunctionHeader(mHeader, lParams)
    }
}

class Ld_Function(
    val aliases: List<Ld_Alias>,
    val deprecated: C_Deprecated?,
    private val header: Ld_FunctionHeader,
    private val body: Ld_FunctionBody,
) {
    fun finish(ctx: Ld_TypeFinishContext, qualifiedName: R_QualifiedName): L_Function {
        val lHeader = header.finish(ctx)
        val lBody = body.finish(qualifiedName)
        return L_Function(qualifiedName, header = lHeader, body = lBody)
    }
}
