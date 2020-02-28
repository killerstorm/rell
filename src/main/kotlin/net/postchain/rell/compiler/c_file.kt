/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import org.apache.commons.lang3.StringUtils
import java.io.File

data class C_SourcePath(val parts: List<String> = listOf()): Comparable<C_SourcePath> {
    private val str = parts.joinToString("/")

    fun add(path: C_SourcePath) = C_SourcePath(parts + path.parts)
    fun add(part: String) = C_SourcePath(parts + part)
    fun parent() = C_SourcePath(parts.subList(0, parts.size - 1))
    fun str() = str

    override fun compareTo(other: C_SourcePath) = CommonUtils.compareLists(parts, other.parts)
    override fun toString() = str()

    override fun equals(other: Any?) = other is C_SourcePath && parts == other.parts
    override fun hashCode() = parts.hashCode()

    companion object {
        fun parse(path: String): C_SourcePath {
            val res = parseOpt(path)
            return res ?: throw C_CommonError("invalid_path:$path", "Invalid path: '$path'")
        }

        fun parseOpt(path: String): C_SourcePath? {
            val parts = StringUtils.splitPreserveAllTokens(path, "/\\").toList()
            if (parts.isEmpty()) {
                return null
            }

            for (part in parts) {
                if (part == "" || part == "." || part == "..") {
                    return null
                }
            }

            return C_SourcePath(parts)
        }
    }
}

abstract class C_SourceFile {
    abstract fun readAst(): S_RellFile
    abstract fun readText(): String
}

abstract class C_SourceDir {
    abstract fun dir(path: C_SourcePath): Boolean
    abstract fun file(path: C_SourcePath): C_SourceFile?
    abstract fun dirs(path: C_SourcePath): List<String>
    abstract fun files(path: C_SourcePath): List<String>
}

class C_TextSourceFile(private val path: C_SourcePath, private val text: String): C_SourceFile() {
    override fun readAst(): S_RellFile {
        return C_Parser.parse(path, text)
    }

    override fun readText() = text
}

class C_MapSourceDir(files: Map<C_SourcePath, C_SourceFile>): C_SourceDir() {
    private val root = buildTree(files)

    override fun dir(path: C_SourcePath): Boolean {
        val dir = findDir(path.parts)
        return dir != null
    }

    override fun file(path: C_SourcePath): C_SourceFile? {
        val n = path.parts.size
        if (n == 0) return null

        val dir = findDir(path.parts.subList(0, n - 1))
        if (dir == null) return null

        val file = dir.files[path.parts[n - 1]]
        return file
    }

    override fun dirs(path: C_SourcePath): List<String> {
        val dir = findDir(path.parts)
        if (dir == null) return listOf()
        return dir.dirs.keys.sorted().toImmList()
    }

    override fun files(path: C_SourcePath): List<String> {
        val dir = findDir(path.parts)
        if (dir == null) return listOf()
        return dir.files.keys.sorted().toImmList()
    }

    private fun findDir(path: List<String>): DirNode? {
        var dir = root
        for (part in path) {
            val next = dir.dirs[part]
            if (next == null) return null
            dir = next
        }
        return dir
    }

    private class DirNode(val dirs: Map<String, DirNode>, val files: Map<String, C_SourceFile>)

    companion object {
        @JvmField
        val EMPTY = C_MapSourceDir(mapOf())

        @JvmStatic
        fun of(files: Map<String, String>): C_SourceDir {
            val files2 = files
                    .mapKeys { (k, _) -> C_SourcePath.parse(k) }
                    .mapValues { (k, v) -> C_TextSourceFile(k, v) }
            return C_MapSourceDir(files2)
        }

        @JvmStatic
        fun of(vararg files: Pair<String, String>): C_SourceDir {
            val files2 = mapOf(*files)
            return of(files2)
        }

        private fun buildTree(map: Map<C_SourcePath, C_SourceFile>): DirNode {
            for (path in map.keys) {
                check(!path.parts.isEmpty())
            }
            return buildTree(map, 0)
        }

        private fun buildTree(map: Map<C_SourcePath, C_SourceFile>, pos: Int): DirNode {
            val files = mutableMapOf<String, C_SourceFile>()
            val subs = mutableMapOf<String, MutableMap<C_SourcePath, C_SourceFile>>()

            for ((path, file) in map) {
                val len = path.parts.size
                val name = path.parts[pos]
                if (len == pos + 1) {
                    files[name] = file
                } else {
                    val sub = subs.computeIfAbsent(name) { mutableMapOf() }
                    sub[path] = file
                }
            }

            val dirs = subs.mapValues { buildTree(it.value, pos + 1) }
            return DirNode(dirs.toImmMap(), files.toImmMap())
        }
    }
}

