/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.R_ByteArrayType
import net.postchain.rell.model.R_GtvType
import net.postchain.rell.model.R_JsonType
import net.postchain.rell.model.R_TextType
import net.postchain.rell.runtime.Rt_ByteArrayValue
import net.postchain.rell.runtime.Rt_GtvValue
import net.postchain.rell.runtime.Rt_JsonValue
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.utils.PostchainUtils

object C_Lib_Type_Gtv: C_Lib_Type("gtv", R_GtvType) {
    override fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
        b.add("fromBytes", R_GtvType, listOf(R_ByteArrayType), GtvFns.FromBytes, C_LibUtils.depError("from_bytes"))
        b.add("from_bytes", R_GtvType, listOf(R_ByteArrayType), GtvFns.FromBytes)
        b.add("fromJSON", R_GtvType, listOf(R_TextType), GtvFns.FromJson_Text, C_LibUtils.depError("from_json"))
        b.add("from_json", R_GtvType, listOf(R_TextType), GtvFns.FromJson_Text)
        b.add("fromJSON", R_GtvType, listOf(R_JsonType), GtvFns.FromJson_Json, C_LibUtils.depError("from_json"))
        b.add("from_json", R_GtvType, listOf(R_JsonType), GtvFns.FromJson_Json)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("toBytes", R_ByteArrayType, listOf(), GtvFns.ToBytes, C_LibUtils.depError("to_bytes"))
        b.add("to_bytes", R_ByteArrayType, listOf(), GtvFns.ToBytes)
        b.add("toJSON", R_JsonType, listOf(), GtvFns.ToJson, C_LibUtils.depError("to_json"))
        b.add("to_json", R_JsonType, listOf(), GtvFns.ToJson)
    }

    override fun bindAliases(b: C_SysNsProtoBuilder) {
        bindAlias(b, "GTXValue", bindNamespace = true, deprecated = C_LibUtils.depError("gtv"))
    }
}

private object GtvFns {
    val ToBytes = C_SysFunction.simple1(pure = true) { a ->
        val gtv = a.asGtv()
        val bytes = PostchainUtils.gtvToBytes(gtv)
        Rt_ByteArrayValue(bytes)
    }

    val ToJson = C_SysFunction.simple1(pure = true) { a ->
        val gtv = a.asGtv()
        val json = PostchainUtils.gtvToJson(gtv)
        //TODO consider making a separate function toJSONStr() to avoid unnecessary conversion str -> json -> str.
        Rt_JsonValue.parse(json)
    }

    val FromBytes = C_SysFunction.simple1(pure = true) { a ->
        val bytes = a.asByteArray()
        Rt_Utils.wrapErr("fn:gtv.from_bytes") {
            val gtv = PostchainUtils.bytesToGtv(bytes)
            Rt_GtvValue(gtv)
        }
    }

    val FromJson_Text = C_SysFunction.simple1(pure = true) { a ->
        val str = a.asString()
        Rt_Utils.wrapErr("fn:gtv.from_json(text)") {
            val gtv = PostchainUtils.jsonToGtv(str)
            Rt_GtvValue(gtv)
        }
    }

    val FromJson_Json = C_SysFunction.simple1(pure = true) { a ->
        val str = a.asJsonString()
        Rt_Utils.wrapErr("fn:gtv.from_json(json)") {
            val gtv = PostchainUtils.jsonToGtv(str)
            Rt_GtvValue(gtv)
        }
    }
}
