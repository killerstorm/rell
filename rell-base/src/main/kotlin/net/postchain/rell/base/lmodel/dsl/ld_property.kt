/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionCtx
import net.postchain.rell.base.lmodel.L_NamespaceProperty
import net.postchain.rell.base.lmodel.L_TypeProperty
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.runtime.Rt_CallContext
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.utils.ide.IdeSymbolInfo

class Ld_NamespaceProperty(
    private val type: Ld_Type,
    private val ideInfo: IdeSymbolInfo,
    private val fn: C_SysFunction,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_NamespaceProperty {
        val mType = type.finish(ctx)
        return L_NamespaceProperty(mType, ideInfo, fn)
    }
}

class Ld_TypeProperty(
    val simpleName: R_Name,
    private val type: Ld_Type,
    private val fn: C_SysFunction,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_TypeProperty {
        val mType = type.finish(ctx)
        return L_TypeProperty(simpleName, mType, fn)
    }
}

@RellLibDsl
interface Ld_NamespacePropertyDsl {
    fun validate(validator: (C_SysFunctionCtx) -> Unit)
    fun bodyContext(block: (Rt_CallContext) -> Rt_Value): Ld_PropertyBody
}

class Ld_NamespacePropertyDslBuilder(
    private val type: Ld_Type,
    pure: Boolean,
    private val ideInfo: IdeSymbolInfo,
): Ld_NamespacePropertyDsl {
    private val bodyBuilder = Ld_InternalFunctionBodyBuilder(Ld_InternalFunctionBody(
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

        val body = bodyBuilder.build()
        val fn = body.bodyContextN { ctx, args ->
            Rt_Utils.checkEquals(args.size, 0)
            block(ctx)
        }

        val res = Ld_PropertyBodyImpl(fn)
        buildRes = res
        return res
    }

    fun build(block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody): Ld_NamespaceProperty {
        val bodyTag = block(this)
        check(bodyTag === buildRes)

        return Ld_NamespaceProperty(
            type = type,
            ideInfo = ideInfo,
            fn = buildRes!!.fn,
        )
    }

    private class Ld_PropertyBodyImpl(val fn: C_SysFunction): Ld_PropertyBody()
}

sealed class Ld_PropertyBody
