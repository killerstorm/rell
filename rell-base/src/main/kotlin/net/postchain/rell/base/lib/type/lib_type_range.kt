/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_SysFunction
import net.postchain.rell.base.model.R_IntegerType
import net.postchain.rell.base.model.R_RangeType
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_RangeValue
import net.postchain.rell.base.runtime.Rt_Value

object C_Lib_Type_Range: C_Lib_Type("range", R_RangeType, defaultMemberFns = false) {
    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, type, listOf(R_IntegerType), RangeFns.Range_1)
        b.add(typeName.str, type, listOf(R_IntegerType, R_IntegerType), RangeFns.Range_2)
        b.add(typeName.str, type, listOf(R_IntegerType, R_IntegerType, R_IntegerType), RangeFns.Range_3)
    }
}

private object RangeFns {
    private fun calcRange(start: Long, end: Long, step: Long): Rt_Value {
        if (step == 0L || (step > 0 && start > end) || (step < 0 && start < end)) {
            throw Rt_Exception.common("fn_range_args:$start:$end:$step",
                "Invalid range: start = $start, end = $end, step = $step")
        }
        return Rt_RangeValue(start, end, step)
    }

    val Range_1 = C_SysFunction.simple1(pure = true) { a ->
        calcRange(0, a.asInteger(), 1)
    }

    val Range_2 = C_SysFunction.simple2(pure = true) { a, b ->
        calcRange(a.asInteger(), b.asInteger(), 1)
    }

    val Range_3 = C_SysFunction.simple3(pure = true) { a, b, c ->
        calcRange(a.asInteger(), b.asInteger(), c.asInteger())
    }
}
