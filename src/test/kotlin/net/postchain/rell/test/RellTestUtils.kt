package net.postchain.rell.test

import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Type
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*

object RellTestUtils {
    val ENCODER_PLAIN = { t: R_Type, v: Rt_Value -> v.toString() }
    val ENCODER_STRICT = { t: R_Type, v: Rt_Value -> v.toStrictString() }
    val ENCODER_GTX = { t: R_Type, v: Rt_Value -> GtxTestUtils.encodeGtxStr(t.rtToGtx(v, true)) }

    val MAIN_FILE = "main.rell"

    fun processModule(code: String, processor: (RellTestModule) -> String): String {
        val includeDir = C_VirtualIncludeDir(mapOf(MAIN_FILE to code))
        return processModule(includeDir, processor = processor)
    }

    fun processModule(
            includeDir: C_IncludeDir,
            errPos: Boolean = false,
            gtx: Boolean = false,
            processor: (RellTestModule) -> String
    ): String {
        val p = catchCtErr0(errPos) {
            parseModule(includeDir, gtx)
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

    fun parseModule(includeDir: C_IncludeDir, gtx: Boolean): RellTestModule {
        val res = C_Compiler.compile(includeDir, MAIN_FILE, gtx)
        saveSource(includeDir, res)
        if (res.error != null) {
            throw res.error!!
        } else {
            return RellTestModule(res.module!!, res.messages)
        }
    }

    private fun saveSource(includeDir: C_IncludeDir, res: C_CompilationResult) {
        val error = res.error
        val result = if (error == null) "OK" else "ct_err:${error.code}"
        val includeResolver = C_IncludeResolver(includeDir)
        val code = includeResolver.resolve(MAIN_FILE).file.readText()
        TestSourcesRecorder.addSource(code, result)
    }
}
