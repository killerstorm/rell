/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import net.postchain.rell.compiler.ast.S_BasicPos
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.compiler.parser.RellTokenizer
import net.postchain.rell.compiler.parser.S_Grammar
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import kotlin.test.assertEquals

abstract class BaseIdeSymbolInfoTest: BaseRellTest(false) {
    protected fun chkExpr(expr: String, vararg expected: String) {
        chkSyms("function main() = $expr;", "main:DEF_FUNCTION_REGULAR", *expected)
    }

    protected fun chkExprErr(expr: String, err: String, vararg expected: String) {
        chkSymsErr("function main() = $expr;", err, "main:DEF_FUNCTION_REGULAR", *expected)
    }

    protected fun chkType(type: String, vararg expected: String) {
        chkSyms("struct s { x: $type; }", "s:DEF_STRUCT", "x:MEM_STRUCT_ATTR", *expected)
    }

    protected fun chkSyms(code: String, vararg expected: String, ide: Boolean = false) {
        val syms = extractSymbols(code)
        val cRes = compileCode(code, ide)
        assertEquals(listOf(), cRes.messages.map { it.code })
        val actualList = calcActualResult(syms, cRes)
        val expectedList = expected.toList()
        assertEquals(expectedList, actualList)
    }

    protected fun chkSymsErr(code: String, expectedErr: String, vararg expectedSyms: String, ide: Boolean = false) {
        val syms = extractSymbols(code)
        val cRes = compileCode(code, ide)
        assertEquals(expectedErr, cRes.messages.joinToString(",") { it.code })
        val actualList = calcActualResult(syms, cRes)
        val expectedList = expectedSyms.toList()
        assertEquals(expectedList, actualList)
    }

    private fun calcActualResult(syms: Map<S_Pos, String>, cRes: C_CompilationResult): List<String> {
        for (pos in cRes.ideSymbolInfos.keys) {
            if (pos.path() == MAIN_FILE_PATH) {
                check(pos in syms) { "$pos:${cRes.ideSymbolInfos[pos]} $syms ${cRes.ideSymbolInfos}" }
            }
        }

        return syms
                .map {
                    val ideInfo = cRes.ideSymbolInfos[it.key]
                    val infoStr = ideInfo?.kind?.name ?: "?"
                    "${it.value}:$infoStr"
                }
                .toImmList()
    }

    private fun extractSymbols(code: String): Map<S_Pos, String> {
        val syms = mutableMapOf<S_Pos, String>()

        val tokenizer = S_Grammar.tokenizer
        val ts = tokenizer.tokenize(code)

        for (t in ts) {
            if (t.type.pattern == RellTokenizer.IDENTIFIER || t.type.pattern == "$") {
                val pos: S_Pos = S_BasicPos(MAIN_FILE_PATH, t.row, t.column)
                syms[pos] = t.text
            }
        }

        return syms.toImmMap()
    }

    private fun compileCode(code: String, ide: Boolean): C_CompilationResult {
        val sourceDir = tst.createSourceDir(code)
        val modSel = C_CompilerModuleSelection(immListOf(R_ModuleName.EMPTY))
        val cOpts = C_CompilerOptions.builder(tst.compilerOptions()).ide(ide).build()
        val cRes = RellTestUtils.compileApp(sourceDir, modSel, cOpts)
        return cRes
    }

    companion object {
        private val MAIN_FILE_PATH = C_SourcePath.parse(RellTestUtils.MAIN_FILE)
    }
}
