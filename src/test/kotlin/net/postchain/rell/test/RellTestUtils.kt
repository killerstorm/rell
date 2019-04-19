package net.postchain.rell.test

import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Type
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*

object RellTestUtils {
    val ENCODER_PLAIN = { t: R_Type, v: Rt_Value -> v.toString() }
    val ENCODER_STRICT = { t: R_Type, v: Rt_Value -> v.toStrictString() }
    val ENCODER_GTX = { t: R_Type, v: Rt_Value -> GtxTestUtils.encodeGtxStr(t.rtToGtx(v, true)) }

    val MAIN_FILE = "main.rell"

    fun processModule(
            code: String,
            errPos: Boolean = false,
            gtx: Boolean = false,
            includeDir: C_IncludeDir = C_EmptyIncludeDir,
            processor: (R_Module) -> String): String
    {
        val p = catchCtErr0(errPos) {
            parseModule(code, gtx, includeDir)
        }
        return p.first ?: processor(p.second!!)
    }

    fun <T> catchCtErr0(errPos: Boolean, block: () -> T): Pair<String?, T?> {
        try {
            val res = block()
            return Pair(null, res)
        } catch (e: C_Error) {
            val p = if (errPos) ":" + e.pos else ""
            return Pair("ct_err$p:" + e.code, null)
        }
    }

    fun catchRtErr(block: () -> String): String {
        val p = catchRtErr0(block)
        return p.first ?: p.second!!
    }

    fun <T> catchRtErr(block1: () -> T, block2: (T) -> String): String {
        val p1 = catchRtErr0(block1)
        if (p1.first != null) return p1.first!!
        return block2(p1.second!!)
    }

    fun <T> catchRtErr0(block: () -> T): Pair<String?, T?> {
        try {
            val res = block()
            return Pair(null, res)
        } catch (e: Rt_Error) {
            return Pair("rt_err:" + e.code, null)
        } catch (e: Rt_RequireError) {
            return Pair("req_err:" + if (e.userMsg != null) "[${e.userMsg}]" else "null", null)
        } catch (e: Rt_GtxValueError) {
            return Pair("gtx_err:" + e.code, null)
        }
    }

    fun callFn(modCtx: Rt_ModuleContext, name: String, args: List<Rt_Value>, strict: Boolean): String {
        val fn = modCtx.module.functions[name]
        if (fn == null) throw IllegalStateException("Function not found: '$name'")
        val res = catchRtErr {
            val v = fn.callTopFunction(modCtx, args)
            if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    fun callQuery(modCtx: Rt_ModuleContext, name: String, args: List<Rt_Value>, encoder: (R_Type, Rt_Value) -> String): String {
        val decoder = { params: List<R_ExternalParam>, args: List<Rt_Value> -> args }
        return callQueryGeneric(modCtx, name, args, decoder, encoder)
    }

    fun <T> callQueryGeneric(
            modCtx: Rt_ModuleContext,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>,
            encoder: (R_Type, Rt_Value) -> String
    ): String
    {
        val query = modCtx.module.queries[name]
        if (query == null) throw IllegalStateException("Query not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(query.params, args) }
        if (rtErr != null) {
            return rtErr
        }

        val res = catchRtErr {
            val v = query.callTopQuery(modCtx, rtArgs!!)
            encoder(query.type, v)
        }
        return res
    }

    fun callOp(modCtx: Rt_ModuleContext, name: String, args: List<Rt_Value>): String {
        val decoder = { params: List<R_ExternalParam>, args: List<Rt_Value> -> args }
        return callOpGeneric(modCtx, name, args, decoder)
    }

    fun <T> callOpGeneric(
            modCtx: Rt_ModuleContext,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>
    ): String
    {
        val op = modCtx.module.operations[name]
        if (op == null) throw IllegalStateException("Operation not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(op.params, args) }
        if (rtErr != null) {
            return rtErr
        }

        return catchRtErr {
            op.callTop(modCtx, rtArgs!!)
            "OK"
        }
    }

    fun parseModule(code: String, gtx: Boolean, includeDir: C_IncludeDir): R_Module {
        val includeResolver = C_IncludeResolver(includeDir)

        val ast = parse(code)
        val m = ast.compile(MAIN_FILE, includeResolver, gtx)

        TestSourcesRecorder.addSource(code)
        return m.rModule
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return C_Parser.parse(MAIN_FILE, code)
        } catch (e: C_Error) {
            println("PARSING FAILED:")
            println(code)
            throw e
        }
    }
}
