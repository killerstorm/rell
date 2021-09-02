/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.R_AppUid
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_QualifiedName
import net.postchain.rell.utils.*
import java.util.*

interface C_NsAsm_BasicAssembler {
    fun futureNs(): Getter<C_Namespace>
}

interface C_NsAsm_ComponentAssembler: C_NsAsm_BasicAssembler {
    fun addDef(name: S_Name, def: C_NsDef)
    fun addNamespace(name: S_Name, merge: Boolean): C_NsAsm_ComponentAssembler
    fun addModuleImport(alias: S_Name, module: C_ModuleKey)
    fun addExactImport(alias: S_Name, module: C_ModuleKey, path: List<S_Name>, name: S_Name)
    fun addWildcardImport(module: C_ModuleKey, path: List<S_Name>)
}

interface C_NsAsm_ModuleAssembler: C_NsAsm_BasicAssembler {
    fun futureDefs(): C_LateGetter<C_ModuleDefs>
    fun addComponent(): C_NsAsm_ComponentAssembler
}

interface C_NsAsm_ReplAssembler: C_NsAsm_BasicAssembler {
    fun addComponent(): C_NsAsm_ComponentAssembler
}

interface C_NsAsm_AppAssembler {
    fun addModule(moduleKey: C_ModuleKey, sysNsProto: C_SysNsProto, exportSysEntities: Boolean): C_NsAsm_ModuleAssembler
    fun addRepl(sysNsProto: C_SysNsProto, linkedModule: C_ModuleKey?, oldState: C_NsAsm_ReplState): C_NsAsm_ReplAssembler
    fun assemble(): C_NsAsm_App

    companion object {
        fun create(
                msgCtx: C_MessageContext,
                appUid: R_AppUid,
                preModules: Map<C_ModuleKey, C_PrecompiledModule>
        ): C_NsAsm_AppAssembler {
            return C_NsAsm_InternalAppAssembler(msgCtx, appUid, preModules)
        }
    }
}

class C_NsAsm_Module(val rawNs: C_NsAsm_RawNamespace, val impNs: C_NsImp_Namespace, val ns: C_Namespace)

class C_NsAsm_App(val newReplState: C_NsAsm_ReplState, modules: Map<C_ModuleKey, C_NsAsm_Module>) {
    val modules = modules.toImmMap()

    companion object { val EMPTY = C_NsAsm_App(C_NsAsm_ReplState.EMPTY, mapOf()) }
}

sealed class C_NsAsm_ReplState {
    companion object { val EMPTY: C_NsAsm_ReplState = C_NsAsm_InternalReplState.EMPTY }
}

class C_NsAsm_WildcardImport(val module: C_ModuleKey, val path: List<S_Name>)

class C_NsAsm_ExactImport(val module: C_ModuleKey, path: List<S_Name>, val name: S_Name) {
    val path = path.toImmList()
}

class C_NsAsm_Namespace(
        defs: Map<String, C_NsAsm_Def>,
        importDefs: Multimap<String, C_NsAsm_Def>,
        wildcardImports: List<C_NsAsm_WildcardImport>
) {
    val defs = defs.toImmMap()
    val importDefs = importDefs.toImmMultimap()
    val wildcardImports = wildcardImports.toImmList()

    companion object { val EMPTY = C_NsAsm_Namespace(mapOf(), immMultimapOf(), listOf()) }
}

sealed class C_NsAsm_Def

class C_NsAsm_Def_Simple(val elem: C_NamespaceElement): C_NsAsm_Def()
class C_NsAsm_Def_ExactImport(val imp: C_NsAsm_ExactImport): C_NsAsm_Def()

sealed class C_NsAsm_Def_Namespace: C_NsAsm_Def() {
    abstract fun ns(): C_NsAsm_Namespace

    companion object {
        fun createDirect(ns: C_NsAsm_Namespace): C_NsAsm_Def = C_NsAsm_Def_DirectNamespace(ns)
        fun createLate(getter: LateGetter<C_NsAsm_Namespace>): C_NsAsm_Def = C_NsAsm_Def_LateNamespace(getter)
    }
}

private class C_NsAsm_Def_DirectNamespace(private val ns: C_NsAsm_Namespace): C_NsAsm_Def_Namespace() {
    override fun ns() = ns
}

