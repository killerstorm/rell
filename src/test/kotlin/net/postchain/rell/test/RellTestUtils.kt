package net.postchain.rell.test

import net.postchain.gtx.GTXValue
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Query
import net.postchain.rell.model.R_Type
import net.postchain.rell.module.GtxToRtContext
import net.postchain.rell.parser.C_Error
import net.postchain.rell.parser.C_Utils
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.*
import java.lang.IllegalStateException

object RellTestUtils {
    val ENCODER_PLAIN = { t: R_Type, v: Rt_Value -> v.toString() }
    val ENCODER_STRICT = { t: R_Type, v: Rt_Value -> v.toStrictString() }
    val ENCODER_GTX = { t: R_Type, v: Rt_Value -> GtxTestUtils.encodeGtxStr(t.rtToGtx(v, true)) }

    fun processModule(code: String, errPos: Boolean = false, gtx: Boolean = false, processor: (R_Module) -> String): String {
        val module = try {
            parseModule(code, gtx)
        } catch (e: C_Error) {
            val p = if (errPos) "" + e.pos else ""
            return "ct_err$p:" + e.code
        }
        return processor(module)
    }

    private fun catchRtErr(block: () -> String): String {
        val p = catchRtErr0(block)
        return p.first ?: p.second!!
    }

    private fun <T> catchRtErr0(block: () -> T): Pair<String?, T?> {
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

    fun callFn(globalCtx: Rt_GlobalContext, module: R_Module, name: String, args: List<Rt_Value>, strict: Boolean): String {
        val fn = module.functions[name]
        if (fn == null) throw IllegalStateException("Function not found: '$name'")
        val modCtx = Rt_ModuleContext(globalCtx, module)
        val res = catchRtErr {
            val v = fn.callTopFunction(modCtx, args)
            if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    fun callQuery(globalCtx: Rt_GlobalContext, module: R_Module, name: String, args: List<Rt_Value>, encoder: (R_Type, Rt_Value) -> String): String {
        val decoder = { params: List<R_ExternalParam>, args: List<Rt_Value> -> args }
        return callQueryGeneric(globalCtx, module, name, args, decoder, encoder)
    }

    fun <T> callQueryGeneric(
            globalCtx: Rt_GlobalContext,
            module: R_Module,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>,
            encoder: (R_Type, Rt_Value) -> String
    ): String
    {
        val query = module.queries[name]
        if (query == null) throw IllegalStateException("Query not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(query.params, args) }
        if (rtErr != null) {
            return rtErr
        }

        val modCtx = Rt_ModuleContext(globalCtx, module)
        return callQuery0(modCtx, query, rtArgs!!, encoder)
    }

    private fun callQuery0(modCtx: Rt_ModuleContext, query: R_Query, args: List<Rt_Value>, encoder: (R_Type, Rt_Value) -> String): String {
        val res = catchRtErr {
            val v = query.callTopQuery(modCtx, args)
            encoder(query.type, v)
        }
        return res
    }

    fun callOp(globalCtx: Rt_GlobalContext, module: R_Module, name: String, args: List<Rt_Value>): String {
        val decoder = { params: List<R_ExternalParam>, args: List<Rt_Value> -> args }
        return callOpGeneric(globalCtx, module, name, args, decoder)
    }

    fun <T> callOpGeneric(
            globalCtx: Rt_GlobalContext,
            module: R_Module,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>
    ): String
    {
        val op = module.operations[name]
        if (op == null) throw IllegalStateException("Operation not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(op.params, args) }
        if (rtErr != null) {
            return rtErr
        }

        val modCtx = Rt_ModuleContext(globalCtx, module)
        return catchRtErr {
            op.callTop(modCtx, rtArgs!!)
            ""
        }
    }

    fun parseModule(code: String, gtx: Boolean): R_Module {
        val ast = parse(code)
        val m = ast.compile(gtx)
        TestSourcesRecorder.addSource(code)
        return m
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return C_Utils.parse(code)
        } catch (e: C_Error) {
            println("PARSING FAILED:")
            println(code)
            throw e
        }
    }
}
