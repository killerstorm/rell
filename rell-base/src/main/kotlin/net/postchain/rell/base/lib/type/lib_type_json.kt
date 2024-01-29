/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_JsonType
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_JsonValue
import net.postchain.rell.base.runtime.Rt_TextValue

object Lib_Type_Json {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("json", rType = R_JsonType) {
            constructor(pure = true) {
                param("value", type = "text")
                dbFunctionCast("json", "JSONB")
                body { a ->
                    val s = a.asString()
                    val r = try {
                        Rt_JsonValue.parse(s)
                    } catch (e: IllegalArgumentException) {
                        throw Rt_Exception.common("fn_json_badstr", "Bad JSON: $s")
                    }
                    r
                }
            }

            function("to_text", result = "text", pure = true) {
                alias("str")
                dbFunctionCast("json.to_text", "TEXT")
                body { a ->
                    val s = a.asJsonString()
                    Rt_TextValue.get(s)
                }
            }
        }
    }
}