private class C_NsAsm_Def_LateNamespace(private val getter: LateGetter<C_NsAsm_Namespace>): C_NsAsm_Def_Namespace() {
    override fun ns() = getter.get()
}

private sealed class C_NsAsm_InternalAssembler {
    private var assembled = false

    protected fun checkCanModify() {
        check(!assembled)
    }

    protected fun finish() {
        check(!assembled)
        assembled = true
    }
}

private sealed class C_NsAsm_InternalBasicAssembler(
        protected val nsLinker: C_NsAsm_NamespaceLinker,
        protected val nsKey: C_NsAsm_NamespaceKey,
        protected val stamp: R_AppUid
): C_NsAsm_InternalAssembler(), C_NsAsm_BasicAssembler {
    private val getter = nsLinker.getLink(nsKey)
    final override fun futureNs() = getter
}

private class C_NsAsm_MutableEntry(
        val name: S_Name,
        val type: C_DeclarationType,
        val def: C_NsAsm_MutableDef,
        val identity: C_NsAsm_Identity,
        val container: C_ContainerKey,
        val stamp: R_AppUid
) {
    fun assemble(): C_NsAsm_RawEntry {
        val def2 = def.assemble()
        return C_NsAsm_RawEntry(name, type, def2, identity, container, stamp)
    }
}

private sealed class C_NsAsm_MutableDef {
    abstract fun assemble(): C_NsAsm_RawDef
}

private class C_NsAsm_MutableDef_Simple(private val def: C_NsAsm_RawDef): C_NsAsm_MutableDef() {
    override fun assemble() = def
}

private class C_NsAsm_MutableDef_Namespace(
        private val asm: C_NsAsm_InternalComponentAssembler,
        private val merge: Boolean
): C_NsAsm_MutableDef() {
    override fun assemble(): C_NsAsm_RawDef {
        val ns = asm.assemble()
        return C_NsAsm_RawDef_Namespace(ns, merge)
    }
}

private class C_NsAsm_InternalComponentAssembler(
        nsLinker: C_NsAsm_NamespaceLinker,
        nsKey: C_NsAsm_NamespaceKey,
        stamp: R_AppUid
): C_NsAsm_InternalBasicAssembler(nsLinker, nsKey, stamp), C_NsAsm_ComponentAssembler {
    private val entries = mutableListOf<C_NsAsm_MutableEntry>()
    private val wildcardImports = mutableListOf<C_NsAsm_RawWildcardImport>()

    private fun addEntry(name: S_Name, type: C_DeclarationType, def: C_NsAsm_MutableDef, identity: C_NsAsm_Identity) {
        checkCanModify()
        entries.add(C_NsAsm_MutableEntry(name, type, def, identity, nsKey.container, stamp))
    }

    final override fun addDef(name: S_Name, def: C_NsDef) {
        val type = def.type()
        val def2 = C_NsAsm_RawDef_Simple(def)
        addEntry(name, type, C_NsAsm_MutableDef_Simple(def2), C_NsAsm_Identity_Def(def2))
    }

    final override fun addNamespace(name: S_Name, merge: Boolean): C_NsAsm_ComponentAssembler {
        checkCanModify()
        val asm = createSubAssembler(name)
        val def = C_NsAsm_MutableDef_Namespace(asm, merge)
        addEntry(name, C_DeclarationType.NAMESPACE, def, C_NsAsm_Identity_Unique())
        return asm
    }

    final override fun addModuleImport(alias: S_Name, module: C_ModuleKey) {
        checkCanModify()
        val asm = createSubAssembler(alias)
        asm.addWildcardImport(module, listOf())
        val ns = asm.assemble()
        val def = C_NsAsm_RawDef_Namespace(ns, false)
        val mutDef = C_NsAsm_MutableDef_Simple(def)
        addEntry(alias, C_DeclarationType.IMPORT, mutDef, C_NsAsm_Identity_ModuleImport(module))
    }

    private fun createSubAssembler(name: S_Name): C_NsAsm_InternalComponentAssembler {
        val subNsKey = nsKey.sub(name.rName)
        return C_NsAsm_InternalComponentAssembler(nsLinker, subNsKey, stamp)
    }

    final override fun addExactImport(alias: S_Name, module: C_ModuleKey, path: List<S_Name>, name: S_Name) {
        val imp = C_NsAsm_ExactImport(module, path, name)
        val def = C_NsAsm_RawDef_ExactImport(imp)
        val mutDef = C_NsAsm_MutableDef_Simple(def)
        addEntry(alias, C_DeclarationType.IMPORT, mutDef, C_NsAsm_Identity_ExactImport(imp))
    }

    final override fun addWildcardImport(module: C_ModuleKey, path: List<S_Name>) {
        checkCanModify()
        wildcardImports.add(C_NsAsm_RawWildcardImport(module, path, stamp))
    }

    fun assemble(): C_NsAsm_RawNamespace {
        finish()
        val resEntries = entries.map { it.assemble() }
        val resWildcardImports = wildcardImports.toImmList()
        return C_NsAsm_RawNamespace(resEntries, resWildcardImports)
    }
}

