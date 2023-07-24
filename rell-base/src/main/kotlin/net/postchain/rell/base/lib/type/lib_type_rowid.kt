/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_RowidType
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.runtime.Rt_RowidValue
import net.postchain.rell.base.runtime.utils.Rt_Utils

object Lib_Type_Rowid {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("rowid", rType = R_RowidType) {
            constructor(pure = true) {
                param("integer")
                body { a ->
                    val v = a.asInteger()
                    Rt_Utils.check(v >= 0) { "rowid(integer):negative:$v" toCodeMsg "Negative value: $v" }
                    Rt_RowidValue(v)
                }
            }

            function("to_integer", "integer", pure = true) {
                dbFunctionTemplate("rowid.to_integer", 1, "#0")
                body { a ->
                    val v = a.asRowid()
                    Rt_IntValue(v)
                }
            }
        }
    }
}
