/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.lmodel.L_Constructor
import net.postchain.rell.base.lmodel.L_ConstructorHeader
import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.utils.toImmList

interface Ld_ConstructorMaker: Ld_CommonFunctionMaker

class Ld_ConstructorBuilder(
    outerTypeParams: Set<R_Name>,
    bodyBuilder: Ld_FunctionBodyBuilder,
): Ld_CommonFunctionBuilder(outerTypeParams, bodyBuilder), Ld_ConstructorMaker {
    fun build(bodyRes: Ld_FunctionBodyRef): Ld_Constructor {
        val cf = commonBuild(bodyRes)

        val header = Ld_ConstructorHeader(
            typeParams = cf.header.typeParams,
            params = cf.header.params,
        )

        return Ld_Constructor(header, cf.deprecated, cf.body)
    }
}

class Ld_ConstructorDslImpl(
    conMaker: Ld_ConstructorMaker,
    bodyMaker: Ld_FunctionBodyDsl,
): Ld_CommonFunctionDslImpl(conMaker, bodyMaker), Ld_ConstructorDsl

class Ld_ConstructorHeader(
    private val typeParams: List<Ld_TypeParam>,
    private val params: List<Ld_FunctionParam>,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_ConstructorHeader {
        val lTypeParams = Ld_TypeParam.finishList(ctx, typeParams)
        val subCtx = ctx.subCtx(lTypeParams.map)
        val lParams = params.map { it.finish(subCtx) }.toImmList()

        return L_ConstructorHeader(
            typeParams = lTypeParams.list,
            params = lParams,
        )
    }
}

class Ld_Constructor(
    private val header: Ld_ConstructorHeader,
    private val deprecated: C_Deprecated?,
    private val body: Ld_FunctionBody,
) {
    fun finish(ctx: Ld_TypeFinishContext, fullName: R_FullName): L_Constructor {
        val lHeader = header.finish(ctx)
        val lBody = body.finish(fullName.qualifiedName)
        return L_Constructor(header = lHeader, deprecated = deprecated, body = lBody, pure = body.pure)
    }
}