private class C_NsAsm_InternalReplAssembler(
        nsLinker: C_NsAsm_NamespaceLinker,
        nsKey: C_NsAsm_NamespaceKey,
        private val msgCtx: C_MessageContext,
        private val sysNsProto: C_SysNsProto,
        private val stamp: R_AppUid,
        private val linkedModule: C_ModuleKey?,
        private val oldState: C_NsAsm_InternalReplState
): C_NsAsm_ReplAssembler {
    val comAsm = C_NsAsm_InternalComponentAssembler(nsLinker, nsKey, stamp)
    private var componentAdded = false

    private val nsGetter: Getter<C_Namespace> = if (linkedModule != null) {
        val linkedContainer = C_ModuleContainerKey.of(linkedModule)
        val linkedNsKey = C_NsAsm_NamespaceKey(linkedContainer, R_QualifiedName.EMPTY)
        nsLinker.getLink(linkedNsKey)
    } else {
        { C_Namespace.EMPTY }
    }

    override fun futureNs() = nsGetter

    override fun addComponent(): C_NsAsm_ComponentAssembler {
        check(!componentAdded)
        componentAdded = true
        return comAsm
    }

    fun assemble(modules: Map<C_ModuleKey, C_NsAsm_Module>): C_NsAsm_InternalReplState {
        val conflictsProcessor = C_NsAsm_ConflictsProcessor(msgCtx, sysNsProto, stamp)

        var newRawNs = comAsm.assemble()
        newRawNs = conflictsProcessor.mergeAndProcess(newRawNs)

        var combinedRawNs = C_NsAsm_Utils.concatNamespaces(listOf(oldState.rawNs, newRawNs))
        combinedRawNs = conflictsProcessor.mergeAndProcess(combinedRawNs)

        if (linkedModule != null) {
            val linkedModule = modules[linkedModule]
            if (linkedModule != null) {
                val rawNs = C_NsAsm_Utils.concatNamespaces(listOf(linkedModule.rawNs, combinedRawNs))
                conflictsProcessor.process(rawNs)
            }
        }

        val newNs = newRawNs.assemble(listOf())
        val impModules = modules.mapValues { (_, v) -> v.impNs }.toImmMap()
        val newImpNs = C_NsImp_ImportsProcessor.process(msgCtx, newNs, impModules)
        val combinedImpNs = mergeReplNamespace(oldState.impNs, newImpNs)

        val ns = C_NsRes_ResultMaker.make(combinedImpNs)
        return C_NsAsm_InternalReplState(combinedRawNs, combinedImpNs, ns)
    }

    private fun mergeReplNamespace(oldNs: C_NsImp_Namespace, newNs: C_NsImp_Namespace): C_NsImp_Namespace {
        val directNames = Sets.union(oldNs.directDefs.keys, newNs.directDefs.keys)
        val directDefs = directNames.map {
            val oldDef = oldNs.directDefs[it]
            val newDef = newNs.directDefs[it]
            it to mergeReplDef(oldDef, newDef)
        }.toMap()

        val importDefs = mutableMultimapOf<String, C_NsImp_Def>()
        importDefs.putAll(oldNs.importDefs)
        importDefs.putAll(newNs.importDefs)

        return C_NsImp_Namespace(directDefs, importDefs)
    }

    private fun mergeReplDef(oldDef: C_NsImp_Def?, newDef: C_NsImp_Def?): C_NsImp_Def {
        if (oldDef == null) {
            return newDef!!
        } else if (newDef == null) {
            return oldDef
        }

        if (oldDef is C_NsImp_Def_Namespace && newDef is C_NsImp_Def_Namespace) {
            val oldNs = oldDef.ns()
            val newNs = newDef.ns()
            val resNs = mergeReplNamespace(oldNs, newNs)
            val getter = LateGetter.of(resNs)
            return C_NsImp_Def_Namespace(getter)
        } else {
            return oldDef
        }
    }
}

