/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.ast.S_VirtualType
import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl

object Lib_Type_Virtual {
    val ToFull = C_SysFunction.simple { a ->
        val virtual = a.asVirtual()
        val full = virtual.toFull()
        full
    }

    val NAMESPACE = Ld_NamespaceDsl.make {
        type("virtual", hidden = true) {
            generic("T")

            rType { t ->
                S_VirtualType.virtualType(t)
            }

            function("to_full", result = "T") {
                bodyFunction(ToFull)
            }
        }
    }
}
