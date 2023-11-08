/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.utils.C_RNamePath
import net.postchain.rell.base.lmodel.L_Module
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.utils.doc.*
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

@RellLibDsl
interface Ld_ModuleDsl: Ld_NamespaceDsl {
    fun imports(module: L_Module)

    companion object {
        fun make(name: String, block: Ld_ModuleDsl.() -> Unit): L_Module {
            val rModuleName = R_ModuleName.of(name)
            val nsBuilder = Ld_NamespaceBuilder()
            val dslBuilder = Ld_ModuleDslBuilder(rModuleName, nsBuilder)
            block(dslBuilder)
            return dslBuilder.build()
        }
    }
}

private class Ld_ModuleDslBuilder(
    private val moduleName: R_ModuleName,
    private val nsBuilder: Ld_NamespaceBuilder,
): Ld_ModuleDsl, Ld_NamespaceDsl by Ld_NamespaceDslBuilder(nsBuilder) {
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

    fun build(): L_Module {
        check(!finished)
        finished = true

        val ns = nsBuilder.build()

        val declareCtx = Ld_DeclareContext(Ld_DeclareTables(moduleName), C_RNamePath.EMPTY)
        val nsDeclaration = ns.declare(declareCtx)

        val finishCtx = declareCtx.finish(imports.toImmMap())
        val lNs = nsDeclaration.finish(finishCtx)

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
}