private class C_NsAsm_RawModule(val rawNs: C_NsAsm_RawNamespace, val ns: C_NsAsm_Namespace)

class C_NsAsm_RawNamespace(
        entries: List<C_NsAsm_RawEntry>,
        wildcardImports: List<C_NsAsm_RawWildcardImport>
) {
    val entries = entries.toImmList()
    val wildcardImports = wildcardImports.toImmList()

    fun addToDefs(b: C_ModuleDefsBuilder) {
        for (entry in entries) {
            entry.def.addToDefs(b)
        }
    }

    fun filterByStamp(stamp: R_AppUid): C_NsAsm_RawNamespace {
        val resEntries = entries.mapNotNull { it.filterByStamp(stamp) }
        val resWildcards = wildcardImports.filter { it.stamp == stamp}
        return C_NsAsm_RawNamespace(resEntries, resWildcards)
    }

    fun assemble(sysEntries: List<C_NsEntry>): C_NsAsm_Namespace {
        val defs = mutableMapOf<String, C_NsAsm_Def>()

        for (entry in sysEntries) {
            val elem = entry.def.toNamespaceElement()
            defs[entry.name] = C_NsAsm_Def_Simple(elem)
        }

        for (entry in entries) {
            val name = entry.name.str
            val def = entry.def.assemble()
            if (name !in defs) defs[name] = def
        }

        val rawImports = makeUnique(wildcardImports)
        val resImports = rawImports.map { it.assemble() }

        return C_NsAsm_Namespace(defs, immMultimapOf(), resImports)
    }

    private fun makeUnique(imports: List<C_NsAsm_RawWildcardImport>): List<C_NsAsm_RawWildcardImport> {
        val list = mutableListOf<C_NsAsm_RawWildcardImport>()
        val set = mutableSetOf<ImportKey>()
        for (imp in imports) {
            val key = ImportKey(imp.module, imp.path.map { it.rName })
            if (set.add(key)) list.add(imp)
        }
        return list
    }

    private data class ImportKey(val module: C_ModuleKey, val path: List<R_Name>)

    companion object { val EMPTY = C_NsAsm_RawNamespace(listOf(), listOf()) }
}

class C_NsAsm_RawEntry(
        val name: S_Name,
        val type: C_DeclarationType,
        val def: C_NsAsm_RawDef,
        val identity: C_NsAsm_Identity,
        val container: C_ContainerKey,
        val stamp: R_AppUid
) {
    fun filterByStamp(targetStamp: R_AppUid): C_NsAsm_RawEntry? {
        if (stamp != targetStamp) {
            return null
        }
        val resDef = def.filterByStamp(targetStamp)
        return if (resDef === def) this else {
            C_NsAsm_RawEntry(name, type, resDef, identity, container, stamp)
        }
    }
}

class C_NsAsm_RawWildcardImport(val module: C_ModuleKey, val path: List<S_Name>, val stamp: R_AppUid) {
    fun assemble() = C_NsAsm_WildcardImport(module, path)
}

sealed class C_NsAsm_RawDef {
    abstract fun filterByStamp(targetStamp: R_AppUid): C_NsAsm_RawDef
    open fun addToDefs(b: C_ModuleDefsBuilder) {}
    abstract fun assemble(): C_NsAsm_Def
}

private class C_NsAsm_RawDef_Simple(private val def: C_NsDef): C_NsAsm_RawDef() {
    override fun filterByStamp(targetStamp: R_AppUid) = this

    override fun addToDefs(b: C_ModuleDefsBuilder) = def.addToDefs(b)

