package net.postchain.rell.parser

import org.apache.commons.lang3.StringUtils
import java.io.File
import java.util.regex.Pattern

data class C_IncludePath(val parts: List<String> = listOf()) {
    fun add(path: C_IncludePath) = C_IncludePath(parts + path.parts)
    fun parent() = C_IncludePath(parts.subList(0, parts.size - 1))
    fun str() = parts.joinToString("/")
    override fun toString() = str()
}

abstract class C_IncludeFile {
    abstract fun readAst(): S_ModuleDefinition
    abstract fun readText(): String
}

abstract class C_IncludeDir {
    abstract fun file(path: C_IncludePath): C_IncludeFile?
}

private class C_VirtualIncludeFile(private val path: String, private val text: String): C_IncludeFile() {
    override fun readAst(): S_ModuleDefinition {
        return C_Parser.parse(path, text)
    }

    override fun readText() = text
}

class C_VirtualIncludeDir(private val files: Map<String, String>): C_IncludeDir() {
    override fun file(path: C_IncludePath): C_IncludeFile? {
        val pathStr = path.str()
        val text = files[pathStr]
        return if (text == null) null else C_VirtualIncludeFile(pathStr, text)
    }
}

private class C_DiskIncludeFile(private val file: File): C_IncludeFile() {
    override fun readAst(): S_ModuleDefinition {
        val text = readText()
        return C_Parser.parse(file.path, text)
    }

    override fun readText() = file.readText()
}

class C_DiskIncludeDir(private val dir: File): C_IncludeDir() {
    override fun file(path: C_IncludePath): C_IncludeFile? {
        val pathStr = path.str()
        val file = File(dir, pathStr)
        return if (file.isFile) C_DiskIncludeFile(file) else null
    }
}

class C_IncludeResource(val innerResolver: C_IncludeResolver, val path: String, val file: C_IncludeFile)

class C_IncludeResolver(private val rootDir: C_IncludeDir, private val curPath: C_IncludePath = C_IncludePath()) {
    companion object {
        private val FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+([.][A-Za-z0-9_\\-]+)*")
    }

    fun resolve(path: String, msgPath: String = path): C_IncludeResource {
        val abs = path.startsWith("/")
        val basePath = if (abs) C_IncludePath() else curPath
        val tailPath = if (abs) path.substring(1) else path

        val incPath = parsePath(tailPath)
        if (incPath == null) {
            throw C_CommonError("include_bad_path:$msgPath", "Invalid path: '$msgPath'")
        }

        val fullPath = C_IncludePath(basePath.parts + incPath.parts)
        val file = rootDir.file(fullPath)
        if (file == null) {
            throw C_CommonError("include_not_found:$fullPath", "File not found: '$fullPath'")
        }

        val innerResolver = C_IncludeResolver(rootDir, fullPath.parent())
        return C_IncludeResource(innerResolver, fullPath.str(), file)
    }

    private fun parsePath(path: String): C_IncludePath? {
        val parts = StringUtils.splitPreserveAllTokens(path, "/").toList()
        if (parts.isEmpty()) return null

        for (part in parts) {
            if (!FILE_NAME_PATTERN.matcher(part).matches()) return null
        }

        return C_IncludePath(parts)
    }
}
