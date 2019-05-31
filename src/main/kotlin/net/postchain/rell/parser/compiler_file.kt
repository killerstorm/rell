package net.postchain.rell.parser

import net.postchain.rell.CommonUtils
import org.apache.commons.lang3.StringUtils
import java.io.File

data class C_SourcePath(val parts: List<String> = listOf()): Comparable<C_SourcePath> {
    fun add(path: C_SourcePath) = C_SourcePath(parts + path.parts)
    fun parent() = C_SourcePath(parts.subList(0, parts.size - 1))
    fun str() = parts.joinToString("/")

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
    abstract fun readAst(): S_ModuleDefinition
    abstract fun readText(): String
}

abstract class C_SourceDir {
    abstract fun file(path: C_SourcePath): C_SourceFile?
}

private class C_VirtualSourceFile(private val path: C_SourcePath, private val text: String): C_SourceFile() {
    override fun readAst(): S_ModuleDefinition {
        return C_Parser.parse(path, text)
    }

    override fun readText() = text
}

class C_VirtualSourceDir(private val files: Map<C_SourcePath, String>): C_SourceDir() {
    override fun file(path: C_SourcePath): C_SourceFile? {
        val text = files[path]
        return if (text == null) null else C_VirtualSourceFile(path, text)
    }
}

private class C_DiskSourceFile(private val file: File, private val sourcePath: C_SourcePath): C_SourceFile() {
    override fun readAst(): S_ModuleDefinition {
        val text = readText()
        return C_Parser.parse(sourcePath, text)
    }

    override fun readText() = file.readText()
}

class C_DiskSourceDir(private val dir: File): C_SourceDir() {
    override fun file(path: C_SourcePath): C_SourceFile? {
        val pathStr = path.str()
        val file = File(dir, pathStr)
        return if (file.isFile) C_DiskSourceFile(file, path) else null
    }
}

class C_IncludeResource(val innerResolver: C_IncludeResolver, val path: String, val file: C_SourceFile)

class C_IncludeResolver(private val rootDir: C_SourceDir, private val curPath: C_SourcePath = C_SourcePath()) {
    fun resolve(path: String, msgPath: String = path): C_IncludeResource {
        val abs = path.startsWith("/")
        val basePath = if (abs) C_SourcePath() else curPath
        val tailPath = if (abs) path.substring(1) else path

        val incPath = C_SourcePath.parseOpt(tailPath)
        if (incPath == null) {
            throw C_CommonError("include_bad_path:$msgPath", "Invalid path: '$msgPath'")
        }

        val fullPath = C_SourcePath(basePath.parts + incPath.parts)
        val file = resolveFile(rootDir, fullPath)

        val innerResolver = C_IncludeResolver(rootDir, fullPath.parent())
        return C_IncludeResource(innerResolver, fullPath.str(), file)
    }

    companion object {
        fun resolveFile(rootDir: C_SourceDir, fullPath: C_SourcePath): C_SourceFile {
            val file = rootDir.file(fullPath)
            if (file == null) {
                throw C_CommonError("include_not_found:$fullPath", "File not found: '$fullPath'")
            }
            return file
        }
    }
}
