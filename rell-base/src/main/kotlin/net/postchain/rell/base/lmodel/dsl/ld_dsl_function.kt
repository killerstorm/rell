/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionCtx
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lmodel.L_FunctionBodyMeta
import net.postchain.rell.base.lmodel.L_ParamArity
import net.postchain.rell.base.lmodel.L_ParamImplication
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.Rt_CallContext
import net.postchain.rell.base.runtime.Rt_Value

@RellLibDsl
interface Ld_FunctionContextDsl {
    val fnSimpleName: String
}

@RellLibDsl
interface Ld_CommonFunctionDsl: Ld_FunctionContextDsl, Ld_FunctionBodyDsl {
    fun deprecated(newName: String, error: Boolean = true)
    fun generic(name: String, subOf: String? = null, superOf: String? = null)

    fun param(
        name: String,
        type: String,
        arity: L_ParamArity = L_ParamArity.ONE,
        exact: Boolean = false,
        nullable: Boolean = false,
        lazy: Boolean = false,
        implies: L_ParamImplication? = null,
    )
}

@RellLibDsl
interface Ld_FunctionDsl: Ld_CommonFunctionDsl {
    fun result(type: String)
    fun alias(name: String, deprecated: C_MessageType? = null)
}

sealed class Ld_FunctionBodyRef

@RellLibDsl
interface Ld_CommonFunctionBodyDsl: Ld_FunctionContextDsl {
    fun dbFunction(dbFn: Db_SysFunction)
    fun dbFunctionSimple(name: String, sql: String)
    fun dbFunctionTemplate(name: String, arity: Int, template: String)
    fun dbFunctionCast(name: String, type: String)

    fun bodyN(rCode: (List<Rt_Value>) -> Rt_Value): Ld_FunctionBodyRef
    fun bodyContextN(rCode: (Rt_CallContext, List<Rt_Value>) -> Rt_Value): Ld_FunctionBodyRef

    fun body(rCode: () -> Rt_Value): Ld_FunctionBodyRef
    fun body(rCode: (Rt_Value) -> Rt_Value): Ld_FunctionBodyRef
    fun body(rCode: (Rt_Value, Rt_Value) -> Rt_Value): Ld_FunctionBodyRef
    fun body(rCode: (Rt_Value, Rt_Value, Rt_Value) -> Rt_Value): Ld_FunctionBodyRef
    fun body(rCode: (Rt_Value, Rt_Value, Rt_Value, Rt_Value) -> Rt_Value): Ld_FunctionBodyRef

    fun bodyOpt1(rCode: (Rt_Value, Rt_Value?) -> Rt_Value): Ld_FunctionBodyRef
    fun bodyOpt2(rCode: (Rt_Value, Rt_Value, Rt_Value?) -> Rt_Value): Ld_FunctionBodyRef

    fun bodyContext(rCode: (Rt_CallContext) -> Rt_Value): Ld_FunctionBodyRef
    fun bodyContext(rCode: (Rt_CallContext, Rt_Value) -> Rt_Value): Ld_FunctionBodyRef
    fun bodyContext(rCode: (Rt_CallContext, Rt_Value, Rt_Value) -> Rt_Value): Ld_FunctionBodyRef
    fun bodyContext(rCode: (Rt_CallContext, Rt_Value, Rt_Value, Rt_Value) -> Rt_Value): Ld_FunctionBodyRef
}

@RellLibDsl
interface Ld_FunctionBodyDsl: Ld_CommonFunctionBodyDsl {
    fun validate(validator: (C_SysFunctionCtx) -> Unit)
    fun bodyRaw(body: C_SysFunctionBody): Ld_FunctionBodyRef
    fun bodyMeta(block: Ld_FunctionMetaBodyDsl.() -> Ld_FunctionBodyRef): Ld_FunctionBodyRef
}

@RellLibDsl
interface Ld_FunctionMetaBodyDsl: Ld_CommonFunctionBodyDsl {
    val fnQualifiedName: String
    val fnBodyMeta: L_FunctionBodyMeta

    fun validationError(code: String, msg: String)
}
