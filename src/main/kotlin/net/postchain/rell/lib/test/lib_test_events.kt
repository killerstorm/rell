/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.test

import net.postchain.rell.compiler.base.fn.C_ArgsTypesMatcher_VarArg
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_CallContext
import net.postchain.rell.runtime.Rt_ListValue
import net.postchain.rell.runtime.Rt_UnitValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.immListOf

private val EVENT_LIST_TYPE: R_Type = R_ListType(C_Lib_Test_Events.EVENT_TYPE)

object C_Lib_Test_Events {
    val EVENT_TUPLE_TYPE = R_TupleType.create(R_TextType, R_GtvType)
    val EVENT_TYPE: R_Type = EVENT_TUPLE_TYPE

    private val GLOBAL_FUNCTIONS = C_GlobalFuncBuilder(C_Lib_Test.NAMESPACE_DEF_PATH)
        .addEx("assert_events", R_UnitType, C_ArgsTypesMatcher_VarArg.make(EVENT_TYPE), R_SysFn_AssertEvents)
        .build()

    private val RELLTEST_FUNCTIONS = C_GlobalFuncBuilder(C_Lib_Test.NAMESPACE_DEF_PATH)
        .add("get_events", EVENT_LIST_TYPE, immListOf(), R_SysFn_GetEvents)
        .build()

    fun bindGlobal(b: C_SysNsProtoBuilder) {
        C_LibUtils.bindFunctions(b, GLOBAL_FUNCTIONS)
    }

    fun bindRellTest(b: C_SysNsProtoBuilder) {
        C_LibUtils.bindFunctions(b, GLOBAL_FUNCTIONS)
        C_LibUtils.bindFunctions(b, RELLTEST_FUNCTIONS)
    }
}

private object R_SysFn_GetEvents: R_SysFunctionEx_0() {
    override fun call(ctx: Rt_CallContext): Rt_Value {
        val events = ctx.exeCtx.emittedEvents
        return Rt_ListValue(EVENT_LIST_TYPE, events.toMutableList())
    }
}

private object R_SysFn_AssertEvents: R_SysFunction {
    override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        val events = ctx.exeCtx.emittedEvents
        val actual: Rt_Value = Rt_ListValue(EVENT_LIST_TYPE, events.toMutableList())
        val expected: Rt_Value = Rt_ListValue(EVENT_LIST_TYPE, args.toMutableList())
        C_Lib_Test_Assert.assertEquals("assert_events", expected, actual)
        return Rt_UnitValue
    }
}
