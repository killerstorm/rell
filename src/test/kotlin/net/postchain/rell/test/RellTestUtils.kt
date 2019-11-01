package net.postchain.rell.test

import net.postchain.rell.model.*
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*

object RellTestUtils {
    val ENCODER_PLAIN = { _: R_Type, v: Rt_Value -> v.toString() }
    val ENCODER_STRICT = { _: R_Type, v: Rt_Value -> v.toStrictString() }
    val ENCODER_GTV = { t: R_Type, v: Rt_Value -> GtvTestUtils.encodeGtvStr(t.rtToGtv(v, true)) }

    const val MAIN_FILE = "main.rell"

    const val RELL_VER = "0.10"

    fun processApp(code: String, processor: (T_App) -> String): String {
        val sourceDir = C_MapSourceDir.of(MAIN_FILE to code)
        return processApp(sourceDir, processor = processor)
    }

    fun processApp(
            sourceDir: C_SourceDir,
            errPos: Boolean = false,
            options: C_CompilerOptions = C_CompilerOptions.DEFAULT,
            outMessages: MutableList<C_Message>? = null,
            processor: (T_App) -> String
    ): String {
        val cRes = compileApp(sourceDir, options)
        outMessages?.addAll(cRes.messages)

        val errs = cRes.messages.filter { it.type == C_MessageType.ERROR }
        if (!errs.isEmpty()) {
            val s = errsToString(errs, errPos)
            return "ct_err:$s"
        }

        val tApp = T_App(cRes.app!!, cRes.messages)
        return processor(tApp)
    }

    private fun errsToString(errs: List<C_Message>, errPos: Boolean): String {
        val forceFile = errs.any { it.pos.path().str() != "main.rell" }

        val errMsgs = errs
                .sortedBy { it.code }
                .sortedBy { it.pos.pos() }
                .sortedBy { it.pos.path() }
                .sortedBy { it.pos.path().str() != "main.rell" }
                .map { errToString(it.pos, it.code, errPos, forceFile) }

        return if (errMsgs.size == 1) errMsgs[0] else errMsgs.joinToString("") { "[$it]" }
    }

    fun catchCtErr(errPos: Boolean, block: () -> String): String {
        val r = catchCtErr0(errPos, block)
        return r.first ?: r.second!!
    }

    fun <T> catchCtErr0(errPos: Boolean, block: () -> T): Pair<String?, T?> {
        try {
            val res = block()
            return Pair(null, res)
        } catch (e: C_Error) {
            val p = errToString(e.pos, e.code, errPos, false)
            return Pair("ct_err:$p", null)
        }
    }

    fun errToString(pos: S_Pos, code: String, forcePos: Boolean, forceFile: Boolean): String {
        val file = pos.path().str()
        return if (forcePos) {
            "$pos:$code"
        } else if (forceFile || file != "main.rell") {
            "$file:$code"
        } else {
            code
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
        } catch (e: Rt_GtvError) {
            return Pair("gtv_err:" + e.code, null)
        }
    }

    fun callFn(appCtx: Rt_AppContext, name: String, args: List<Rt_Value>, strict: Boolean): String {
        val fn = findFn(appCtx.app, name)
        val res = catchRtErr {
            val v = fn.callTopFunction(appCtx, args)
            if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    private fun findFn(app: R_App, name: String): R_Function {
        for (module in app.modules) {
            val fn = module.functions[name]
            if (fn != null) return fn
        }
        throw IllegalStateException("Function not found: '$name'")
    }

    fun callQuery(appCtx: Rt_AppContext, name: String, args: List<Rt_Value>, encoder: (R_Type, Rt_Value) -> String): String {
        val decoder = { _: List<R_ExternalParam>, args2: List<Rt_Value> -> args2 }
        return callQueryGeneric(appCtx, name, args, decoder, encoder)
    }

    fun <T> callQueryGeneric(
            appCtx: Rt_AppContext,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>,
            encoder: (R_Type, Rt_Value) -> String
    ): String
    {
        val mName = R_MountName.of(name)
        val query = appCtx.app.queries[mName]
        if (query == null) throw IllegalStateException("Query not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(query.params(), args) }
        if (rtErr != null) {
            return rtErr
        }

        val res = catchRtErr {
            val v = query.callTopQuery(appCtx, rtArgs!!)
            encoder(query.type(), v)
        }
        return res
    }

    fun callOp(appCtx: Rt_AppContext, name: String, args: List<Rt_Value>): String {
        val decoder = { _: List<R_ExternalParam>, args2: List<Rt_Value> -> args2 }
        return callOpGeneric(appCtx, name, args, decoder)
    }

    fun <T> callOpGeneric(
            appCtx: Rt_AppContext,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>
    ): String
    {
        val mName = R_MountName.of(name)
        val op = appCtx.app.operations[mName]
        if (op == null) throw IllegalStateException("Operation not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(op.params(), args) }
        if (rtErr != null) {
            return rtErr
        }

        return catchRtErr {
            op.callTop(appCtx, rtArgs!!)
            "OK"
        }
    }

    fun compileApp(sourceDir: C_SourceDir, options: C_CompilerOptions): C_CompilationResult {
        val modules = listOf(R_ModuleName.EMPTY)
        return compileApp(sourceDir, modules, options)
    }

    fun compileApp(sourceDir: C_SourceDir, modules: List<R_ModuleName>, options: C_CompilerOptions): C_CompilationResult {
        val res = C_Compiler.compile(sourceDir, modules, options)
        TestSnippetsRecorder.record(sourceDir, modules, options, res)
        return res
    }
}
