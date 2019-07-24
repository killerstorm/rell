package net.postchain.rell

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvString
import net.postchain.gtv.GtvType
import net.postchain.rell.module.CONFIG_RELL_FILES
import net.postchain.rell.module.CONFIG_RELL_SOURCES
import net.postchain.rell.parser.*
import picocli.CommandLine
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RellCfgArgs()) {
        main0(it)
    }
}

private fun main0(args: RellCfgArgs) {
    val (sourceDir, sourcePath) = RellCliUtils.getSourceDirAndPath(args.sourceDir, args.rellFile)

    val template = if (args.configTemplateFile == null) null else {
        readFile(File(args.configTemplateFile))
    }

    val config = RellConfigGen.makeConfig(sourceDir, sourcePath, template)

    if (args.outputFile != null) {
        val outputFile = File(args.outputFile)
        verifyCfg(outputFile.absoluteFile.parentFile.isDirectory, "Path not found: $outputFile")
        FileOutputStream(outputFile).use {
            writeResult(args, it, config)
        }
    } else {
        writeResult(args, System.out, config)
    }
}

private fun writeResult(args: RellCfgArgs, os: OutputStream, config: Gtv) {
    val bytes = if (args.binaryOutput) {
        PostchainUtils.gtvToBytes(config)
    } else {
        val text = RellConfigGen.configToText(config)
        text.toByteArray()
    }
    os.write(bytes)
}

private fun readFile(file: File): String {
    verifyCfg(file.isFile, "File not found: ${file.path}")
    return file.readText()
}

private fun verifyCfg(b: Boolean, msg: String) {
    if (!b) {
        throw RellCliErr(msg)
    }
}

object RellConfigGen {
    fun makeConfig(sourceDir: C_SourceDir, mainFile: C_SourcePath, template: String?): Gtv {
        val gtvTemplate = getConfigTemplate(template)
        return makeConfig(sourceDir, mainFile, gtvTemplate)
    }

    fun makeConfig(sourceDir: C_SourceDir, mainFile: C_SourcePath, template: Gtv): Gtv {
        val sourceModule = getModuleSources(sourceDir, mainFile)

        val mutableConfig = GtvNode.create(null, template)
        injectRellFiles(mutableConfig, sourceModule.files, sourceModule.mainFile)

        val gtvConfig = mutableConfig.toValue()
        return gtvConfig
    }

    fun getModuleSources(sourceDir: C_SourceDir, mainFile: C_SourcePath): RellModuleSources {
        val mainCode: String

        val included = try {
            mainCode = C_IncludeResolver.resolveFile(sourceDir, mainFile).readText()
            C_Compiler.getIncludedResources(sourceDir, mainFile, transitive = true, fail = true)
        } catch (e: C_Error) {
            throw RellCliErr(e.message!!)
        } catch (e: Exception) {
            throw RellCliErr(e.message ?: "unknown")
        }

        val mainFilePath = mainFile.str()
        val files = mutableMapOf<String, String>()
        files[mainFilePath] = mainCode

        for (include in included) {
            if (include.path !in files) {
                files[include.path] = include.file.readText()
            }
        }

        return RellModuleSources(mainFilePath, files)
    }

    private fun getConfigTemplate(template: String?): Gtv {
        if (template == null) return GtvFactory.gtv(mapOf())

        try {
            return PostchainUtils.xmlToGtv(template)
        } catch (e: Exception) {
            throw RellCliErr("Failed to parse template XML: ${e.message}")
        }
    }

    private fun injectRellFiles(template: GtvNode, files: Map<String, String>, mainFile: String) {
        val rootDict = asDictNode(template)
        val gtxDict = getDictByKey(rootDict, "gtx")
        val rellDict = getDictByKey(gtxDict, "rell")

        rellDict.putString("mainFile", mainFile)

        val sourcesDict = getDictByKey(rellDict, CONFIG_RELL_SOURCES)
        for ((name, source) in files) {
            sourcesDict.putString(name, source)
        }

        rellDict.remove(CONFIG_RELL_FILES)
    }

    private fun getDictByKey(dict: DictGtvNode, key: String): DictGtvNode {
        val node = dict.get(key)
        return if (node == null) {
            dict.putDict(key)
        } else {
            asDictNode(node)
        }
    }

    private fun asDictNode(node: GtvNode): DictGtvNode {
        if (node !is DictGtvNode) {
            val pathStr = if (node.path == null) "<root>" else node.path
            val type = node.type()
            throw RellCliErr("Found $type instead of ${GtvType.DICT} ($pathStr)")
        }
        return node
    }

    fun configToText(gtvConfig: Gtv): String {
        val xml = PostchainUtils.gtvToXml(gtvConfig)
        return xml
    }
}

private sealed class GtvNode(val path: String?) {
    abstract fun type(): GtvType
    abstract fun toValue(): Gtv

    companion object {
        fun subPath(parentPath: String?, key: String) = if (parentPath == null) key else "$parentPath.$key"

        fun create(path: String?, value: Gtv): GtvNode {
            return if (value.type == GtvType.DICT) {
                val map = value.asDict().mapValues { (k, v) -> create(subPath(path, k), v) }
                DictGtvNode(path, LinkedHashMap(map))
            } else {
                TermGtvNode(path, value)
            }
        }
    }
}

private class TermGtvNode(path: String?, private val value: Gtv): GtvNode(path) {
    override fun type() = value.type
    override fun toValue() = value
}

private class DictGtvNode(path: String?, private val map: MutableMap<String, GtvNode>): GtvNode(path) {
    override fun type() = GtvType.DICT
    override fun toValue() = GtvFactory.gtv(map.mapValues { (k, v) -> v.toValue() })

    fun get(key: String) = map[key]

    fun putString(key: String, value: String) {
        val path = subPath(path, key)
        map[key] = TermGtvNode(path, GtvString(value))
    }

    fun putDict(key: String): DictGtvNode {
        val path = subPath(path, key)
        val dict = DictGtvNode(path, mutableMapOf())
        map[key] = dict
        return dict
    }

    fun remove(key: String) {
        map.remove(key)
    }
}

@CommandLine.Command(name = "RellConfigGen", description = ["Generates Rell Postchain configuration"])
private class RellCfgArgs {
    @CommandLine.Parameters(index = "0", paramLabel = "RELL_FILE", description = ["Rell main file"])
    var rellFile: String = ""

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "OUTPUT_FILE", description = ["Output configuration file"])
    var outputFile: String? = null

    @CommandLine.Option(names = ["--source-dir"], paramLabel =  "SOURCE_DIR",
            description =  ["Source directory used to resolve absolute include paths (default: the directory of the Rell file)"])
    var sourceDir: String? = null

    @CommandLine.Option(names = ["--template"], paramLabel =  "TEMPLATE_FILE", description =  ["Configuration template file"])
    var configTemplateFile: String? = null

    @CommandLine.Option(names = ["--binary-output"], paramLabel = "BINARY_OUTPUT", description = ["Write output as binary"])
    var binaryOutput: Boolean = false
}
