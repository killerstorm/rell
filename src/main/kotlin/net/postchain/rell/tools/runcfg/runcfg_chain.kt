/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.rell.RellConfigGen
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.ConfigConstants
import net.postchain.rell.utils.*

class RunConfigChainConfigGen private constructor(private val cliEnv: RellCliEnv, params: RellRunConfigParams) {
    private val sourceDir = params.sourceDir
    private val configDir = params.configDir
    private val sourceVersion = params.sourceVersion

    companion object {
        fun generateChainsConfigs(
                cliEnv: RellCliEnv,
                params: RellRunConfigParams,
                runConfig: Rcfg_Run,
                replaceSigners: Collection<Bytes33>?
        ): List<RellPostAppChain> {
            val generator = RunConfigChainConfigGen(cliEnv, params)
            val commonTestModules = runConfig.tests.map { it.module }.toSet()

            val res = mutableListOf<RellPostAppChain>()
            val brids = mutableMapOf<Rcfg_Chain, Bytes32>()

            for (runChain in runConfig.chains) {
                val resChain = generator.genChainConfig(runChain, brids, replaceSigners, commonTestModules)
                res.add(resChain)
                brids[runChain] = resChain.brid
            }

            return res.toImmList()
        }
    }

    private fun genChainConfig(
            chain: Rcfg_Chain,
            brids: Map<Rcfg_Chain, Bytes32>,
            replaceSigners: Collection<Bytes33>?,
            commonTestModules: Set<R_ModuleName>
    ): RellPostAppChain {
        val chainTestModules = (commonTestModules + chain.tests.map { it.module }.toSet()).toList()

        val resConfigs = mutableMapOf<Long, RellPostAppChainConfig>()
        val modules = mutableSetOf<R_ModuleName>()

        for (config in chain.configs) {
            val resConfig = genChainConfig0(config, brids, replaceSigners, chainTestModules)
            resConfigs[config.height] = resConfig
            if (resConfig.appModule != null) modules.add(resConfig.appModule)
        }

        val gtvConfigs = resConfigs.mapValues { it.value.gtvConfig }
        val brid = calcChainBrid(gtvConfigs)

        return RellPostAppChain(chain.name, chain.iid, brid, resConfigs, modules)
    }

    private fun calcChainBrid(configs: Map<Long, Gtv>): Bytes32 {
        val config0 = configs.getValue(0)
        return PostchainUtils.calcBlockchainRid(config0)
    }

    private fun genChainConfig0(
            config: Rcfg_ChainConfig,
            brids: Map<Rcfg_Chain, Bytes32>,
            replaceSigners: Collection<Bytes33>?,
            testModules: List<R_ModuleName>
    ): RellPostAppChainConfig {
        val b = RunConfigGtvBuilder()
        var module: R_ModuleName? = null

        if (config.app != null) {
            val (moduleGtv, appModule) = genAppConfig(config.app)
            b.update(moduleGtv)
            module = appModule
        }

        for (chainGtv in config.gtvs) {
            val actualGtv = genChainGtv(chainGtv)
            b.update(actualGtv, *chainGtv.path.toTypedArray())
        }

        val signersGtv = let {
            val elems = (replaceSigners ?: listOf()).map { Rcfg_Gtv.decode(gtv(it.toByteArray())) }
            val merge = if (replaceSigners != null) Rcfg_Gtv_ArrayMerge.REPLACE else Rcfg_Gtv_ArrayMerge.APPEND
            Rcfg_Gtv_Array(elems, merge)
        }
        b.update(signersGtv, "signers")

        if (config.addDependencies && !config.dependencies.isEmpty()) {
            val deps = config.dependencies.map { (k, v) ->
                val depBrid = if (v.chain != null) brids.getValue(v.chain).toByteArray() else v.brid!!.toByteArray()
                gtv(gtv(k), gtv(depBrid))
            }
            val depsGtv = gtv(deps)
            b.update(depsGtv, "dependencies")
        }

        val gtv = b.build()
        return RellPostAppChainConfig(module, testModules, gtv)
    }

    private fun genAppConfig(app: Rcfg_App): Pair<Gtv, R_ModuleName> {
        val b = RunConfigGtvBuilder()

        if (app.addDefaults) {
            b.update(gtv("name" to gtv("net.postchain.base.BaseBlockBuildingStrategy")), "blockstrategy")
            b.update(gtv("net.postchain.gtx.GTXBlockchainConfigurationFactory"), "configurationfactory")

            val modulesGtv = gtv(
                    gtv("net.postchain.rell.module.RellPostchainModuleFactory"),
                    gtv("net.postchain.gtx.StandardOpsGTXModule")
            )
            b.update(modulesGtv, "gtx", "modules")
        }

        val configGen = RellConfigGen.create(cliEnv, sourceDir, listOf(app.module))
        val sources = configGen.getModuleSources()

        val srcGtv = gtv(
                "modules" to gtv(listOf(gtv(app.module.str()))),
                ConfigConstants.RELL_SOURCES_KEY to gtv(sources.files.mapValues { (_, v) -> gtv(v) }),
                ConfigConstants.RELL_VERSION_KEY to gtv(sourceVersion.str())
        )
        b.update(srcGtv, "gtx", "rell")

        if (app.args.isNotEmpty()) {
            val argsGtv = app.args.mapKeys{ (k, _) -> k.str() }.mapValues{ (_, v) -> gtv(v) }
            b.update(gtv(argsGtv), "gtx", "rell", "moduleArgs")
        }

        val gtv = b.build()
        return Pair(gtv, app.module)
    }

    private fun genChainGtv(chainGtv: Rcfg_ChainConfigGtv): Rcfg_Gtv {
        return if (chainGtv.gtv != null) {
            chainGtv.gtv
        } else if (chainGtv.src != null) {
            val text = configDir.readText(chainGtv.src)
            val elem = RellXmlParser(preserveWhitespace = true).parse(chainGtv.src, text)
            return RunConfigGtvParser.parseGtvNode(elem, mergeAllowed = true)
        } else {
            Rcfg_Gtv_Dict(mapOf(), Rcfg_Gtv_DictMerge.KEEP_NEW)
        }
    }
}
