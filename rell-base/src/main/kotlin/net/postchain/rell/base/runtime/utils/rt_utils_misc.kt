/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.runtime.utils

import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.ThreadLocalContext
import java.util.*

class Rt_ValueRecursionDetector {
    private val threadLocalCtx = ThreadLocalContext<ValueSet>()

    fun <T> calculate(v: Rt_Value, code: () -> T): T? {
        val vs = threadLocalCtx.getOpt()
        if (vs != null) {
            if (!vs.set.add(v)) {
                return null
            }
            try {
                return code()
            } finally {
                vs.set.remove(v)
            }
        } else {
            val vs2 = ValueSet()
            vs2.set.add(v)
            return threadLocalCtx.set(vs2, code)
        }
    }

    private class ValueSet {
        val set = Collections.newSetFromMap(IdentityHashMap<Rt_Value, Boolean>())
    }
}
