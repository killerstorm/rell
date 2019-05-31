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
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val argsEx = parseArgs(args)
    try {
        main0(argsEx)
    } catch (e: RellCfgErr) {
        System.err.println("ERROR: ${e.message}")
        exitProcess(2)
    }
}

private fun main0(args: RellCfgArgs) {
    val rellFile = File(args.rellFile)
    val rellFileName = rellFile.name
    val sourceDir = C_DiskSourceDir(rellFile.absoluteFile.parentFile)
    val sourcePath = C_SourcePath.parse(rellFileName)

    val template = if (args.configTemplateFile == null) null else {
        readFile(File(args.configTemplateFile))
    }

    val config = makeRellPostchainConfig(sourceDir, sourcePath, template, true)

    if (args.outputFile != null) {
        val outputFile = File(args.outputFile)
        verifyCfg(outputFile.absoluteFile.parentFile.isDirectory, "Path not found: $outputFile")
        outputFile.writeText(config)
    } else {
        println(config)
    }
}

private fun readFile(file: File): String {
    verifyCfg(file.isFile, "File not found: ${file.path}")
    return file.readText()
}

fun makeRellPostchainConfig(sourceDir: C_SourceDir, mainFile: C_SourcePath, template: String?, pretty: Boolean): String {
    val files = discoverBundleFiles(sourceDir, mainFile)

    val gtvTemplate = getConfigTemplate(template)

    val mutableConfig = GtvNode.create(null, gtvTemplate)
    injectRellFiles(mutableConfig, files, mainFile.str(), pretty)

    val gtvConfig = mutableConfig.toValue()
    val config = generateConfigText(gtvConfig)
    return config
}

private fun discoverBundleFiles(sourceDir: C_SourceDir, mainFile: C_SourcePath): Map<String, String> {
    val mainCode: String

    val included = try {
        mainCode = C_IncludeResolver.resolveFile(sourceDir, mainFile).readText()
        C_Compiler.getIncludedResources(sourceDir, mainFile, transitive = true, fail = true)
    } catch (e: C_Error) {
        throw RellCfgErr(e.message!!)
    } catch (e: Exception) {
        throw RellCfgErr(e.message ?: "unknown")
    }

    val files = mutableMapOf<String, String>()
    files[mainFile.str()] = mainCode

    for (include in included) {
        if (include.path !in files) {
            files[include.path] = include.file.readText()
        }
    }

    return files
}

private fun getConfigTemplate(template: String?): Gtv {
    if (template == null) return GtvFactory.gtv(mapOf())

    try {
        return PostchainUtils.xmlToGtv(template)
    } catch (e: Exception) {
        throw RellCfgErr("Failed to parse template XML: ${e.message}")
    }
}

private fun injectRellFiles(template: GtvNode, files: Map<String, String>, mainFile: String, pretty: Boolean) {
    val rootDict = asDictNode(template)
    val gtxDict = getDictByKey(rootDict, "gtx")
    val rellDict = getDictByKey(gtxDict, "rell")

    rellDict.putString("mainFile", mainFile)

    val sourcesDict = getDictByKey(rellDict, CONFIG_RELL_SOURCES)
    for ((name, source) in files) {
        val source2 = if (pretty) "\n" + source.trim() + "\n" else source
        sourcesDict.putString(name, source2)
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
        throw RellCfgErr("Found $type instead of ${GtvType.DICT} ($pathStr)")
    }
    return node
}

private fun generateConfigText(gtvConfig: Gtv): String {
    val xml = PostchainUtils.gtvToXml(gtvConfig)
    return xml
}

private fun verifyCfg(b: Boolean, msg: String) {
    if (!b) {
        throw RellCfgErr(msg)
    }
}

private fun parseArgs(args: Array<String>): RellCfgArgs {
    val argsObj = RellCfgArgs()
    val cl = CommandLine(argsObj)
    try {
        cl.parse(*args)
    } catch (e: CommandLine.PicocliException) {
        cl.usageHelpWidth = 1000
        cl.usage(System.err)
        exitProcess(1)
    }
    return argsObj
}

private class RellCfgErr(msg: String): RuntimeException(msg)

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

    @CommandLine.Option(names = ["--template"], paramLabel =  "TEMPLATE_FILE", description =  ["Configuration template file"])
    var configTemplateFile: String? = null
}
