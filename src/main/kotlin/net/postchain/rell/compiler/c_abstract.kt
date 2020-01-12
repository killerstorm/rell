package net.postchain.rell.compiler

import net.postchain.rell.model.R_Function
import net.postchain.rell.model.R_FunctionBody
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.toImmList
import net.postchain.rell.toImmSet

class C_AbstractDescriptor(private val fnPos: S_Pos, private val rFunction: R_Function, val hasDefaultBody: Boolean) {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_UserFunctionHeader.EMPTY)

    private var override = false
    private var usingDefaultBody = false

    fun header() = headerLate.get()

    fun functionPos() = fnPos
    fun functionName() = rFunction.appLevelName

    fun isUsingDefaultBody(): Boolean {
        check(!usingDefaultBody)
        if (!hasDefaultBody || override) return false
        usingDefaultBody = true
        return true
    }

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate.set(header)
    }

    fun setOverride() {
        check(!override)
        check(!usingDefaultBody)
        override = true
    }

    fun setBody(body: R_FunctionBody) {
        rFunction.setBody(body)
    }
}

class C_OverrideDescriptor(val fnPos: S_Pos) {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, EMPTY_HEADER)
    private var bodySet = false
    private var bind = false

    fun abstract() = headerLate.get().abstract

    fun setAbstract(abstract: C_AbstractDescriptor?) {
        headerLate.set(C_OverrideHeader(abstract))
    }

    fun setBody(body: R_FunctionBody) {
        check(!bodySet)
        bodySet = true
        val abstract = abstract() // Does state check
        if (bind) {
            abstract?.setBody(body)
        }
    }

    fun bind(): C_AbstractDescriptor? {
        check(!bind)
        check(!bodySet)
        val abstract = abstract() // Does state check
        bind = true

        abstract?.setOverride()
        return abstract
    }

    private class C_OverrideHeader(val abstract: C_AbstractDescriptor?)

    companion object {
        private val EMPTY_HEADER = C_OverrideHeader(null)
    }
}

object C_AbstractCompiler {
    fun compile(globalCtx: C_GlobalContext, modules: List<C_Module>) {
        val conflictsProcessor = C_OverrideConflictsProcessor(globalCtx)
        val actualOverrides = conflictsProcessor.processApp(modules)

        val missingOverridesProcessor = C_MissingOverridesProcessor(globalCtx, actualOverrides)
        for (module in modules) {
            missingOverridesProcessor.processModule(module)
        }
    }
}

private class C_OverrideConflictsProcessor(private val globalCtx: C_GlobalContext) {
    private val errorLocations = mutableSetOf<C_OverrideLocation>()
    private val errorOverrides = mutableSetOf<C_OverrideDescriptor>()

    fun processApp(modules: List<C_Module>): Set<C_AbstractDescriptor> {
        val entries = mutableListOf<C_OverrideEntry>()
        for (module in modules) {
            entries.addAll(processModule(module))
        }

        val goodEntries = processConflicts(entries, true)

        val res = mutableSetOf<C_AbstractDescriptor>()
        for (entry in goodEntries) {
            val abstract = entry.override.bind()
            if (abstract != null) res.add(abstract)
        }

        return res
    }

    private fun processModule(module: C_Module): List<C_OverrideEntry> {
        val contents = module.contents()

        val entries = mutableListOf<C_OverrideEntry>()
        for (file in contents.files) {
            entries.addAll(processFile(module, file))
        }

        return processConflicts(entries, false)
    }

    private fun processFile(module: C_Module, file: C_RellFileContents): List<C_OverrideEntry> {
        val entries = collectOverrides(module, file)
        return processConflicts(entries, false)
    }

    private fun collectOverrides(module: C_Module, file: C_RellFileContents): List<C_OverrideEntry> {
        val res = mutableListOf<C_OverrideEntry>()

        val visitedModules = mutableSetOf<C_Module>()
        visitedModules.add(module)

        for (import in file.imports) {
            val sub = collectImportOverrides(import, visitedModules)
            res.addAll(sub)
        }

        collectDirectOverrides(null, module, file, res)

        return res.toImmList()
    }

    private fun collectImportOverrides(import: C_ImportDescriptor, visitedModules: MutableSet<C_Module>): List<C_OverrideEntry> {
        val res = mutableListOf<C_OverrideEntry>()

        val allModules = C_AbstractUtils.collectImportedModules(import, visitedModules, false)
        for (subModule in allModules) {
            val contents = subModule.contents()
            for (file in contents.files) {
                collectDirectOverrides(import, subModule, file, res)
            }
        }

        return res.groupBy { it.abstract }.values.map { it[0] }.toImmList()
    }

