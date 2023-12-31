/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SpecialLibMemberFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.lmodel.L_TypeDefRTypeFactory
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_Type_Composite
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.doc.DocCode

@RellLibDsl
interface Ld_CommonTypeDsl {
    fun generic(name: String, subOf: String? = null, superOf: String? = null)

    fun property(name: String, type: String, pure: Boolean = false, getter: (Rt_Value) -> Rt_Value)
    fun property(name: String, type: String, body: C_SysFunctionBody)

    fun function(
        name: String,
        result: String? = null,
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_SpecialLibMemberFunctionBody)

    fun staticFunction(
        name: String,
        result: String? = null,
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun staticFunction(name: String, fn: C_SpecialLibGlobalFunctionBody)
}

@RellLibDsl
interface Ld_TypeDefDsl: Ld_CommonTypeDsl, Ld_CommonNamespaceDsl {
    val typeSimpleName: String

    fun parent(type: String)

    fun rType(rType: R_Type)
    fun rType(factory: (R_Type) -> R_Type?)
    fun rType(factory: (R_Type, R_Type) -> R_Type?)
    fun rType(factory: (R_Type, R_Type, R_Type) -> R_Type?)
    fun rTypeFactory(factory: L_TypeDefRTypeFactory)

    fun docCode(calculator: (DocCode) -> DocCode)
    fun docCode(calculator: (DocCode, DocCode) -> DocCode)
    fun docCode(calculator: (DocCode, DocCode, DocCode) -> DocCode)

    fun supertypeStrategySpecial(predicate: (M_Type) -> Boolean)
    fun supertypeStrategyComposite(predicate: (M_Type_Composite) -> Boolean)

    fun constructor(
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef,
    )

    fun constructor(fn: C_SpecialLibGlobalFunctionBody)
}

@RellLibDsl
interface Ld_TypeExtensionDsl: Ld_CommonTypeDsl

@RellLibDsl
interface Ld_ConstructorDsl: Ld_CommonFunctionDsl
