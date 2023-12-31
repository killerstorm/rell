/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.utils.C_RFullNamePath
import net.postchain.rell.base.lmodel.L_Module
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.utils.doc.*
import net.postchain.rell.base.utils.futures.FcManager
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

class Ld_ModuleDslImpl private constructor(
    private val moduleName: R_ModuleName,
    private val nsBuilder: Ld_NamespaceBuilder,
): Ld_ModuleDsl, Ld_NamespaceDsl by Ld_NamespaceDslImpl(nsBuilder) {
    private val imports = mutableMapOf<R_ModuleName, L_Module>()
    private val allImports = mutableMapOf<R_ModuleName, L_Module>()

    private var finished = false

    override fun imports(module: L_Module) {
        check(!finished)

        checkImport(module)

        for (module2 in module.allImports) {
            checkImport(module2)
        }

        if (module.moduleName !in imports) {
            imports[module.moduleName] = module
        }

        if (module.moduleName !in allImports) {
            allImports[module.moduleName] = module
        }

        for (module2 in module.allImports) {
            if (module2.moduleName !in allImports) {
                allImports[module2.moduleName] = module2
            }
        }
    }

    private fun checkImport(module: L_Module) {
        val modName = module.moduleName
        val oldModule = allImports[modName]
        Ld_Exception.check(oldModule == null || oldModule === module) {
            "import_module_conflict:$modName" to "Different imported modules with same name: [$modName]"
        }
    }

    private fun build(): L_Module {
        check(!finished)
        finished = true

        val ns = nsBuilder.build()
        val resImports = imports.toImmMap()

        val fcManager = FcManager.create()

        val finCtxP = fcManager.promise<Ld_NamespaceFinishContext>()

        val nsF = fcManager.future().delegate {
            val modCtx = Ld_ModuleContext(moduleName, fcManager, finCtxP.future())
            val nsCtx = Ld_NamespaceContext(modCtx, C_RFullNamePath.of(moduleName))
            val nsF = ns.process(nsCtx)

            val finCtx = modCtx.finish(resImports)
            finCtxP.setResult(finCtx)
            nsF
        }

        fcManager.finish()

        val lNs = nsF.getResult()

        val doc = DocSymbol(
            kind = DocSymbolKind.MODULE,
            symbolName = DocSymbolName.module(moduleName.str()),
            mountName = null,
            declaration = DocDeclaration_Module(DocModifiers.NONE),
            comment = null,
        )

        return L_Module(
            moduleName = moduleName,
            namespace = lNs,
            allImports = allImports.values.sortedBy { it.moduleName }.toImmList(),
            docSymbol = doc,
        )
    }

    companion object {
        fun make(name: String, block: Ld_ModuleDsl.() -> Unit): L_Module {
            val rModuleName = R_ModuleName.of(name)
            val nsBuilder = Ld_NamespaceBuilder()
            val dslBuilder = Ld_ModuleDslImpl(rModuleName, nsBuilder)
            block(dslBuilder)
            return dslBuilder.build()
        }
    }
}
