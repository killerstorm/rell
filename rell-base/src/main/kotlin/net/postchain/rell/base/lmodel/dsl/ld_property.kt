/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionCtx
import net.postchain.rell.base.lmodel.L_NamespaceProperty
import net.postchain.rell.base.lmodel.L_TypeProperty
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.runtime.Rt_CallContext
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.runtime.utils.Rt_Utils

class Ld_NamespaceProperty(
    private val type: Ld_Type,
    private val fn: C_SysFunction,
    private val pure: Boolean,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_NamespaceProperty {
        val mType = type.finish(ctx)
        return L_NamespaceProperty(mType, fn, pure)
    }
}

class Ld_TypeProperty(
    val simpleName: R_Name,
    private val type: Ld_Type,
    private val body: C_SysFunctionBody,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_TypeProperty {
        val mType = type.finish(ctx)
        return L_TypeProperty(simpleName, mType, body)
    }
}

class Ld_NamespacePropertyDslImpl(
    private val type: Ld_Type,
    pure: Boolean,
): Ld_NamespacePropertyDsl {
    private val bodyBuilder = Ld_InternalFunctionBodyBuilder(Ld_InternalFunctionBodyState(
        pure = pure,
        validator = null,
        dbFunction = null,
    ))

    private var buildRes: Ld_PropertyBodyImpl? = null

    override fun validate(validator: (C_SysFunctionCtx) -> Unit) {
        check(buildRes == null) { "Body already set" }
        bodyBuilder.validator(validator)
    }

    override fun bodyContext(block: (Rt_CallContext) -> Rt_Value): Ld_PropertyBody {
        check(buildRes == null) { "Body already set" }

        val internalState = bodyBuilder.build()
        val internalBody = internalState.bodyContextN { ctx, args ->
            Rt_Utils.checkEquals(args.size, 0)
            block(ctx)
        }

        val res = Ld_PropertyBodyImpl(internalBody)
        buildRes = res
        return res
    }

    fun build(block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody): Ld_NamespaceProperty {
        val bodyTag = block(this)
        check(bodyTag === buildRes)

        val res = buildRes!!
        return Ld_NamespaceProperty(
            type = type,
            fn = res.body.fn,
            pure = res.body.pure,
        )
    }

    private class Ld_PropertyBodyImpl(val body: Ld_InternalFunctionBody): Ld_PropertyBody()
}

sealed class Ld_PropertyBody
