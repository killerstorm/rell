/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionCtx
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_CallContext
import net.postchain.rell.base.runtime.Rt_Value
import java.math.BigDecimal
import java.math.BigInteger

@RellLibDsl
interface Ld_CommonNamespaceDsl {
    fun constant(name: String, value: Long)
    fun constant(name: String, value: BigInteger)
    fun constant(name: String, value: BigDecimal)
    fun constant(name: String, type: String, value: Rt_Value)
    fun constant(name: String, type: String, getter: (R_Type) -> Rt_Value)
}

@RellLibDsl
interface Ld_NamespaceDsl: Ld_CommonNamespaceDsl {
    fun include(namespace: Ld_Namespace)

    fun alias(name: String? = null, target: String, deprecated: C_Deprecated? = null)
    fun alias(name: String? = null, target: String, deprecated: C_MessageType)

    fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit)

    fun type(
        name: String,
        abstract: Boolean = false,
        hidden: Boolean = false,
        rType: R_Type? = null,
        block: Ld_TypeDefDsl.() -> Unit = {},
    )

    fun extension(name: String, type: String, block: Ld_TypeExtensionDsl.() -> Unit)

    fun struct(name: String, block: Ld_StructDsl.() -> Unit)

    fun property(
        name: String,
        type: String,
        pure: Boolean = false,
        block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody,
    )

    fun property(name: String, property: C_NamespaceProperty)

    fun function(
        name: String,
        result: String? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_SpecialLibGlobalFunctionBody)

    companion object {
        fun make(block: Ld_NamespaceDsl.() -> Unit): Ld_Namespace {
            val builder = Ld_NamespaceBuilder()
            val dsl = Ld_NamespaceDslImpl(builder)
            block(dsl)
            return builder.build()
        }
    }
}

@RellLibDsl
interface Ld_NamespacePropertyDsl {
    fun validate(validator: (C_SysFunctionCtx) -> Unit)
    fun bodyContext(block: (Rt_CallContext) -> Rt_Value): Ld_PropertyBody
}

@RellLibDsl
interface Ld_StructDsl {
    fun attribute(name: String, type: String, mutable: Boolean = false)
}
