/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.runtime.Rt_ByteArrayValue
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.utils.PostchainGtvUtils

object C_Lib_Type_Virtual {
    val ToFull = C_SysFunction.simple1 { a ->
        val virtual = a.asVirtual()
        val full = virtual.toFull()
        full
    }

    val Hash = C_SysFunction.simple1(pure = true) { a ->
        val virtual = a.asVirtual()
        val gtv = virtual.gtv
        val hash = Rt_Utils.wrapErr("fn:virtual:hash") {
            PostchainGtvUtils.merkleHash(gtv)
        }
        Rt_ByteArrayValue(hash)
    }
}
