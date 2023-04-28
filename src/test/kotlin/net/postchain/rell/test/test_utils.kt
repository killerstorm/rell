/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.base.utils.C_Message
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.C_Lib_OpContext
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.repl.NullReplInterpreterProjExt
import net.postchain.rell.repl.ReplInterpreterProjExt
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.NullSqlInitProjExt
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlInitProjExt
import net.postchain.rell.utils.*
import java.util.*
import kotlin.test.assertEquals

fun String.unwrap(): String = this.replace(Regex("\\n\\s*"), "")

class T_App(val rApp: R_App, val messages: List<C_Message>)

abstract class RellTestProjExt: ProjExt() {
    abstract fun getSqlInitProjExt(): SqlInitProjExt
    abstract fun getReplInterpreterProjExt(): ReplInterpreterProjExt

    abstract fun initSysAppTables(sqlExec: SqlExecutor)

    abstract fun createUnitTestBlockRunner(
        sourceDir: C_SourceDir,
        app: R_App,
        moduleArgs: Map<R_ModuleName, Gtv>,
    ): Rt_UnitTestBlockRunner
}

object BaseRellTestProjExt: RellTestProjExt() {
    override fun getSqlInitProjExt(): SqlInitProjExt = NullSqlInitProjExt
    override fun getReplInterpreterProjExt(): ReplInterpreterProjExt = NullReplInterpreterProjExt

    override fun initSysAppTables(sqlExec: SqlExecutor) {
        SqlTestUtils.createSysAppTables(sqlExec)
    }

    override fun createUnitTestBlockRunner(
        sourceDir: C_SourceDir,
        app: R_App,
        moduleArgs: Map<R_ModuleName, Gtv>
    ): Rt_UnitTestBlockRunner {
        return Rt_NullUnitTestBlockRunner
    }
}

class Rt_TestPrinter: Rt_Printer {
    private val queue = LinkedList<String>()

    override fun print(str: String) {
        queue.add(str)
    }

    fun chk(vararg expected: String) {
        val expectedList = expected.toList()
        val actualList = queue.toList()
        assertEquals(expectedList, actualList)
        queue.clear()
    }
}

class Rt_TestOpContext(
    private val lastBlockTime: Long,
    private val transactionIid: Long,
    private val blockHeight: Long,
    private val opIndex: Int,
    signers: List<Bytes>,
    allOperations: List<Pair<String, List<Gtv>>>
): Rt_OpContext() {
    private val signers = signers.toImmList()
    private val allOperations = allOperations.toImmList()

    override fun exists() = true
    override fun lastBlockTime() = lastBlockTime
    override fun transactionIid() = transactionIid
    override fun blockHeight() = blockHeight
    override fun opIndex() = opIndex
    override fun isSigner(pubKey: Bytes) = pubKey in signers
    override fun signers() = signers

    override fun allOperations(): List<Rt_Value> {
        return allOperations.map { op ->
            val name = Rt_TextValue(op.first)
            val args = Rt_ListValue(C_Lib_OpContext.LIST_OF_GTV_TYPE, op.second.map { Rt_GtvValue(it) }.toMutableList())
            Rt_StructValue(C_Lib_OpContext.GTX_OPERATION_STRUCT_TYPE, mutableListOf(name, args)) as Rt_Value
        }
    }

    override fun emitEvent(type: String, data: Gtv) {
        throw Rt_Utils.errNotSupported("Not supported in tests")
    }
}

class RellTestEval {
    private var wrapping = false
    private var lastErrorStack = listOf<R_StackPos>()

    fun eval(code: () -> String): String {
        val oldWrapping = wrapping
        wrapping = true
        return try {
            code()
        } catch (e: EvalException) {
            e.payload
        } finally {
            wrapping = oldWrapping
        }
    }

    fun errorStack() = lastErrorStack

    fun <T> wrapCt(code: () -> T): T {
        if (wrapping) {
            val p = RellTestUtils.catchCtErr0(false, code)
            return result(p)
        } else {
            return code()
        }
    }

    fun <T> wrapRt(code: () -> T): T {
        if (wrapping) {
            val p = RellTestUtils.catchRtErr0(code)
            lastErrorStack = p.first?.stack ?: listOf()
            return result(Pair(p.first?.res, p.second))
        } else {
            return code()
        }
    }

    fun <T> wrapAll(code: () -> T): T {
        return wrapRt {
            wrapCt(code)
        }
    }

    private fun <T> result(p: Pair<String?, T?>): T {
        if (p.first != null) throw EvalException(p.first!!)
        return p.second!!
    }

    private class EvalException(val payload: String): RuntimeException()
}