    override fun assemble(): C_NsAsm_Def {
        val elem = def.toNamespaceElement()
        return C_NsAsm_Def_Simple(elem)
    }
}

private class C_NsAsm_RawDef_Namespace(
        val ns: C_NsAsm_RawNamespace,
        val merge: Boolean
): C_NsAsm_RawDef() {
    override fun filterByStamp(targetStamp: R_AppUid): C_NsAsm_RawDef {
        val resNs = ns.filterByStamp(targetStamp)
        return if (resNs === ns) this else C_NsAsm_RawDef_Namespace(resNs, merge)
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) = ns.addToDefs(b)

    override fun assemble(): C_NsAsm_Def {
        val ns2 = ns.assemble(listOf())
        return C_NsAsm_Def_Namespace.createDirect(ns2)
    }
}

private class C_NsAsm_RawDef_ExactImport(private val imp: C_NsAsm_ExactImport): C_NsAsm_RawDef() {
    override fun filterByStamp(targetStamp: R_AppUid) = this
    override fun assemble() = C_NsAsm_Def_ExactImport(imp)
}

sealed class C_NsAsm_Identity

private class C_NsAsm_Identity_Unique: C_NsAsm_Identity()

private class C_NsAsm_Identity_Def(private val def: C_NsAsm_RawDef): C_NsAsm_Identity() {
    override fun equals(other: Any?) = other is C_NsAsm_Identity_Def && def == other.def
    override fun hashCode() = def.hashCode()
}

private class C_NsAsm_Identity_ModuleImport(private val module: C_ModuleKey): C_NsAsm_Identity() {
    override fun equals(other: Any?) = other is C_NsAsm_Identity_ModuleImport && module == other.module
    override fun hashCode() = module.hashCode()
}

private class C_NsAsm_Identity_ExactImport(imp: C_NsAsm_ExactImport): C_NsAsm_Identity() {
    private val module = imp.module
    private val path = imp.path.map { it.str }
    private val name = imp.name.str

    override fun equals(other: Any?) = other is C_NsAsm_Identity_ExactImport
            && module == other.module && path == other.path && name == other.name
    override fun hashCode() = Objects.hash(module, path, name)
}

private class C_NsAsm_InternalModuleAssembler(
        private val msgCtx: C_MessageContext,
        private val sysNsProto: C_SysNsProto,
        private val exportSysEntities: Boolean,
        nsLinker: C_NsAsm_NamespaceLinker,
        nsKey: C_NsAsm_NamespaceKey,
        stamp: R_AppUid
): C_NsAsm_InternalBasicAssembler(nsLinker, nsKey, stamp), C_NsAsm_ModuleAssembler {
    private val modDefsLate = C_LateInit(C_CompilerPass.NAMESPACES, C_ModuleDefs.EMPTY)

    private val components = mutableListOf<C_NsAsm_InternalComponentAssembler>()

    override fun futureDefs() = modDefsLate.getter

    override fun addComponent(): C_NsAsm_ComponentAssembler {
        checkCanModify()
        val asm = C_NsAsm_InternalComponentAssembler(nsLinker, nsKey, stamp)
        components.add(asm)
        return asm
    }

    fun assemble(): C_NsAsm_RawModule {
        finish()

        val sysEntries = if (exportSysEntities) sysNsProto.entities else listOf()
        val conflictsProcessor = C_NsAsm_ConflictsProcessor(msgCtx, sysNsProto, stamp)

        var componentNss = components.map {
            val rawNs = it.assemble()
            conflictsProcessor.mergeAndProcess(rawNs)
        }

        var modNs = C_NsAsm_Utils.concatNamespaces(componentNss)

        val fullModNs = conflictsProcessor.mergeAndProcess(modNs)
        val modDefs = createModuleDefs(fullModNs)
        modDefsLate.set(modDefs)

        modNs = fullModNs.filterByStamp(stamp)
        val resNs = modNs.assemble(sysEntries)

        return C_NsAsm_RawModule(fullModNs, resNs)
    }

    private fun createModuleDefs(ns: C_NsAsm_RawNamespace): C_ModuleDefs {
        val defsBuilder = C_ModuleDefsBuilder()
        ns.addToDefs(defsBuilder)
        return defsBuilder.build()
    }
}