    private fun collectDirectOverrides(
            import: C_ImportDescriptor?,
            module: C_Module,
            file: C_RellFileContents,
            res: MutableList<C_OverrideEntry>)
    {
        for (override in file.overrides) {
            val abstract = override.abstract()
            if (abstract != null) {
                val location = C_OverrideLocation(import, module, override.fnPos)
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

        globalCtx.error(pos, code, msg)
    }

    private fun locationCode(l: C_OverrideLocation): String {
        val importCode = if (l.import == null) "direct" else "import:${l.import.module.name.str()}"
        val pos = l.fnPos.strLine()
        return "$importCode:${l.fnModule.name.str()}:$pos"
    }

    private fun locationMsg(l: C_OverrideLocation): String? {
        return if (l.import == null) null else otherLocationMsg(l)
    }

    private fun otherLocationMsg(l: C_OverrideLocation): String {
        val pos = l.fnPos.strLine()
        return if (l.import == null) pos else "$pos (via import ${l.import.module.name.str()} at ${l.import.pos.strLine()})"
    }

    private class C_OverrideLocation(val import: C_ImportDescriptor?, val fnModule: C_Module, val fnPos: S_Pos)

    private class C_OverrideEntry(
            val abstract: C_AbstractDescriptor,
            val override: C_OverrideDescriptor,
            val locations: List<C_OverrideLocation>
    )
}

private class C_MissingOverridesProcessor(
        private val globalCtx: C_GlobalContext,
        private val actualOverrides: Set<C_AbstractDescriptor>
) {
    private val errorAbstracts = mutableSetOf<C_AbstractDescriptor>()

    fun processModule(module: C_Module) {
        if (module.header().abstract == null) {
            processNonAbstractModule(module)
        } else if (!globalCtx.compilerOptions.ide) {
            for (file in module.contents().files) {
                processFileExtra(file)
            }
        }
    }

    private fun processNonAbstractModule(module: C_Module) {
        val allOverrides = collectAllOverrides(module)

        val contents = module.contents()
        for (file in contents.files) {
            processFile(file, allOverrides)
        }
    }

    private fun collectAllOverrides(module: C_Module): Set<C_AbstractDescriptor> {
        val res = mutableSetOf<C_AbstractDescriptor>()

        for (file in module.contents().files) {
            res.addAll(file.overrides.map { it.abstract() }.filterNotNull())

            for ((_, impFile) in getImportedFiles(file, false)) {
                res.addAll(impFile.overrides.map { it.abstract() }.filterNotNull())
            }
        }

        return res.toImmSet()
    }

    private fun processFile(file: C_RellFileContents, allOverrides: Set<C_AbstractDescriptor>) {
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
        globalCtx.error(import.pos, code, msg)
        errorAbstracts.add(abstract)
    }

    private fun getImportedFiles(
            file: C_RellFileContents,
            abstractOnly: Boolean
    ): List<Pair<C_ImportDescriptor, C_RellFileContents>> {
        val res = mutableListOf<Pair<C_ImportDescriptor, C_RellFileContents>>()

        val visitedModules = mutableSetOf<C_Module>()
        for (import in file.imports) {
            val modules = C_AbstractUtils.collectImportedModules(import, visitedModules, abstractOnly)
            for (impModule in modules) {
                for (impFile in impModule.contents().files) {
                    res.add(Pair(import, impFile))
                }
            }
        }

        return res
    }

    private fun processFileExtra(file: C_RellFileContents) {
        // Extra safety (not a real case so far).
        for (abstract in file.abstracts) {
            if (!abstract.hasDefaultBody && abstract !in actualOverrides && abstract !in errorAbstracts) {
                val fName = abstract.functionName()
                val fPos = abstract.functionPos()
                globalCtx.error(fPos, "override:missing:[$fName]", "No override for abstract function '$fName'")
            }
        }
    }
}

private object C_AbstractUtils {
    fun collectImportedModules(
            import: C_ImportDescriptor,
            visitedModules: MutableSet<C_Module>,
            abstractOnly: Boolean
    ): List<C_Module> {
        val res = mutableListOf<C_Module>()
        collectImportedModules0(import, visitedModules, abstractOnly, res)
        return res.toImmList()
    }

    private fun collectImportedModules0(
            import: C_ImportDescriptor,
            visitedModules: MutableSet<C_Module>,
            abstractOnly: Boolean,
            res: MutableList<C_Module>
    ) {
        if (!visitedModules.add(import.module)) return
        if (abstractOnly && import.module.header().abstract == null) return

        res.add(import.module)
        val contents = import.module.contents()
        for (file in contents.files) {
            for (subImport in file.imports) {
                collectImportedModules0(subImport, visitedModules, abstractOnly, res)
            }
        }
    }
}
