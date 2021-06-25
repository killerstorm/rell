/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.R_FunctionDefinition
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.utils.Nullable
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

class C_AbstractDescriptor(private val fnPos: S_Pos, private val rFunction: R_FunctionDefinition, val hasDefaultBody: Boolean) {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, Nullable<C_UserFunctionHeader>())
    private val overrideBodyLate = C_LateInit(C_CompilerPass.ABSTRACT, Nullable<C_UserFunctionBody>())

    fun functionPos() = fnPos
    fun functionName() = rFunction.appLevelName

    fun header(): C_UserFunctionHeader = headerLate.get().value ?: C_UserFunctionHeader.ERROR

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate.set(Nullable(header))
    }

    fun setOverride(overrideBody: C_UserFunctionBody) {
        overrideBodyLate.set(Nullable(overrideBody))
    }

    fun bind() {
        val overrideBody = overrideBodyLate.get().value
        val header = headerLate.get().value
        val actualBody = overrideBody ?: header?.fnBody
        if (actualBody != null) {
            val rBody = actualBody.compile()
            rFunction.setBody(rBody)
        }
    }
}

class C_OverrideDescriptor(val fnPos: S_Pos) {
    private val abstractLate = C_LateInit(C_CompilerPass.MEMBERS, Nullable<C_AbstractDescriptor>())
    private val bodyLate = C_LateInit(C_CompilerPass.MEMBERS, Nullable<C_UserFunctionBody>())
    private var bind = false

    fun abstract() = abstractLate.get().value

    fun setAbstract(abstract: C_AbstractDescriptor?) {
        abstractLate.set(Nullable(abstract))
    }

    fun setBody(body: C_UserFunctionBody) {
        bodyLate.set(Nullable(body))
    }

    fun bind(): C_AbstractDescriptor? {
        check(!bind)
        bind = true
        val abstract = abstractLate.get().value
        val body = bodyLate.get().value
        if (abstract != null && body != null) {
            abstract.setOverride(body)
        }
        return abstract
    }
}

object C_AbstractCompiler {
    fun compile(msgCtx: C_MessageContext, modules: List<C_ModuleDescriptor>) {
        val conflictsProcessor = C_OverrideConflictsProcessor(msgCtx)
        val actualOverrides = conflictsProcessor.processApp(modules)

        val missingOverridesProcessor = C_MissingOverridesProcessor(msgCtx, actualOverrides)
        for (module in modules) {
            missingOverridesProcessor.processModule(module)
        }
    }
}

private class C_OverrideConflictsProcessor(private val msgCtx: C_MessageContext) {
    private val errorLocations = mutableSetOf<C_OverrideLocation>()
    private val errorOverrides = mutableSetOf<C_OverrideDescriptor>()

    fun processApp(modules: List<C_ModuleDescriptor>): Set<C_AbstractDescriptor> {
        val entries = mutableListOf<C_OverrideEntry>()
        for (module in modules) {
            val importsDescriptor = module.importsDescriptor()
            entries.addAll(processModule(importsDescriptor))
        }

        val goodEntries = processConflicts(entries, true)

        val res = mutableSetOf<C_AbstractDescriptor>()
        for (entry in goodEntries) {
            val abstract = entry.override.bind()
            if (abstract != null) res.add(abstract)
        }

        return res
    }

    private fun processModule(module: C_ModuleImportsDescriptor): List<C_OverrideEntry> {
        val entries = mutableListOf<C_OverrideEntry>()
        for (file in module.files) {
            entries.addAll(processFile(module, file))
        }
        return processConflicts(entries, false)
    }

    private fun processFile(module: C_ModuleImportsDescriptor, file: C_FileImportsDescriptor): List<C_OverrideEntry> {
        val entries = collectOverrides(module, file)
        return processConflicts(entries, false)
    }

    private fun collectOverrides(module: C_ModuleImportsDescriptor, file: C_FileImportsDescriptor): List<C_OverrideEntry> {
        val res = mutableListOf<C_OverrideEntry>()

        val visitedModules = mutableSetOf<C_ContainerKey>()
        visitedModules.add(module.key)

        for (import in file.imports) {
            val sub = collectImportOverrides(import, visitedModules)
            res.addAll(sub)
        }

        collectDirectOverrides(null, module, file, res)

        return res.toImmList()
    }

