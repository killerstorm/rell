/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.ide

import net.postchain.rell.base.compiler.ast.S_BasicPos
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.C_CompilationResult
import net.postchain.rell.base.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.base.compiler.base.core.C_CompilerOptions
import net.postchain.rell.base.compiler.base.utils.C_ParserFilePath
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.compiler.base.utils.C_SourcePath
import net.postchain.rell.base.compiler.parser.RellTokenizer
import net.postchain.rell.base.compiler.parser.S_Grammar
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.runtime.Rt_TextValue
import net.postchain.rell.base.testutils.BaseRellTest
import net.postchain.rell.base.testutils.RellTestUtils
import net.postchain.rell.base.utils.ide.*
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap
import kotlin.test.assertEquals

abstract class BaseIdeSymbolTest: BaseRellTest(false) {
    protected class SymParts(val kind: Boolean = false, val def: Boolean = false, val link: Boolean = false)

    protected fun replaceKdl(info: String, name: String? = null, kind: String? = null, defId: String? = null, link: String? = null): String {
        val m = Regex("^([A-Za-z_][A-Za-z0-9_]*):([^;]+);([^;]+);([^;]+)$").matchEntire(info)
        checkNotNull(m) { info }
        val (oldName, oldKind, oldDefId, oldLink) = m.destructured
        return "${name?:oldName}:${kind?:oldKind};${defId?:oldDefId};${link?:oldLink}"
    }

    protected fun chkKindsExpr(expr: String, vararg expected: String) {
        chkKinds("function main() = $expr;", "main:DEF_FUNCTION", *expected)
    }

    protected fun chkKindsExprErr(expr: String, err: String, vararg expected: String) {
        chkKindsErr("function main() = $expr;", err, "main:DEF_FUNCTION", *expected)
    }

    protected fun chkKdlsExpr(expr: String, vararg expected: String) {
        chkKdls("function main() = $expr;", "main:DEF_FUNCTION;function[main];-", *expected)
    }

    protected fun chkKdlsExprErr(expr: String, err: String, vararg expected: String) {
        chkKdlsErr("function main() = $expr;", err, "main:DEF_FUNCTION;function[main];-", *expected)
    }

    protected fun chkKindsType(type: String, vararg expected: String) {
        chkKinds("struct s { x: $type; }", "s:DEF_STRUCT", "x:MEM_STRUCT_ATTR", *expected)
    }

    protected fun chkKdlsType(type: String, vararg expected: String) {
        chkKdls("struct s { x: $type; }", "s:DEF_STRUCT;struct[s];-", "x:MEM_STRUCT_ATTR;struct[s].attr[x];-", *expected)
    }

    protected fun chkKinds(code: String, vararg expected: String, ide: Boolean = false) {
        chkSyms(code, *expected, ide = ide, parts = SymParts(kind = true))
    }

    protected fun chkDefs(code: String, vararg expected: String) {
        chkSyms(code, *expected, parts = SymParts(def = true))
    }

    protected fun chkLnks(code: String, vararg expected: String) {
        chkSyms(code, *expected, parts = SymParts(link = true))
    }

    protected fun chkKdls(code: String, vararg expected: String, ide: Boolean = false) {
        chkSyms(code, *expected, parts = SymParts(kind = true, def = true, link = true), ide = ide)
    }

    protected fun chkKdlsErr(code: String, expectedErr: String, vararg expectedSyms: String, ide: Boolean = false) {
        chkSymsErr(code, expectedErr, *expectedSyms, parts = SymParts(kind = true, def = true, link = true), ide = ide)
    }

    protected fun chkFileDefs(file: String, vararg expected: String) {
        chkSyms("", *expected, file = C_SourcePath.parse(file), parts = SymParts(def = true), ide = true)
    }

    protected fun chkFileLnks(file: String, vararg expected: String) {
        chkSyms("", *expected, file = C_SourcePath.parse(file), parts = SymParts(link = true), ide = true)
    }

    protected fun chkFileKdls(file: String, vararg expected: String) {
        chkSyms("", *expected, file = C_SourcePath.parse(file), parts = SymParts(kind = true, def = true, link = true), ide = true)
    }

    private fun chkSyms(
        code: String,
        vararg expected: String,
        ide: Boolean = false,
        file: C_SourcePath = MAIN_FILE_PATH,
        parts: SymParts,
    ) {
        val sourceDir = tst.createSourceDir(code)
        val cRes = compileCode(sourceDir, file, ide)
        assertEquals(listOf(), cRes.messages.map { it.code })

        val actualList = calcActualResult(sourceDir, file, cRes, parts)
        val expectedList = expected.toList()
        assertSyms(expectedList, actualList)
    }

    protected fun chkKindsErr(code: String, expectedErr: String, vararg expectedSyms: String, ide: Boolean = false) {
        chkSymsErr(code, expectedErr, *expectedSyms, ide = ide, parts = SymParts(kind = true))
    }

