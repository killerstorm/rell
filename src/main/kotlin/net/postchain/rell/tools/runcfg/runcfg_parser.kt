package net.postchain.rell.tools.runcfg

import net.postchain.gtv.Gtv
import net.postchain.rell.Bytes32
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RELL_VERSION_MODULE_SYSTEM
import net.postchain.rell.toImmMap

object RunConfigParser {
    fun parseConfig(xml: RellXmlElement): Rcfg_Run {
        val root = xml
        root.checkTag("run")
        root.checkNoText()

        val attrs = root.attrs()
        val wipeDb = attrs.getBooleanOpt("wipe-db") ?: false
        attrs.checkNoMore()

        var nodes = false
        var nodeConfig: Rcfg_NodeConfig? = null
        var chains: List<Rcfg_Chain>? = null

        for (elem in root.elems) {
            when (elem.tag) {
                "nodes" -> {
                    elem.check(!nodes) { "nodes specified more than once" }
                    nodes = true
                    nodeConfig = parseNodes(elem)
                }
                "chains" -> {
                    elem.check(chains == null) { "chains specified more than once" }
                    chains = parseChains(elem)
                }
                else -> throw elem.errorTag()
            }
        }

        root.check(nodeConfig != null) { "node configuration not defined" }
        root.check(chains != null && !chains.isEmpty()) { "no chains defined" }

        return Rcfg_Run(nodeConfig!!, chains ?: listOf(), wipeDb = wipeDb)
    }

    private fun parseNodes(nodes: RellXmlElement): Rcfg_NodeConfig {
        nodes.checkNoText()
        nodes.attrs().checkNoMore()

        var config: Rcfg_NodeConfig? = null

        for (elem in nodes.elems) {
            elem.checkTag("config")
            elem.check(config == null) { "node configuration specified more than once" }
            config = parseNodeConfig(elem)
        }

        nodes.check(config != null) { "node configuration not defined" }

        return config!!
    }

    private fun parseNodeConfig(elem: RellXmlElement): Rcfg_NodeConfig? {
        elem.checkNoElems()

        val attrs = elem.attrs()
        val src = attrs.getNoBlankOpt("src")
        val addSigners = attrs.getBooleanOpt("add-signers") ?: true
        attrs.checkNoMore()

        val text = elem.text

        elem.check(src != null || text != null) { "neither src nor text specified" }
        elem.check(src == null || text == null) { "both src and text specified" }

        return Rcfg_NodeConfig(src, text, addSigners)
    }

    private fun parseChains(chains: RellXmlElement): List<Rcfg_Chain> {
        chains.checkNoText()
        chains.attrs().checkNoMore()

        val ctx = ParseChainsCtx()
        val headers = mutableListOf<ParseChainHeader>()
        for (elem in chains.elems) {
            val header = parseChainHeader(ctx, elem)
            headers.add(header)
        }

        val brids = headers.map { Pair(it.name, it.brid) }.toMap()

        val res = mutableListOf<Rcfg_Chain>()
        for (header in headers) {
            val chain = parseChain(header, brids)
            res.add(chain)
        }

        return res
    }

    private fun parseChainHeader(ctx: ParseChainsCtx, chain: RellXmlElement): ParseChainHeader {
        chain.checkTag("chain")
        chain.checkNoText()

        val attrs = chain.attrs()
        val name = attrs.getNoBlank("name")
        val iid = attrs.getType("iid", null) { val v = it.toLong(); check(v >= 0); v }
        val brid = attrs.getType("brid", null) { Bytes32.parse(it) }
        attrs.checkNoMore()

        chain.check(ctx.names.add(name)) { "duplicate chain name: '$name'" }
        chain.check(ctx.iids.add(iid)) { "duplicate chain IID: $iid (name: '$name')" }
        chain.check(ctx.brids.add(brid)) { "duplicate chain BRID: ${brid.toHex()} (name: '$name')" }

        return ParseChainHeader(name, iid, brid, chain)
    }

    private fun parseChain(header: ParseChainHeader, brids: Map<String, Bytes32>): Rcfg_Chain {
        val subCtx = ParseChainCtx(header.name)
        val configs = mutableListOf<Rcfg_ChainConfig>()
        var dependencies: Map<String, Bytes32>? = null

        for (elem in header.elem.elems) {
            when (elem.tag) {
                "config" -> {
                    val config = parseChainConfig(subCtx, elem)
                    configs.add(config)
                }
                "dependencies" -> {
                    elem.check(dependencies == null) { "dependencies specified more than once" }
                    dependencies = parseChainDependencies(elem, brids)
                }
                else -> throw elem.errorTag()
            }
        }

        header.elem.check(configs.any { it.height == 0L }) { "no config for height 0" }

        return Rcfg_Chain(header.name, header.iid, header.brid, configs, dependencies ?: mapOf())
    }

