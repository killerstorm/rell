package net.postchain.rell.tools.runcfg

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.rell.Bytes33
import net.postchain.rell.GeneralDir
import net.postchain.rell.PostchainUtils
import net.postchain.rell.RellConfigGen
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.CONFIG_RELL_SOURCES
import net.postchain.rell.parser.C_SourceDir

class RunConfigChainConfigGen private constructor(private val sourceDir: C_SourceDir, private val configDir: GeneralDir) {
    companion object {
        fun generateChainsConfigs(
                sourceDir: C_SourceDir,
                configDir: GeneralDir,
                runConfig: Rcfg_Run,
                extraSigners: Collection<Bytes33>
        ): List<RellPostAppChain> {
            val res = mutableListOf<RellPostAppChain>()
            val generator = RunConfigChainConfigGen(sourceDir, configDir)

            for (chain in runConfig.chains) {
                val resConfigs = mutableMapOf<Long, Gtv>()
                val modules = mutableSetOf<R_ModuleName>()
                for (config in chain.configs) {
                    val (gtv, module) = generator.genChainConfig(chain, config, extraSigners)
                    resConfigs[config.height] = gtv
                    if (module != null) modules.add(module)
                }
                val resChain = RellPostAppChain(chain.name, chain.iid, chain.brid, resConfigs, modules)
                res.add(resChain)
            }

            return res
        }
    }

    private fun genChainConfig(
            chain: Rcfg_Chain,
            config: Rcfg_ChainConfig,
            extraSigners: Collection<Bytes33>
    ): Pair<Gtv, R_ModuleName?> {
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

        if (!extraSigners.isEmpty()) {
            val signersGtv: Gtv = gtv(extraSigners.map { gtv(it.toByteArray()) })
            b.update(signersGtv, "signers")
        }

        if (config.addDependencies && !chain.dependencies.isEmpty()) {
            val depsGtv = gtv(chain.dependencies.map { (k, v) -> gtv(gtv(k), gtv(v.toByteArray())) })
            b.update(depsGtv, "dependencies")
        }

        val gtv = b.build()
        return Pair(gtv, module)
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

        val configGen = RellConfigGen.create(sourceDir, listOf(app.module))
        val sources = configGen.getModuleSources()

        val srcGtv = gtv(
                "modules" to gtv(listOf(gtv(app.module.str()))),
                CONFIG_RELL_SOURCES to gtv(sources.files.mapValues { (_, v) -> gtv(v) })
        )
        b.update(srcGtv, "gtx", "rell")

        if (app.args.isNotEmpty()) {
            val argsGtv = app.args.mapKeys{ (k, _) -> k.str() }.mapValues{ (_, v) -> gtv(v) }
            b.update(gtv(argsGtv), "gtx", "rell", "moduleArgs")
        }

        val gtv = b.build()
        return Pair(gtv, app.module)
    }

    private fun genChainGtv(chainGtv: Rcfg_ChainConfigGtv): Gtv {
        return if (chainGtv.gtv != null) {
            chainGtv.gtv
        } else if (chainGtv.src != null) {
            val xml = configDir.readText(chainGtv.src)
            PostchainUtils.xmlToGtv(xml)
        } else {
            gtv(mapOf())
        }
    }
}
