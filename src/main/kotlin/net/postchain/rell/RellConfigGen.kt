/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvString
import net.postchain.gtv.GtvType
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.CONFIG_RELL_FILES
import net.postchain.rell.module.CONFIG_RELL_SOURCES
import net.postchain.rell.compiler.C_Error
import net.postchain.rell.compiler.C_SourceDir
import net.postchain.rell.compiler.C_SourcePath
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
    val target = RellCliUtils.getTarget(args.sourceDir, args.module)

    val template = if (args.configTemplateFile == null) null else {
        readFile(File(args.configTemplateFile))
    }

    val configGen = RellConfigGen.create(target)
    val config = configGen.makeConfig(template)

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

class RellConfigGen(
        private val sourceDir: C_SourceDir,
        private val modules: List<R_ModuleName>,
        private val moduleFiles: List<C_SourcePath>
) {
    fun makeConfig(templateXml: String?): Gtv {
        val template = getConfigTemplate(templateXml)
        return makeConfig(template)
    }

    fun makeConfig(template: Gtv): Gtv {
        val sources = getModuleSources()

        val mutableConfig = GtvNode.create(null, template)
        injectRellFiles(mutableConfig, sources.files, sources.modules)

        val gtvConfig = mutableConfig.toValue()
        return gtvConfig
    }

    private fun getConfigTemplate(template: String?): Gtv {
        if (template == null) return GtvFactory.gtv(mapOf())

        try {
            return PostchainUtils.xmlToGtv(template)
        } catch (e: Exception) {
            throw RellCliErr("Failed to parse template XML: ${e.message}")
        }
    }

    fun getModuleSources(): RellModuleSources {
        val files = mutableMapOf<String, String>()

        try {
            for (path in moduleFiles) {
                val file = sourceDir.file(path)
                if (file == null) throw RellCliErr("File not found: $path")
                val text = file.readText()
                files[path.str()] = text
            }
        } catch (e: C_Error) {
            throw RellCliErr(e.message!!)
        } catch (e: Exception) {
            throw RellCliErr(e.message ?: "unknown")
        }

        val modules2 = modules.map { it.str() }

        return RellModuleSources(modules2, files)
    }

    private fun injectRellFiles(template: GtvNode, files: Map<String, String>, modules: List<String>) {
        val rootDict = asDictNode(template)
        val gtxDict = getDictByKey(rootDict, "gtx")
        val rellDict = getDictByKey(gtxDict, "rell")

        val modulesArray = getArrayByKey(rellDict, "modules")
        for (module in modules) {
            if (!modulesArray.contains(module)) {
                modulesArray.add(GtvFactory.gtv(module))
            }
        }

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

    private fun getArrayByKey(dict: DictGtvNode, key: String): ArrayGtvNode {
        val node = dict.get(key)
        return if (node == null) {
            dict.putArray(key)
        } else {
            asArrayNode(node)
        }
    }

    private fun asDictNode(node: GtvNode): DictGtvNode {
        return node as? DictGtvNode ?: throw nodeTypeErr(node, GtvType.DICT)
    }

    private fun asArrayNode(node: GtvNode): ArrayGtvNode {
        return node as? ArrayGtvNode ?: throw nodeTypeErr(node, GtvType.ARRAY)
    }

    private fun nodeTypeErr(node: GtvNode, expected: GtvType): RuntimeException {
        val pathStr = if (node.path == null) "<root>" else node.path
        val type = node.type()
        return RellCliErr("Found $type instead of ${expected} ($pathStr)")
    }

    companion object {
        fun create(target: RellCliTarget): RellConfigGen {
            return create(target.sourceDir, target.modules)
        }

        fun create(sourceDir: C_SourceDir, modules: List<R_ModuleName>): RellConfigGen {
            val cRes = RellCliUtils.compile(sourceDir, modules, true)
            return RellConfigGen(sourceDir, modules, cRes.files)
        }

        fun configToText(gtvConfig: Gtv): String {
            val xml = PostchainUtils.gtvToXml(gtvConfig)
            return xml
        }
    }
}

private sealed class GtvNode(val path: String?) {
    abstract fun type(): GtvType
    abstract fun toValue(): Gtv

    companion object {
        fun subPath(parentPath: String?, key: String) = if (parentPath == null) key else "$parentPath.$key"
        fun subPath(parentPath: String?, index: Int) = if (parentPath == null) "[$index]" else "$parentPath[$index]"

        fun create(path: String?, value: Gtv): GtvNode {
            return if (value.type == GtvType.DICT) {
                val map = value.asDict().mapValues { (k, v) -> create(subPath(path, k), v) }
                DictGtvNode(path, map)
            } else if (value.type == GtvType.ARRAY) {
                val array = value.asArray().mapIndexed { i, v -> create(subPath(path, i), v) }
                ArrayGtvNode(path, array)
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

private class DictGtvNode(path: String?, map: Map<String, GtvNode>): GtvNode(path) {
    private val map: MutableMap<String, GtvNode> = LinkedHashMap(map)

    override fun type() = GtvType.DICT
    override fun toValue() = GtvFactory.gtv(map.mapValues { (_, v) -> v.toValue() })

    fun get(key: String) = map[key]

    fun put(key: String, gtv: Gtv) {
        val path = subPath(path, key)
        map[key] = TermGtvNode(path, gtv)
    }

    fun putString(key: String, value: String) {
        put(key, GtvString(value))
    }

    fun putDict(key: String): DictGtvNode {
        val path = subPath(path, key)
        val dict = DictGtvNode(path, mapOf())
        map[key] = dict
        return dict
    }

    fun putArray(key: String): ArrayGtvNode {
        val path = subPath(path, key)
        val array = ArrayGtvNode(path, listOf())
        map[key] = array
        return array
    }

    fun remove(key: String) {
        map.remove(key)
    }
}

private class ArrayGtvNode(path: String?, list: List<GtvNode>): GtvNode(path) {
    private val list: MutableList<GtvNode> = ArrayList(list)

    override fun type() = GtvType.ARRAY
    override fun toValue() = GtvFactory.gtv(list.map { it.toValue() })

    fun contains(s: String): Boolean {
        return list.any { it.type() == GtvType.STRING && it.toValue().asString() == s }
    }

    fun add(gtv: Gtv) {
        val path = subPath(path, list.size)
        list.add(create(path, gtv))
    }
}

@CommandLine.Command(name = "RellConfigGen", description = ["Generates Rell Postchain configuration"])
private class RellCfgArgs: RellBaseCliArgs() {
    @CommandLine.Parameters(index = "0", paramLabel = "MODULE", description = ["Module name"])
    var module: String = ""

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "OUTPUT_FILE", description = ["Output configuration file"])
    var outputFile: String? = null

    @CommandLine.Option(names = ["--template"], paramLabel = "TEMPLATE_FILE", description = ["Configuration template file"])
    var configTemplateFile: String? = null

    @CommandLine.Option(names = ["--binary-output"], paramLabel = "BINARY_OUTPUT", description = ["Write output as binary"])
    var binaryOutput: Boolean = false
}
