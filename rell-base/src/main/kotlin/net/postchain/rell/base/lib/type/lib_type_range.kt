/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.L_ParamArity
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_RangeType
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_RangeValue
import net.postchain.rell.base.runtime.Rt_Value

object Lib_Type_Range {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("range", rType = R_RangeType) {
            parent(type = "iterable<integer>")

            constructor(pure = true) {
                param("integer")
                body { a ->
                    calcRange(0, a.asInteger(), 1)
                }
            }

            constructor(pure = true) {
                param("integer")
                param("integer")
                param("integer", arity = L_ParamArity.ZERO_ONE)
                bodyOpt2 { a, b, c ->
                    calcRange(a.asInteger(), b.asInteger(), c?.asInteger() ?: 1)
                }
            }
        }
    }

    private fun calcRange(start: Long, end: Long, step: Long): Rt_Value {
        if (step == 0L || (step > 0 && start > end) || (step < 0 && start < end)) {
            throw Rt_Exception.common("fn_range_args:$start:$end:$step",
                "Invalid range: start = $start, end = $end, step = $step")
        }
        return Rt_RangeValue(start, end, step)
    }
}
