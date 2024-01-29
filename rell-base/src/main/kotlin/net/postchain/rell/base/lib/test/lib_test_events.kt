/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.test

import net.postchain.rell.base.lmodel.L_ParamArity
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.*
import net.postchain.rell.base.runtime.Rt_ListValue
import net.postchain.rell.base.runtime.Rt_Value

private val EVENT_LIST_TYPE: R_Type = R_ListType(Lib_Test_Events.EVENT_TYPE)

object Lib_Test_Events {
    val EVENT_TUPLE_TYPE = R_TupleType.create(R_TextType, R_GtvType)
    val EVENT_TYPE: R_Type = EVENT_TUPLE_TYPE
    private const val EVENT_TYPE_STR = "(text,gtv)"

    val NAMESPACE = Ld_NamespaceDsl.make {
        alias(target = "rell.test.assert_events")

        namespace("rell.test") {
            function("assert_events", "unit") {
                param("expected", EVENT_TYPE_STR, arity = L_ParamArity.ZERO_MANY)
                bodyContextN { ctx, args ->
                    val events = ctx.exeCtx.emittedEvents
                    val actual: Rt_Value = Rt_ListValue(EVENT_LIST_TYPE, events.toMutableList())
                    val expected: Rt_Value = Rt_ListValue(EVENT_LIST_TYPE, args.toMutableList())
                    Lib_Test_Assert.calcAssertEquals("assert_events", expected, actual)
                }
            }

            function("get_events", "list<$EVENT_TYPE_STR>") {
                bodyContext { ctx ->
                    val events = ctx.exeCtx.emittedEvents
                    Rt_ListValue(EVENT_LIST_TYPE, events.toMutableList())
                }
            }
        }
    }
}