private class C_NsAsm_InternalAppAssembler(
        private val msgCtx: C_MessageContext,
        private val stamp: R_AppUid,
        private val preModules: Map<C_ModuleKey, C_PrecompiledModule>
): C_NsAsm_InternalAssembler(), C_NsAsm_AppAssembler {
    private val nsLinker = C_NsAsm_InternalNamespaceLinker()

    private val moduleAsms = mutableMapOf<C_ModuleKey, C_NsAsm_InternalModuleAssembler>()
    private var replAsm: C_NsAsm_InternalReplAssembler? = null

    override fun addModule(moduleKey: C_ModuleKey, sysNsProto: C_SysNsProto, exportSysEntities: Boolean): C_NsAsm_ModuleAssembler {
        checkCanModify()
        check(moduleKey !in moduleAsms) { moduleKey }
        check(moduleKey !in preModules) { moduleKey }

        val containerKey = C_ModuleContainerKey.of(moduleKey)
        val nsKey = C_NsAsm_NamespaceKey(containerKey)

        val moduleAsm = C_NsAsm_InternalModuleAssembler(
                msgCtx,
                sysNsProto,
                exportSysEntities,
                nsLinker,
                nsKey,
                stamp
        )

        moduleAsms[moduleKey] = moduleAsm
        return moduleAsm
    }

    override fun addRepl(
            sysNsProto: C_SysNsProto,
            linkedModule: C_ModuleKey?,
            oldState: C_NsAsm_ReplState
    ): C_NsAsm_ReplAssembler {
        checkCanModify()
        check(replAsm == null)

        val nsKey = C_NsAsm_NamespaceKey(C_ReplContainerKey)

        val replAsm = C_NsAsm_InternalReplAssembler(
                nsLinker,
                nsKey,
                msgCtx,
                sysNsProto,
                stamp,
                linkedModule,
                oldState as C_NsAsm_InternalReplState
        )

        this.replAsm = replAsm
        return replAsm
    }

    override fun assemble(): C_NsAsm_App {
        finish()

        val modules = moduleAsms.mapValues { (_, v) -> v.assemble() }
        val nsModules = modules.mapValues { (_, v) -> v.ns }
        val preImpModules = preModules.mapValues { (_, v) -> v.asmModule.impNs }.toImmMap()
        val impModules = C_NsImp_ImportsProcessor.process(msgCtx, nsModules, preImpModules)

        val resModules = C_NsRes_ResultMaker.make(impModules)
        val appModules = createAppModules(modules, impModules, resModules)
        val replState = replAsm?.assemble(appModules) ?: C_NsAsm_InternalReplState.EMPTY

        val linkModules = preModules.map { (k, v) -> C_ModuleContainerKey.of(k) to v.asmModule.ns }.toMap() +
                resModules.mapKeys { (k, _) -> C_ModuleContainerKey.of(k) } +
                mapOf(C_ReplContainerKey to replState.ns)
        nsLinker.setModules(linkModules)

        return C_NsAsm_App(replState, appModules)
    }

    private fun createAppModules(
            rawModules: Map<C_ModuleKey, C_NsAsm_RawModule>,
            impModules: Map<C_ModuleKey, C_NsImp_Namespace>,
            resModules: Map<C_ModuleKey, C_Namespace>
    ): Map<C_ModuleKey, C_NsAsm_Module> {
        checkEquals(impModules.keys, rawModules.keys)
        checkEquals(resModules.keys, rawModules.keys)
        checkEquals(Sets.intersection(rawModules.keys, preModules.keys), setOf<C_ModuleKey>())

        val oldModules = preModules.mapValues { (_, v) -> v.asmModule }
        val newModules = rawModules.mapValues { (k, v) -> C_NsAsm_Module(v.rawNs, impModules.getValue(k), resModules.getValue(k)) }
        return (oldModules + newModules).toImmMap()
    }
}

private class C_NsAsm_InternalReplState(
        val rawNs: C_NsAsm_RawNamespace,
        val impNs: C_NsImp_Namespace,
        val ns: C_Namespace
): C_NsAsm_ReplState() {
    companion object {
        val EMPTY = C_NsAsm_InternalReplState(C_NsAsm_RawNamespace.EMPTY, C_NsImp_Namespace.EMPTY, C_Namespace.EMPTY)
    }
}

