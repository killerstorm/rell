/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.testutils

import net.postchain.rell.base.compiler.base.core.C_CompilationResult
import net.postchain.rell.base.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.base.compiler.base.core.C_CompilerOptions
import net.postchain.rell.base.compiler.base.utils.*
import net.postchain.rell.base.utils.RellVersions
import net.postchain.rell.base.utils.ide.IdeCodeSnippet
import net.postchain.rell.base.utils.ide.IdeSnippetMessage
import net.postchain.rell.base.utils.toImmMap
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestSnippetsRecorder {
    private const val ENABLED = false
    private val SOURCES_FILE: String = System.getProperty("user.home") + "/testsources-${RellVersions.VERSION_STR}.zip"

    private val sync = Any()
    private val snippets = mutableListOf<IdeCodeSnippet>()
    private var shutdownHookInstalled = false

    fun record(
        sourceDir: C_SourceDir,
        modules: C_CompilerModuleSelection,
        options: C_CompilerOptions,
        res: C_CompilationResult
    ) {
        if (!ENABLED) return

        val files = sourceDirToMap(sourceDir)
        val messages = res.messages.map { IdeSnippetMessage(it.pos.str(), it.type, it.code, it.text) }
        val parsing = makeParsing(files)

        val snippet = IdeCodeSnippet(files, modules, options, messages, parsing)
        addSnippet(snippet)
    }

    private fun sourceDirToMap(sourceDir: C_SourceDir): Map<String, String> {
        val map = mutableMapOf<C_SourcePath, String>()
        sourceDirToMap0(sourceDir, C_SourcePath.EMPTY, map)
        return map.mapKeys { (k, _) -> k.str() }.toImmMap()
    }

    private fun sourceDirToMap0(sourceDir: C_SourceDir, path: C_SourcePath, map: MutableMap<C_SourcePath, String>) {
        for (file in sourceDir.files(path)) {
            val subPath = path.add(file)
            check(subPath !in map) { "File already in the map: $subPath" }
            val text = sourceDir.file(subPath)!!.readText()
            map[subPath] = text
        }

        for (dir in sourceDir.dirs(path)) {
            val subPath = path.add(dir)
            sourceDirToMap0(sourceDir, subPath, map)
        }
    }

    private fun makeParsing(files: Map<String, String>): Map<String, List<IdeSnippetMessage>> {
        val res = mutableMapOf<String, List<IdeSnippetMessage>>()

        for ((file, code) in files) {
            val sourcePath = C_SourcePath.parse(file)
            val idePath = IdeSourcePathFilePath(sourcePath)
            val messages = try {
                C_Parser.parse(sourcePath, idePath, code)
                listOf()
            } catch (e: C_Error) {
                listOf(IdeSnippetMessage(e.pos.str(), C_MessageType.ERROR, e.code, e.errMsg))
            }
            res[file] = messages
        }

        return res.toImmMap()
    }

    private fun addSnippet(snippet: IdeCodeSnippet) {
        synchronized (sync) {
            snippets.add(snippet)
            if (!shutdownHookInstalled) {
                val thread = Thread(TestSnippetsRecorder::saveSources)
                thread.name = "SaveSources"
                thread.isDaemon = false
                Runtime.getRuntime().addShutdownHook(thread)
                shutdownHookInstalled = true
            }
        }
    }

    private fun saveSources() {
        synchronized (sync) {
            try {
                saveSourcesZipFile(File(SOURCES_FILE))
            } catch (e: Throwable) {
                System.err.println("Snippets saving failed")
                e.printStackTrace()
            }
        }
    }

    private fun saveSourcesZipFile(f: File) {
        FileOutputStream(f).use { fout ->
            ZipOutputStream(fout).use { zout ->
                for ((i, snippet) in snippets.withIndex()) {
                    val str = snippet.serialize()
                    IdeCodeSnippet.deserialize(str) // Verification
                    zout.putNextEntry(ZipEntry(String.format("%04d.json", i)))
                    zout.write(str.toByteArray())
                }
            }
        }
        printNotice(snippets.size, f)
    }

    private fun printNotice(count: Int, f: File) {
        println("Test snippets ($count) written to file: $f")
    }
}
