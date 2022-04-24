/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.R_JsonType
import net.postchain.rell.model.R_TextType
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_JsonValue
import net.postchain.rell.runtime.Rt_TextValue

object C_Lib_Type_Json: C_Lib_Type("json", R_JsonType) {
    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, type, listOf(R_TextType), JsonFns.FromText)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("str", R_TextType, listOf(), JsonFns.ToText)
        b.add("to_text", R_TextType, listOf(), JsonFns.ToText)
    }
}

private object JsonFns {
    val FromText = C_SysFunction.simple1(Db_SysFunction.cast("json", "JSONB"), pure = true) { a ->
        val s = a.asString()
        val r = try {
            Rt_JsonValue.parse(s)
        } catch (e: IllegalArgumentException) {
            throw Rt_Error("fn_json_badstr", "Bad JSON: $s")
        }
        r
    }

    val ToText = C_SysFunction.simple1(Db_SysFunction.cast("json.to_text", "TEXT"), pure = true) { a ->
        val s = a.asJsonString()
        Rt_TextValue(s)
    }
}