private class C_NsAsm_ConflictsProcessor(
        private val msgCtx: C_MessageContext,
        sysNsProto: C_SysNsProto,
        private val stamp: R_AppUid
) {
    private val sysDefs: Map<String, C_DeclarationType> = let {
        val mutSysDefs = mutableMapOf<String, C_DeclarationType>()
        for (entry in sysNsProto.entries) if (entry.name !in mutSysDefs) mutSysDefs[entry.name] = entry.def.type()
        mutSysDefs.toImmMap()
    }

    private val errors = mutableSetOf<S_Pos>()

    fun process(ns: C_NsAsm_RawNamespace): C_NsAsm_RawNamespace {
        return C_NsAsm_Utils.transformNamespaces(ns, false, stamp) { oldNs, newNs ->
            process0(newNs, oldNs === ns)
        }
    }

    fun mergeAndProcess(ns: C_NsAsm_RawNamespace): C_NsAsm_RawNamespace {
        val mergedNs = C_NsAsm_Utils.mergeNamespaces(ns, stamp)
        return process(mergedNs)
    }

    private fun process0(ns: C_NsAsm_RawNamespace, checkSysDefs: Boolean): C_NsAsm_RawNamespace {
        val map = mutableMapOf<String, C_NsAsm_RawEntry>()
        for (entry in ns.entries) {
            addEntry(map, entry, checkSysDefs)
        }
        return C_NsAsm_RawNamespace(map.values.toList(), ns.wildcardImports)
    }

    private fun addEntry(map: MutableMap<String, C_NsAsm_RawEntry>, entry: C_NsAsm_RawEntry, checkSysDefs: Boolean) {
        val name = entry.name

        val sysDefType = sysDefs[name.str]
        if (sysDefType != null && checkSysDefs) {
            report(name, entry.stamp, sysDefType, null)
            return
        }

        val oldEntry = map[name.str]
        if (oldEntry != null) {
            if (oldEntry.identity != entry.identity) {
                report(oldEntry, entry)
                report(entry, oldEntry)
            }
            return
        }

        map[name.str] = entry
    }

    private fun report(entry: C_NsAsm_RawEntry, otherEntry: C_NsAsm_RawEntry) {
        if (entry.container == C_ReplContainerKey || otherEntry.container != C_ReplContainerKey) {
            report(entry.name, entry.stamp, otherEntry.type, otherEntry.name)
        }
    }

    private fun report(name: S_Name, entryStamp: R_AppUid, otherType: C_DeclarationType, otherName: S_Name?) {
        if (entryStamp == stamp && errors.add(name.pos)) {
            msgCtx.error(C_Errors.errNameConflict(name, otherType, otherName?.pos))
        }
    }
}

private data class C_NsAsm_NamespaceKey(val container: C_ContainerKey, val path: R_QualifiedName = R_QualifiedName.EMPTY) {
    fun sub(name: R_Name): C_NsAsm_NamespaceKey {
        val subPath = path.child(name)
        return C_NsAsm_NamespaceKey(container, subPath)
    }
}

private sealed class C_NsAsm_NamespaceLinker {
    abstract fun getLink(nsKey: C_NsAsm_NamespaceKey): Getter<C_Namespace>
}

private class C_NsAsm_InternalNamespaceLinker: C_NsAsm_NamespaceLinker() {
    private val links = mutableMapOf<C_NsAsm_NamespaceKey, NamespaceLink>()
    private val containerNamespaces = mutableMapOf<C_ContainerKey, C_Namespace>()
    private var completed = false

    override fun getLink(nsKey: C_NsAsm_NamespaceKey): Getter<C_Namespace> {
        check(!completed)
        val link = links.computeIfAbsent(nsKey) { NamespaceLink(it) }
        return link.getter
    }

    fun setModules(containers: Map<C_ContainerKey, C_Namespace>) {
        check(!completed)
        completed = true
        containerNamespaces.putAll(containers)
    }

    private fun resolveNamespace(nsKey: C_NsAsm_NamespaceKey): C_Namespace {
        var ns = containerNamespaces[nsKey.container]
        for (name in nsKey.path.parts) {
            if (ns == null) return C_Namespace.EMPTY
            ns = ns.namespace(name.str)?.getDefQuiet()
        }
        return ns ?: C_Namespace.EMPTY
    }