class C_DiskSourceDir(private val dir: File): C_SourceDir() {
    override fun dir(path: C_SourcePath): Boolean {
        val file = toFile(path)
        return file.isDirectory
    }

    override fun file(path: C_SourcePath): C_SourceFile? {
        val file = toFile(path)
        return if (file.isFile) C_DiskSourceFile(file, path) else null
    }

    override fun dirs(path: C_SourcePath): List<String> {
        return members(path) { it.isDirectory }
    }

    override fun files(path: C_SourcePath): List<String> {
        return members(path) { it.isFile }
    }

    private fun members(path: C_SourcePath, filter: (File) -> Boolean): List<String> {
        val file = toFile(path)
        val files = file.listFiles() ?: arrayOf<File>()
        return files.filter(filter).map { it.name }.sorted()
    }

    private fun toFile(path: C_SourcePath): File {
        val pathStr = path.str()
        return File(dir, pathStr)
    }

    private class C_DiskSourceFile(private val file: File, private val sourcePath: C_SourcePath): C_SourceFile() {
        override fun readAst(): S_RellFile {
            val text = readText()
            return C_Parser.parse(sourcePath, text)
        }

        override fun readText() = file.readText()
    }
}

class C_CachedSourceDir(private val sourceDir: C_SourceDir): C_SourceDir() {
    private val cache = mutableMapOf<C_SourcePath, CacheEntry>()

    override fun dir(path: C_SourcePath): Boolean {
        val e = lookup(path)
        var dir = e.dir
        if (dir == null) {
            dir = sourceDir.dir(path)
            e.dir = dir
        }
        return dir
    }

    override fun file(path: C_SourcePath): C_SourceFile? {
        val e = lookup(path)
        var file = e.file
        if (!e.fileKnown) {
            file = sourceDir.file(path)
            if (file != null) {
                file = C_CachedSourceFile(file)
            }
            e.file = file
            e.fileKnown = true
        }
        return file
    }

    override fun dirs(path: C_SourcePath): List<String> {
        val e = lookup(path)
        var dirs = e.dirs
        if (dirs == null) {
            dirs = sourceDir.dirs(path)
            if (dirs.isNotEmpty()) e.dir = true
            e.dirs = dirs
        }
        return dirs
    }

    override fun files(path: C_SourcePath): List<String> {
        val e = lookup(path)
        var files = e.files
        if (files == null) {
            files = sourceDir.files(path)
            if (files.isNotEmpty()) e.dir = true
            e.files = files
        }
        return files
    }

    private fun lookup(path: C_SourcePath): CacheEntry = cache.computeIfAbsent(path) { CacheEntry() }

    private class C_CachedSourceFile(private val file: C_SourceFile): C_SourceFile() {
        private val ast = CachedField { file.readAst() }
        private val text = CachedField { file.readText() }

        override fun readAst(): S_RellFile {
            return ast.get()
        }

        override fun readText(): String {
            return text.get()
        }

        private class CachedField<T>(private val f: () -> T) {
            private var value: T? = null
            private var error: Exception? = null

            fun get(): T {
                var res = value
                if (res != null) {
                    return res
                }

                val err = error
                if (err != null) {
                    throw err
                }

                try {
                    res = f()
                    value = res
                    return res
                } catch (e: Exception) {
                    error = e
                    throw e
                }
            }
        }
    }

    private class CacheEntry {
        var dir: Boolean? = null
        var fileKnown = false
        var file: C_SourceFile? = null
        var dirs: List<String>? = null
        var files: List<String>? = null
    }
}
