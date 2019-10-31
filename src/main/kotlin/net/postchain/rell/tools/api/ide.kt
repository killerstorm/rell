package net.postchain.rell.tools.api

import com.fasterxml.jackson.databind.ObjectMapper
import net.postchain.rell.RellConfigGen
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.parser.*
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap

@Suppress("UNUSED")
object IdeApi {
    const val RELL_VERSION = net.postchain.rell.module.RELL_VERSION

    @JvmStatic fun parseSourcePath(s: String): C_SourcePath? {
        return C_SourcePath.parseOpt(s)
    }

    @JvmStatic fun parseModuleName(s: String): R_ModuleName? {
        return R_ModuleName.ofOpt(s)
    }

    @JvmStatic fun getModuleInfo(path: C_SourcePath, ast: S_RellFile): IdeModuleInfo? {
        val (moduleName, directory) = C_ModuleManager.getModuleInfo(path, ast)
        return if (moduleName == null) null else IdeModuleInfo(moduleName, directory)
    }

    @JvmStatic fun getImportedModules(moduleName: R_ModuleName, ast: S_RellFile): List<R_ModuleName> {
        val res = mutableSetOf<R_ModuleName>()
        ast.getImportedModules(moduleName, res)
        return res.toImmList()
    }

    @JvmStatic fun getAppFiles(sourceDir: C_SourceDir, modules: List<R_ModuleName>): Map<String, String> {
        val configGen = RellConfigGen.create(sourceDir, modules)
        val ms = configGen.getModuleSources()
        return ms.files
    }

    @JvmStatic fun buildOutlineTree(b: IdeOutlineTreeBuilder, ast: S_RellFile) {
        ast.ideBuildOutlineTree(b)
    }

    @JvmStatic fun validate(
            sourceDir: C_SourceDir,
            modules: List<R_ModuleName>,
            options: C_CompilerOptions
    ): List<C_Message> {
        val res = C_Compiler.compile(sourceDir, modules, options)
        return res.messages
    }
}

class IdeModuleInfo(
        @JvmField val name: R_ModuleName,
        @JvmField val directory: Boolean
)

class IdeSnippetMessage(
        @JvmField val pos: String,
        @JvmField val type: C_MessageType,
        @JvmField val code: String,
        @JvmField val text: String
) {
    fun serialize(): Any {
        return mapOf(
                "pos" to pos,
                "type" to type.name,
                "code" to code,
                "text" to text
        )
    }

    companion object {
        fun deserialize(obj: Any): IdeSnippetMessage {
            val raw = obj as Map<Any, Any>
            val map = raw.map { (k, v) -> k as String to v as String }.toMap()
            return IdeSnippetMessage(
                    map.getValue("pos"),
                    C_MessageType.valueOf(map.getValue("type")),
                    map.getValue("code"),
                    map.getValue("text")
            )
        }
    }
}

class IdeCodeSnippet(
        files: Map<String, String>,
        modules: List<String>,
        @JvmField val options: C_CompilerOptions,
        messages: List<IdeSnippetMessage>,
        parsing: Map<String, List<IdeSnippetMessage>>
) {
    @JvmField val files = files.toImmMap()
    @JvmField val modules = modules.toImmList()
    @JvmField val messages = messages.toImmList()
    @JvmField val parsing = parsing.toImmMap()

    fun serialize(): String {
        val opts = mutableMapOf<String, Any>()
        opts["gtv"] = options.gtv
        opts["deprecatedError"] = options.deprecatedError

        val msgs = messages.map { it.serialize() }

        val prsing = parsing.mapValues { (_, v) -> v.map { it.serialize() } }

        val obj = mapOf(
                "files" to files,
                "modules" to modules,
                "options" to opts,
                "messages" to msgs,
                "parsing" to prsing
        )

        val mapper = ObjectMapper()
        val res = mapper.writeValueAsString(obj)
        return res
    }

    companion object {
        @JvmStatic fun deserialize(s: String): IdeCodeSnippet {
            val mapper = ObjectMapper()
            val any = mapper.readValue(s, Any::class.java)

            val obj = any as Map<String, Any>

            val filesRaw = obj.getValue("files") as Map<Any, Any>
            val files = filesRaw.map { (k, v) -> k as String to v as String }.toMap()

            val modulesRaw = obj.getValue("modules") as List<Any>
            val modules = modulesRaw.map { it as String }

            val optionsRaw = obj.getValue("options") as Map<Any, Any>
            val optionsMap = optionsRaw.map { (k, v) -> k as String to v }.toMap()
            val options = C_CompilerOptions(
                    gtv = optionsMap.getValue("gtv") as Boolean,
                    deprecatedError = optionsMap.getValue("deprecatedError") as Boolean
            )

            val messagesRaw = obj.getValue("messages") as List<Any>
            val messages = messagesRaw.map { IdeSnippetMessage.deserialize(it) }

            val parsingRaw = obj.getValue("parsing") as Map<Any, Any>
            val parsing = parsingRaw.map { (k, v) ->
                k as String to (v as List<Any>).map { IdeSnippetMessage.deserialize(it) }
            }.toMap()

            return IdeCodeSnippet(files, modules, options, messages, parsing)
        }
    }
}