    private inner class NamespaceLink(private val nsKey: C_NsAsm_NamespaceKey) {
        val getter: Getter<C_Namespace> = {
            get()
        }

        private var ns: C_Namespace? = null

        private fun get(): C_Namespace {
            var res = ns
            if (res == null) {
                res = resolveNamespace(nsKey)
                ns = res
            }
            return res
        }
    }
}

private object C_NsAsm_Utils {
    fun mergeNamespaces(ns: C_NsAsm_RawNamespace, stamp: R_AppUid): C_NsAsm_RawNamespace {
        return transformNamespaces(ns, true, stamp) { oldNs, newNs ->
            mergeNamespaces0(oldNs, stamp)
        }
    }

    private fun mergeNamespaces0(ns: C_NsAsm_RawNamespace, stamp: R_AppUid): C_NsAsm_RawNamespace {
        val namespaces = mutableMapOf<String, MutableList<C_NsAsm_RawNamespace>>()
        for (entry in ns.entries) {
            if (entry.def is C_NsAsm_RawDef_Namespace && entry.def.merge) {
                val list = namespaces.computeIfAbsent(entry.name.str) { mutableListOf() }
                list.add(entry.def.ns)
            }
        }

        val entries = mutableListOf<C_NsAsm_RawEntry>()
        for (entry in ns.entries) {
            if (entry.def is C_NsAsm_RawDef_Namespace && entry.def.merge) {
                val list = namespaces.remove(entry.name.str)
                if (list != null && list.size >= 2) {
                    val mergedNs = mergeNamespaces0(list)
                    val mergedDef = C_NsAsm_RawDef_Namespace(mergedNs, true)
                    val mergedEntry = C_NsAsm_RawEntry(entry.name, entry.type, mergedDef, entry.identity, entry.container, stamp)
                    entries.add(mergedEntry)
                } else if (list != null) {
                    entries.add(entry)
                }
            } else {
                entries.add(entry)
            }
        }

        return C_NsAsm_RawNamespace(entries, ns.wildcardImports)
    }

    private fun mergeNamespaces0(list: List<C_NsAsm_RawNamespace>): C_NsAsm_RawNamespace {
        val entries = list.flatMap { it.entries }
        val wildcardImports = list.flatMap { it.wildcardImports }
        return C_NsAsm_RawNamespace(entries, wildcardImports)
    }

    fun concatNamespaces(nss: List<C_NsAsm_RawNamespace>): C_NsAsm_RawNamespace {
        val entries = nss.flatMap { it.entries }
        val wildcardImports = nss.flatMap { it.wildcardImports }
        return C_NsAsm_RawNamespace(entries, wildcardImports)
    }

    fun transformNamespaces(
            ns: C_NsAsm_RawNamespace,
            pre: Boolean,
            stamp: R_AppUid,
            f: (C_NsAsm_RawNamespace, C_NsAsm_RawNamespace) -> C_NsAsm_RawNamespace
    ): C_NsAsm_RawNamespace {
        val preNs = if (pre) f(ns, ns) else ns
        val transNs = transformNamespacesSub(preNs, pre, stamp, f)
        val postNs = if (!pre) f(ns, transNs) else transNs
        return postNs
    }

    private fun transformNamespacesSub(
            ns: C_NsAsm_RawNamespace,
            pre: Boolean,
            stamp: R_AppUid,
            f: (C_NsAsm_RawNamespace, C_NsAsm_RawNamespace) -> C_NsAsm_RawNamespace
    ): C_NsAsm_RawNamespace {
        val entries = mutableListOf<C_NsAsm_RawEntry>()
        for (entry in ns.entries) {
            val entry2 = if (entry.def !is C_NsAsm_RawDef_Namespace) entry else {
                val ns2 = transformNamespaces(entry.def.ns, pre, stamp, f)
                val def2 = C_NsAsm_RawDef_Namespace(ns2, entry.def.merge)
                C_NsAsm_RawEntry(entry.name, entry.type, def2, entry.identity, entry.container, stamp)
            }
            entries.add(entry2)
        }
        return C_NsAsm_RawNamespace(entries, ns.wildcardImports)
    }
}
