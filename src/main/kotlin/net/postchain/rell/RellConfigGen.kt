package net.postchain.rell

import net.postchain.gtx.DictGTXValue
import net.postchain.gtx.GTXValue
import net.postchain.gtx.GTXValueType
import net.postchain.gtx.StringGTXValue
import net.postchain.gtx.gtxml.GTXMLValueEncoder
import net.postchain.gtx.gtxml.GTXMLValueParser
import net.postchain.rell.module.CONFIG_RELL_FILES
import net.postchain.rell.module.CONFIG_RELL_SOURCES
import net.postchain.rell.parser.C_DiskIncludeDir
import net.postchain.rell.parser.C_Error
import net.postchain.rell.parser.C_IncludeResolver
import net.postchain.rell.parser.C_Parser
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
    val resolver = C_IncludeResolver(C_DiskIncludeDir(rellFile.absoluteFile.parentFile))

    val template = if (args.configTemplateFile == null) null else {
        readFile(File(args.configTemplateFile))
    }

    val config = makeRellPostchainConfig(resolver, rellFileName, template, true)

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

fun makeRellPostchainConfig(resolver: C_IncludeResolver, mainFile: String, template: String?, pretty: Boolean): String {
    val files = discoverBundleFiles(resolver, mainFile)

    val gtxTemplate = getConfigTemplate(template)

    val mutableConfig = GtxNode.create(null, gtxTemplate)
    injectRellFiles(mutableConfig, files, mainFile, pretty)

    val gtxConfig = mutableConfig.toValue()
    val config = generateConfigText(gtxConfig)
    return config
}

private fun discoverBundleFiles(resolver: C_IncludeResolver, mainFile: String): Map<String, String> {
    val mainCode = try {
        val resource = resolver.resolve(mainFile)
        resource.file.readText()
    } catch (e: Exception) {
        throw RellCfgErr(e.message ?: "unknown")
    }

    val includes = try {
        val ast = C_Parser.parse(mainFile, mainCode)
        ast.getIncludes(mainFile, resolver, nested = true, fail = true)
    } catch (e: C_Error) {
        throw RellCfgErr(e.message!!)
    }

    val files = mutableMapOf<String, String>()
    files[mainFile] = mainCode

    for (include in includes) {
        if (include.path !in files) {
            files[include.path] = include.file.readText()
        }
    }

    return files
}

private fun getConfigTemplate(template: String?): GTXValue {
    if (template == null) return DictGTXValue(mapOf())

    try {
        return GTXMLValueParser.parseGTXMLValue(template)
    } catch (e: Exception) {
        throw RellCfgErr("Failed to parse template XML: ${e.message}")
    }
}

private fun injectRellFiles(template: GtxNode, files: Map<String, String>, mainFile: String, pretty: Boolean) {
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

private fun getDictByKey(dict: DictGtxNode, key: String): DictGtxNode {
    val node = dict.get(key)
    return if (node == null) {
        dict.putDict(key)
    } else {
        asDictNode(node)
    }
}

private fun asDictNode(node: GtxNode): DictGtxNode {
    if (node !is DictGtxNode) {
        val pathStr = if (node.path == null) "<root>" else node.path
        val type = node.type()
        throw RellCfgErr("Found $type instead of ${GTXValueType.DICT} ($pathStr)")
    }
    return node
}

private fun generateConfigText(gtxConfig: GTXValue): String {
    val xml = GTXMLValueEncoder.encodeXMLGTXValue(gtxConfig)
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

private sealed class GtxNode(val path: String?) {
    abstract fun type(): GTXValueType
    abstract fun toValue(): GTXValue

    companion object {
        fun subPath(parentPath: String?, key: String) = if (parentPath == null) key else "$parentPath.$key"

        fun create(path: String?, value: GTXValue): GtxNode {
            return if (value.type == GTXValueType.DICT) {
                val map = value.asDict().mapValues { (k, v) -> create(subPath(path, k), v) }
                DictGtxNode(path, LinkedHashMap(map))
            } else {
                TermGtxNode(path, value)
            }
        }
    }
}

private class TermGtxNode(path: String?, private val value: GTXValue): GtxNode(path) {
    override fun type() = value.type
    override fun toValue() = value
}

private class DictGtxNode(path: String?, private val map: MutableMap<String, GtxNode>): GtxNode(path) {
    override fun type() = GTXValueType.DICT
    override fun toValue() = DictGTXValue(map.mapValues { (k, v) -> v.toValue() })

    fun get(key: String) = map[key]

    fun putString(key: String, value: String) {
        val path = subPath(path, key)
        map[key] = TermGtxNode(path, StringGTXValue(value))
    }

    fun putDict(key: String): DictGtxNode {
        val path = subPath(path, key)
        val dict = DictGtxNode(path, mutableMapOf())
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