    private fun collectImportOverrides(
            import: C_ImportDescriptor,
            visitedModules: MutableSet<C_ContainerKey>
    ): List<C_OverrideEntry> {
        val res = mutableListOf<C_OverrideEntry>()

        val allModules = C_AbstractUtils.collectImportedModules(import, visitedModules, false)
        for (subModule in allModules) {
            for (file in subModule.files) {
                collectDirectOverrides(import, subModule, file, res)
            }
        }

        return res.groupBy { it.abstract }.values.map { it[0] }.toImmList()
    }

    private fun collectDirectOverrides(
            import: C_ImportDescriptor?,
            module: C_ModuleImportsDescriptor,
            file: C_FileImportsDescriptor,
            res: MutableList<C_OverrideEntry>)
    {
        for (override in file.overrides) {
            val abstract = override.abstract()
            if (abstract != null) {
                val location = C_OverrideLocation(import, module.name, override.fnPos)
                res.add(C_OverrideEntry(abstract, override, listOf(location)))
            }
        }
    }

    private fun processConflicts(entries: List<C_OverrideEntry>, appLevel: Boolean): List<C_OverrideEntry> {
        val grouped = entries.groupBy { Pair(it.abstract, it.override) }
                .map { (k, v) -> C_OverrideEntry(k.first, k.second, v.flatMap { it.locations }) }
                .groupBy { it.abstract }

        val res = mutableListOf<C_OverrideEntry>()

        for (entries in grouped.values) {
            if (entries.size >= 2) {
                for (entry in entries) {
                    if (!errorOverrides.add(entry.override) && appLevel) continue
                    val otherEntry = entries.first { it !== entry }
                    processConflict(entry, otherEntry)
                }
            }
            res.add(entries[0])
        }

        return res
    }

    private fun processConflict(entry: C_OverrideEntry, otherEntry: C_OverrideEntry) {
        val otherLocation = otherEntry.locations[0]
        for (location in entry.locations) {
            processConflictLocation(entry.abstract, location, otherLocation)
        }
    }

    private fun processConflictLocation(
            abstract: C_AbstractDescriptor,
            location: C_OverrideLocation,
            otherLocation: C_OverrideLocation
    ) {
        if (!errorLocations.add(location)) return
        val pos = location.import?.pos ?: location.fnPos

        val locCode1 = locationCode(location)
        val locCode2 = locationCode(otherLocation)
        val fName = abstract.functionName()
        val code = "override:conflict:[$fName]:[$locCode1]:[$locCode2]"

        val locMsg1 = locationMsg(location)
        val locMsg2 = otherLocationMsg(otherLocation)
        val baseMsg = "Override conflict: override for function '$fName' defined"
        val msg = if (locMsg1 == null) "$baseMsg at $locMsg2" else "$baseMsg at $locMsg1 and at $locMsg2"

        msgCtx.error(pos, code, msg)
    }

    private fun locationCode(l: C_OverrideLocation): String {
        val importCode = if (l.import == null) "direct" else "import:${l.import.module.name.str()}"
        val pos = l.fnPos.strLine()
        return "$importCode:${l.fnModule.str()}:$pos"
    }

    private fun locationMsg(l: C_OverrideLocation): String? {
        return if (l.import == null) null else otherLocationMsg(l)
    }

    private fun otherLocationMsg(l: C_OverrideLocation): String {
        val pos = l.fnPos.strLine()
        return if (l.import == null) pos else "$pos (via import ${l.import.module.name.str()} at ${l.import.pos.strLine()})"
    }

    private class C_OverrideLocation(val import: C_ImportDescriptor?, val fnModule: R_ModuleName, val fnPos: S_Pos)

    private class C_OverrideEntry(
            val abstract: C_AbstractDescriptor,
            val override: C_OverrideDescriptor,
            val locations: List<C_OverrideLocation>
    )
}