    private fun parseChainConfig(ctx: ParseChainCtx, config: RellXmlElement): Rcfg_ChainConfig {
        config.checkNoText()

        val attrs = config.attrs()
        val height = attrs.getLongOpt("height") { it >= 0 } ?: 0
        val addDependencies = attrs.getBooleanOpt("add-dependencies") ?: true
        attrs.checkNoMore()

        config.check(ctx.configHeights.add(height)) { "duplicate height: $height (chain: '${ctx.name}')" }

        var app: Rcfg_App? = null
        val gtvs = mutableListOf<Rcfg_ChainConfigGtv>()

        for (elem in config.elems) {
            when (elem.tag) {
                "app" -> {
                    elem.check(app == null) { "app specified more than once" }
                    app = parseAppConfig(elem)
                }
                "module" -> throw elem.error("module tag is deprecated since Rell $RELL_VERSION_MODULE_SYSTEM, use app instead")
                "gtv" -> {
                    val gtv = parseChainConfigGtv(elem)
                    gtvs.add(gtv)
                }
                else -> throw elem.errorTag()
            }
        }

        return Rcfg_ChainConfig(height, app, gtvs, addDependencies)
    }

    private fun parseAppConfig(app: RellXmlElement): Rcfg_App {
        app.checkNoText()

        val attrs = app.attrs()
        val moduleStr = attrs.get("module")
        val addDefaults = attrs.getBooleanOpt("add-defaults") ?: true
        attrs.checkNoMore()

        val module = R_ModuleName.ofOpt(moduleStr)
        module ?: throw app.error("Invalid module name: '$moduleStr'")

        var args = mutableMapOf<R_ModuleName, Map<String, Gtv>>()

        for (elem in app.elems) {
            when (elem.tag) {
                "args" -> {
                    val (argsModule, moduleArgs) = parseModuleArgs(elem)
                    elem.check(argsModule !in args) { "args specified more than once for module '$argsModule'" }
                    args[argsModule] = moduleArgs
                }
                else -> throw elem.errorTag()
            }
        }

        return Rcfg_App(module, args.toImmMap(), addDefaults)
    }

    private fun parseModuleArgs(args: RellXmlElement): Pair<R_ModuleName, Map<String, Gtv>> {
        args.checkNoText()

        val attrs = args.attrs()
        val moduleStr = attrs.get("module")
        attrs.checkNoMore()

        val module = R_ModuleName.ofOpt(moduleStr)
        module ?: throw args.error("Invalid module name: '${moduleStr}'")

        val res = mutableMapOf<String, Gtv>()

        for (elem in args.elems) {
            val (key, gtv) = parseModuleArg(elem)
            elem.check(key !in res) { "duplicate module arg key: '$key'" }
            res[key] = gtv
        }

        return Pair(module, res)
    }

    private fun parseModuleArg(elem: RellXmlElement): Pair<String, Gtv> {
        elem.checkTag("arg")
        elem.checkNoText()

        val attrs = elem.attrs()
        val key = attrs.getNoBlank("key")
        attrs.checkNoMore()

        val gtv = RunConfigGtvParser.parseNestedGtv(elem)
        return Pair(key, gtv)
    }

    private fun parseChainConfigGtv(gtvElem: RellXmlElement): Rcfg_ChainConfigGtv {
        gtvElem.checkNoText()

        val attrs = gtvElem.attrs()

        val path = attrs.getTypeOpt("path", null) {
            val list = it.split("/")
            list.forEach { s -> check(!s.isEmpty()) }
            list
        }

        val src = attrs.getNoBlankOpt("src")

        attrs.checkNoMore()

        val gtv = if (gtvElem.elems.isEmpty()) null else RunConfigGtvParser.parseNestedGtv(gtvElem)

        gtvElem.check(src != null || gtv != null) { "neither 'src' nor nested element specified" }
        gtvElem.check(src == null || gtv == null) { "both 'src' and nested element specified" }

        return Rcfg_ChainConfigGtv(path ?: listOf(), src, gtv)
    }

    private fun parseChainDependencies(deps: RellXmlElement, brids: Map<String, Bytes32>): Map<String, Bytes32> {
        deps.checkNoText()
        deps.attrs().checkNoMore()

        val res = mutableMapOf<String, Bytes32>()

        for (elem in deps.elems) {
            elem.checkTag("dependency")
            elem.checkNoText()
            elem.checkNoElems()

            val attrs = elem.attrs()
            val name = attrs.getNoBlank("name")
            val chain = attrs.getNoBlank("chain")
            attrs.checkNoMore()

            elem.check(name !in res) { "duplicate dependency name: '$name'" }

            val brid = brids[chain]
            elem.check(brid != null) { "unknown chain: '$chain' (dependency: '$name')" }

            res[name] = brid!!
        }

        return res
    }

    private class ParseChainHeader(val name: String, val iid: Long, val brid: Bytes32, val elem: RellXmlElement)

    private class ParseChainsCtx {
        val names = mutableSetOf<String>()
        val iids = mutableSetOf<Long>()
        val brids = mutableSetOf<Bytes32>()
    }

    private class ParseChainCtx(val name: String) {
        val configHeights = mutableSetOf<Long>()
    }
}
