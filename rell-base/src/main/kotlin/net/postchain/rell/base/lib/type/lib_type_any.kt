/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.utils.C_SysFunction
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.utils.PostchainGtvUtils

object C_Lib_Type_Any {
    fun Hash(type: R_Type) = C_SysFunction.simple1(pure = true) { a ->
        val hash = Rt_Utils.wrapErr("fn:any:hash") {
            val gtv = type.rtToGtv(a, false)
            PostchainGtvUtils.merkleHash(gtv)
        }
        Rt_ByteArrayValue(hash)
    }

    val ToText_R = C_SysFunction.rSimple1 { a ->
        val s = a.str()
        Rt_TextValue(s)
    }

    val ToText_Db = Db_SysFunction.cast("to_text", "TEXT")

    // No DB-operation, as most types do not support it.
    val ToText_NoDb = C_SysFunction.direct(ToText_R, pure = true)

    fun ToGtv(type: R_Type, pretty: Boolean, name: String) = C_SysFunction.simple1(pure = true) { a ->
        val gtv = try {
            type.rtToGtv(a, pretty)
        } catch (e: Throwable) {
            throw Rt_Exception.common(name, e.message ?: "error")
        }
        Rt_GtvValue(gtv)
    }

    fun FromGtv(type: R_Type, pretty: Boolean, name: String) = C_SysFunction.context1(
        pure = type.completeFlags().pure
    ) { ctx, a ->
        val gtv = a.asGtv()
        Rt_Utils.wrapErr({ "fn:[$name]:from_gtv:$pretty" }) {
            val convCtx = GtvToRtContext.make(pretty)
            val res = type.gtvToRt(convCtx, gtv)
            convCtx.finish(ctx.exeCtx)
            res
        }
    }
}