private class C_MissingOverridesProcessor(
        private val msgCtx: C_MessageContext,
        private val actualOverrides: Set<C_AbstractDescriptor>
) {
    private val globalCtx = msgCtx.globalCtx
    private val errorAbstracts = mutableSetOf<C_AbstractDescriptor>()

    fun processModule(module: C_ModuleDescriptor) {
        if (!module.header.abstract) {
            processNonAbstractModule(module)
        } else if (!globalCtx.compilerOptions.ide) {
            for (file in module.importsDescriptor().files) {
                processFileExtra(file)
            }
        }
    }

    private fun processNonAbstractModule(module: C_ModuleDescriptor) {
        val allOverrides = collectAllOverrides(module)

        val importsDesc = module.importsDescriptor()
        for (file in importsDesc.files) {
            processFile(file, allOverrides)
        }
    }

    private fun collectAllOverrides(module: C_ModuleDescriptor): Set<C_AbstractDescriptor> {
        val res = mutableSetOf<C_AbstractDescriptor>()

        for (file in module.importsDescriptor().files) {
            res.addAll(file.overrides.map { it.abstract() }.filterNotNull())

            for ((_, impFile) in getImportedFiles(file, false)) {
                res.addAll(impFile.overrides.map { it.abstract() }.filterNotNull())
            }
        }

        return res.toImmSet()
    }

    private fun processFile(file: C_FileImportsDescriptor, allOverrides: Set<C_AbstractDescriptor>) {
        for ((import, impFile) in getImportedFiles(file, true)) {
            for (abstract in impFile.abstracts) {
                if (!abstract.hasDefaultBody && abstract !in allOverrides) {
                    processMissingOverride(import, abstract)
                }
            }
        }
    }

    private fun processMissingOverride(import: C_ImportDescriptor, abstract: C_AbstractDescriptor) {
        val fName = abstract.functionName()
        val fPos = abstract.functionPos()
        val code = "override:missing:[$fName]:[${fPos.strLine()}]"
        val msg = "No override for abstract function '$fName' (defined at ${fPos.strLine()})"
        msgCtx.error(import.pos, code, msg)
        errorAbstracts.add(abstract)
    }

    private fun getImportedFiles(
            file: C_FileImportsDescriptor,
            abstractOnly: Boolean
    ): List<Pair<C_ImportDescriptor, C_FileImportsDescriptor>> {
        val res = mutableListOf<Pair<C_ImportDescriptor, C_FileImportsDescriptor>>()

        val visitedModules = mutableSetOf<C_ContainerKey>()
        for (import in file.imports) {
            val modules = C_AbstractUtils.collectImportedModules(import, visitedModules, abstractOnly)
            for (impModule in modules) {
                for (impFile in impModule.files) {
                    res.add(Pair(import, impFile))
                }
            }
        }

        return res
    }

    private fun processFileExtra(file: C_FileImportsDescriptor) {
        // Extra safety (not a real case so far).
        for (abstract in file.abstracts) {
            if (!abstract.hasDefaultBody && abstract !in actualOverrides && abstract !in errorAbstracts) {
                val fName = abstract.functionName()
                val fPos = abstract.functionPos()
                msgCtx.error(fPos, "override:missing:[$fName]", "No override for abstract function '$fName'")
            }
        }
    }
}

private object C_AbstractUtils {
    fun collectImportedModules(
            import: C_ImportDescriptor,
            visitedModules: MutableSet<C_ContainerKey>,
            abstractOnly: Boolean
    ): List<C_ModuleImportsDescriptor> {
        val res = mutableListOf<C_ModuleImportsDescriptor>()
        collectImportedModules0(import, visitedModules, abstractOnly, res)
        return res.toImmList()
    }

    private fun collectImportedModules0(
            import: C_ImportDescriptor,
            visitedModules: MutableSet<C_ContainerKey>,
            abstractOnly: Boolean,
            res: MutableList<C_ModuleImportsDescriptor>
    ) {
        if (!visitedModules.add(import.module.containerKey)) return
        if (abstractOnly && !import.module.header.abstract) return

        res.add(import.module.importsDescriptor())
        val importsDesc = import.module.importsDescriptor()
        for (file in importsDesc.files) {
            for (subImport in file.imports) {
                collectImportedModules0(subImport, visitedModules, abstractOnly, res)
            }
        }
    }
}