    private fun chkSymsErr(code: String, expectedErr: String, vararg expectedSyms: String, ide: Boolean = false, parts: SymParts) {
        val sourceDir = tst.createSourceDir(code)
        val file = MAIN_FILE_PATH
        val cRes = compileCode(sourceDir, file, ide)
        assertEquals(expectedErr, cRes.messages.joinToString(",") { it.code })
        val actualList = calcActualResult(sourceDir, file, cRes, parts)
        val expectedList = expectedSyms.toList()
        assertSyms(expectedList, actualList)
    }

    private fun compileCode(sourceDir: C_SourceDir, file: C_SourcePath, ide: Boolean): C_CompilationResult {
        val moduleName = getModuleName(sourceDir, file)
        val modSel = C_CompilerModuleSelection(immListOf(moduleName))
        val cOpts = C_CompilerOptions.builder(tst.compilerOptions()).symbolInfoFile(file).ide(ide).build()
        val cRes = RellTestUtils.compileApp(sourceDir, modSel, cOpts)
        return cRes
    }

    private fun getModuleName(sourceDir: C_SourceDir, file: C_SourcePath): R_ModuleName {
        val sourceFile = sourceDir.file(file)
        sourceFile ?: throw IllegalArgumentException(file.str())
        val ast = sourceFile.readAst()
        val res = IdeApi.getModuleName(file, ast)
        return res ?: throw IllegalArgumentException(file.str())
    }

    private fun assertSyms(expectedList: List<String>, actualList: List<String>) {
        val match = actualList.size == expectedList.size && actualList.indices.all { matchSym(actualList[it], expectedList[it]) }
        if (!match) {
            // Replace matching actual values with exact expected values, so assertEquals() shows only non-matching differences.
            val actualList2 = actualList.mapIndexed { i, actual ->
                if (i < expectedList.size && matchSym(actual, expectedList[i])) expectedList[i] else actual
            }
            assertEquals(expectedList, actualList2)
            throw IllegalStateException() // Must not happen - assertEquals() must fail.
        }
    }

    private fun matchSym(actual: String, expected: String): Boolean {
        val pat = Rt_TextValue.likePatternToRegex(expected, '?', '*')
        return pat.matcher(actual).matches()
    }

    private fun calcActualResult(
        sourceDir: C_SourceDir,
        file: C_SourcePath,
        cRes: C_CompilationResult,
        parts: SymParts,
    ): List<String> {
        val syms = extractSymbols(sourceDir, file)

        for (pos in cRes.ideSymbolInfos.keys) {
            if (pos.path() == file) {
                check(pos in syms) { "$pos:${cRes.ideSymbolInfos[pos]} $syms ${cRes.ideSymbolInfos}" }
            }
        }

        return syms
                .map {
                    val ideInfo = cRes.ideSymbolInfos[it.key]
                    val infoStr = if (ideInfo == null) "?" else ideInfoToStr(ideInfo, parts, syms)
                    "${it.value}:$infoStr"
                }
                .toImmList()
    }

    private fun extractSymbols(sourceDir: C_SourceDir, file: C_SourcePath): Map<S_Pos, String> {
        val sourceFile = sourceDir.file(file)
        sourceFile ?: throw IllegalArgumentException(file.str())

        val parserPath = C_ParserFilePath(file, sourceFile.idePath())
        val code = sourceFile.readText()

        val syms = mutableMapOf<S_Pos, String>()

        val tokenizer = S_Grammar.tokenizer
        val ts = tokenizer.tokenize(code)

        for (t in ts) {
            if (t.type.pattern == RellTokenizer.IDENTIFIER || t.type.pattern == "$") {
                val pos: S_Pos = S_BasicPos(parserPath, t.row, t.column)
                syms[pos] = t.text
            }
        }

        return syms.toImmMap()
    }

    private fun ideInfoToStr(ideInfo: IdeSymbolInfo, parts: SymParts, syms: Map<S_Pos, String>): String {
        val res = mutableListOf<String>()
        if (parts.kind) res.add(ideInfo.kind.name)
        if (parts.def) res.add(ideInfo.defId?.encode() ?: "-")
        if (parts.link) res.add(linkToStr(ideInfo.link, syms))
        return res.joinToString(";")
    }

    private fun linkToStr(link: IdeSymbolLink?, syms: Map<S_Pos, String>): String {
        return when (link) {
            null -> "-"
            is IdeModuleSymbolLink -> link.encode()
            is IdeGlobalSymbolLink -> link.encode()
            is IdeLocalSymbolLink -> {
                val pos = link.localPos()
                val name = syms.getValue(pos)
                val idx = syms.entries.filter { it.value == name }.indexOfFirst { it.key == pos }
                "local[$name:$idx]"
            }
            else -> TODO(link.javaClass.simpleName)
        }
    }

    companion object {
        private val MAIN_FILE_PATH = C_SourcePath.parse(RellTestUtils.MAIN_FILE)
    }
}
