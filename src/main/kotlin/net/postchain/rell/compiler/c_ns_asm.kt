package net.postchain.rell.compiler

import net.postchain.rell.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos

interface C_NsAsm_BasicAssembler {
    fun futureNs(): Getter<C_Namespace>
}

interface C_NsAsm_FileAssembler: C_NsAsm_BasicAssembler {
    fun addDef(name: S_Name, def: C_NsDef)
    fun addNamespace(name: S_Name, expandable: Boolean): C_NsAsm_FileAssembler
    fun addModuleImport(alias: S_Name, module: C_ModuleKey)
    fun addExactImport(alias: S_Name, module: C_ModuleKey, path: List<S_Name>, name: S_Name)
    fun addWildcardImport(module: C_ModuleKey, path: List<S_Name>)
}

interface C_NsAsm_ModuleAssembler: C_NsAsm_BasicAssembler {
    fun futureDefs(): Getter<C_ModuleDefs>
    fun addFile(): C_NsAsm_FileAssembler
}

interface C_NsAsm_AppAssembler {
    fun addModule(moduleKey: C_ModuleKey, sysNsProto: C_SysNsProto, exportSysEntities: Boolean): C_NsAsm_ModuleAssembler
    fun assemble()

    companion object {
        fun create(globalCtx: C_GlobalContext): C_NsAsm_AppAssembler {
            return C_NsAsm_InternalAppAssembler(globalCtx)
        }
    }
}

class C_NsAsm_WildcardImport(val module: C_ModuleKey, val path: List<S_Name>)

class C_NsAsm_ExactImport(val module: C_ModuleKey, path: List<S_Name>, val name: S_Name) {
    val path = path.toImmList()
}

class C_NsAsm_Namespace(
        defs: Map<String, C_NsAsm_Def>,
        wildcardImports: List<C_NsAsm_WildcardImport>,
        val setter: Setter<C_Namespace>
) {
    val defs = defs.toImmMap()
    val wildcardImports = wildcardImports.toImmList()

    companion object {
        val EMPTY = C_NsAsm_Namespace(mapOf(), listOf(), {})
    }
}

sealed class C_NsAsm_Def

class C_NsAsm_Def_Simple(val elem: C_NamespaceElement): C_NsAsm_Def()
class C_NsAsm_Def_ExactImport(val imp: C_NsAsm_ExactImport): C_NsAsm_Def()

sealed class C_NsAsm_Def_Namespace: C_NsAsm_Def() {
    abstract fun ns(): C_NsAsm_Namespace

    companion object {
        fun createDirect(ns: C_NsAsm_Namespace): C_NsAsm_Def = C_NsAsm_Def_DirectNamespace(ns)
        fun createLate(getter: Getter<C_NsAsm_Namespace>): C_NsAsm_Def = C_NsAsm_Def_LateNamespace(getter)
    }
}

private class C_NsAsm_Def_DirectNamespace(private val ns: C_NsAsm_Namespace): C_NsAsm_Def_Namespace() {
    override fun ns() = ns
}

private class C_NsAsm_Def_LateNamespace(private val getter: Getter<C_NsAsm_Namespace>): C_NsAsm_Def_Namespace() {
    override fun ns() = getter()
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

private sealed class C_NsAsm_InternalBasicAssembler: C_NsAsm_InternalAssembler(), C_NsAsm_BasicAssembler {
    protected val nsLate = C_LateInit(C_CompilerPass.NAMESPACES, C_Namespace.EMPTY)
    final override fun futureNs() = nsLate.getter
}

private class C_NsAsm_MutableEntry(
        val name: S_Name,
        val type: C_DeclarationType,
        val def: C_NsAsm_MutableDef,
        val identity: C_NsAsm_Identity
) {
    fun assemble(): C_NsAsm_RawEntry {
        val def2 = def.assemble()
        return C_NsAsm_RawEntry(name, type, def2, identity)
    }
}

private sealed class C_NsAsm_MutableDef {
    abstract fun assemble(): C_NsAsm_RawDef
}

private class C_NsAsm_MutableDef_Simple(private val def: C_NsAsm_RawDef): C_NsAsm_MutableDef() {
    override fun assemble() = def
}

private class C_NsAsm_MutableDef_Namespace(
        private val asm: C_NsAsm_InternalFileAssembler,
        private val merge: Boolean
): C_NsAsm_MutableDef() {
    override fun assemble(): C_NsAsm_RawDef {
        val ns = asm.assemble()
        return C_NsAsm_RawDef_Namespace(ns, merge)
    }
}

private class C_NsAsm_InternalFileAssembler: C_NsAsm_InternalBasicAssembler(), C_NsAsm_FileAssembler {
    private val entries = mutableListOf<C_NsAsm_MutableEntry>()
    private val wildcardImports = mutableListOf<C_NsAsm_WildcardImport>()

    private fun addEntry(name: S_Name, type: C_DeclarationType, def: C_NsAsm_MutableDef, identity: C_NsAsm_Identity) {
        checkCanModify()
        entries.add(C_NsAsm_MutableEntry(name, type, def, identity))
    }

    override fun addDef(name: S_Name, def: C_NsDef) {
        val type = def.type()
        val def2 = C_NsAsm_RawDef_Simple(def)
        addEntry(name, type, C_NsAsm_MutableDef_Simple(def2), C_NsAsm_Identity_Def(def2))
    }

    override fun addNamespace(name: S_Name, merge: Boolean): C_NsAsm_FileAssembler {
        checkCanModify()
        val asm = C_NsAsm_InternalFileAssembler()
        val def = C_NsAsm_MutableDef_Namespace(asm, merge)
        addEntry(name, C_DeclarationType.NAMESPACE, def, C_NsAsm_Identity_Unique())
        return asm
    }

    override fun addModuleImport(alias: S_Name, module: C_ModuleKey) {
        val asm = C_NsAsm_InternalFileAssembler()
        asm.addWildcardImport(module, listOf())
        val ns = asm.assemble()
        val def = C_NsAsm_RawDef_Namespace(ns, false)
        val mutDef = C_NsAsm_MutableDef_Simple(def)
        addEntry(alias, C_DeclarationType.IMPORT, mutDef, C_NsAsm_Identity_ModuleImport(module))
    }

    override fun addExactImport(alias: S_Name, module: C_ModuleKey, path: List<S_Name>, name: S_Name) {
        val imp = C_NsAsm_ExactImport(module, path, name)
        val def = C_NsAsm_RawDef_ExactImport(imp)
        val mutDef = C_NsAsm_MutableDef_Simple(def)
        addEntry(alias, C_DeclarationType.IMPORT, mutDef, C_NsAsm_Identity_ExactImport(imp))
    }

    override fun addWildcardImport(module: C_ModuleKey, path: List<S_Name>) {
        checkCanModify()
        wildcardImports.add(C_NsAsm_WildcardImport(module, path))
    }

    fun assemble(): C_NsAsm_RawNamespace {
        finish()
        val resEntries = entries.map { it.assemble() }
        val resWildcardImports = wildcardImports.toImmList()
        return C_NsAsm_RawNamespace(resEntries, resWildcardImports, nsLate.setter)
    }
}

private class C_NsAsm_RawNamespace(
        entries: List<C_NsAsm_RawEntry>,
        wildcardImports: List<C_NsAsm_WildcardImport>,
        val setter: Setter<C_Namespace>
) {
    val entries = entries.toImmList()
    val wildcardImports = wildcardImports.toImmList()

    fun addToDefs(b: C_ModuleDefsBuilder) {
        for (entry in entries) {
            entry.def.addToDefs(b)
        }
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

        return C_NsAsm_Namespace(defs, wildcardImports, setter)
    }
}

private class C_NsAsm_RawEntry(
        val name: S_Name,
        val type: C_DeclarationType,
        val def: C_NsAsm_RawDef,
        val identity: C_NsAsm_Identity
)

private sealed class C_NsAsm_RawDef {
    open fun addToDefs(b: C_ModuleDefsBuilder) {}
    abstract fun assemble(): C_NsAsm_Def
}

private class C_NsAsm_RawDef_Simple(private val def: C_NsDef): C_NsAsm_RawDef() {
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
    override fun addToDefs(b: C_ModuleDefsBuilder) = ns.addToDefs(b)

    override fun assemble(): C_NsAsm_Def {
        val ns2 = ns.assemble(listOf())
        return C_NsAsm_Def_Namespace.createDirect(ns2)
    }
}

private class C_NsAsm_RawDef_ExactImport(private val imp: C_NsAsm_ExactImport): C_NsAsm_RawDef() {
    override fun assemble() = C_NsAsm_Def_ExactImport(imp)
}

private sealed class C_NsAsm_Identity

private class C_NsAsm_Identity_Unique: C_NsAsm_Identity() {
    override fun equals(other: Any?) = this === other
}

private class C_NsAsm_Identity_Def(private val def: C_NsAsm_RawDef): C_NsAsm_Identity() {
    override fun equals(other: Any?) = other is C_NsAsm_Identity_Def && def == other.def
}

private class C_NsAsm_Identity_ModuleImport(private val module: C_ModuleKey): C_NsAsm_Identity() {
    override fun equals(other: Any?) = other is C_NsAsm_Identity_ModuleImport && module == other.module
}

private class C_NsAsm_Identity_ExactImport(imp: C_NsAsm_ExactImport): C_NsAsm_Identity() {
    private val module = imp.module
    private val path = imp.path.map { it.str }
    private val name = imp.name.str
    override fun equals(other: Any?) = other is C_NsAsm_Identity_ExactImport
            && module == other.module && path == other.path && name == other.name
}

private class C_NsAsm_InternalModuleAssembler(
        private val globalCtx: C_GlobalContext,
        private val sysNsProto: C_SysNsProto,
        private val exportSysEntities: Boolean
): C_NsAsm_InternalBasicAssembler(), C_NsAsm_ModuleAssembler {
    private val modDefsLate = C_LateInit(C_CompilerPass.NAMESPACES, C_ModuleDefs.EMPTY)

    private val files = mutableListOf<C_NsAsm_InternalFileAssembler>()

    override fun futureDefs() = modDefsLate.getter

    override fun addFile(): C_NsAsm_FileAssembler {
        checkCanModify()
        val asm = C_NsAsm_InternalFileAssembler()
        files.add(asm)
        return asm
    }

    fun assemble(): C_NsAsm_Namespace {
        finish()

        val sysEntries = if (exportSysEntities) sysNsProto.entries else listOf()

        val conflictsProcessor = createConflictsProcessor()

        var fileNss = files.map { it.assemble() }
        fileNss = fileNss.map { mergeNamespaces(it) }
        fileNss = fileNss.map { conflictsProcessor.process(it) }

        var modNs = mergeTrees(fileNss, nsLate.setter)
        modNs = mergeNamespaces(modNs)
        modNs = conflictsProcessor.process(modNs)

        val modDefs = createModuleDefs(modNs)
        modDefsLate.set(modDefs)

        val resNs = modNs.assemble(sysEntries)
        return resNs
    }

    private fun createConflictsProcessor(): C_NsAsm_ConflictsProcessor {
        val sysDefs = mutableMapOf<String, C_DeclarationType>()
        for (entry in sysNsProto.entries) {
            if (entry.name !in sysDefs) sysDefs[entry.name] = entry.def.type()
        }
        return C_NsAsm_ConflictsProcessor(globalCtx, sysDefs)
    }

    private fun createModuleDefs(ns: C_NsAsm_RawNamespace): C_ModuleDefs {
        val defsBuilder = C_ModuleDefsBuilder()
        ns.addToDefs(defsBuilder)
        return defsBuilder.build()
    }

    private fun mergeNamespaces(ns: C_NsAsm_RawNamespace): C_NsAsm_RawNamespace {
        return C_NsAsm_Utils.transformNamespaces(ns, true) { oldNs, newNs ->
            mergeNamespaces0(oldNs)
        }
    }

    private fun mergeNamespaces0(ns: C_NsAsm_RawNamespace): C_NsAsm_RawNamespace {
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
                    entries.add(C_NsAsm_RawEntry(entry.name, entry.type, mergedDef, entry.identity))
                } else if (list != null) {
                    entries.add(entry)
                }
            } else {
                entries.add(entry)
            }
        }

        return C_NsAsm_RawNamespace(entries, ns.wildcardImports, ns.setter)
    }

    private fun mergeNamespaces0(list: List<C_NsAsm_RawNamespace>): C_NsAsm_RawNamespace {
        val entries = list.flatMap { it.entries }
        val wildcardImports = list.flatMap { it.wildcardImports }
        val setters = list.map { it.setter }
        val setter = CommonUtils.compoundSetter(setters)
        return C_NsAsm_RawNamespace(entries, wildcardImports, setter)
    }

    private fun mergeTrees(nss: List<C_NsAsm_RawNamespace>, setter: Setter<C_Namespace>): C_NsAsm_RawNamespace {
        val entries = nss.flatMap { it.entries }
        val wildcardImports = nss.flatMap { it.wildcardImports }
        return C_NsAsm_RawNamespace(entries, wildcardImports, setter)
    }
}

private class C_NsAsm_InternalAppAssembler(private val globalCtx: C_GlobalContext): C_NsAsm_InternalAssembler(), C_NsAsm_AppAssembler {
    private val moduleAsms = mutableMapOf<C_ModuleKey, C_NsAsm_InternalModuleAssembler>()

    override fun addModule(moduleKey: C_ModuleKey, sysNsProto: C_SysNsProto, exportSysEntities: Boolean): C_NsAsm_ModuleAssembler {
        checkCanModify()
        check(moduleKey !in moduleAsms) { moduleKey }
        val moduleAsm = C_NsAsm_InternalModuleAssembler(globalCtx, sysNsProto, exportSysEntities)
        moduleAsms[moduleKey] = moduleAsm
        return moduleAsm
    }

    override fun assemble() {
        finish()

        var modules = moduleAsms.mapValues { (_, v) -> v.assemble() }
        modules = C_NsImp_ImportsProcessor.process(globalCtx, modules)

        val resAsm = C_NsAsm_ResultAssembler()
        for (ns in modules.values) {
            resAsm.assembleModule(ns)
        }
    }
}

private class C_NsAsm_ResultAssembler {
    private val nsMap = mutableMapOf<C_NsAsm_Namespace, Getter<C_Namespace>>()

    fun assembleModule(ns: C_NsAsm_Namespace) {
        assembleNamespace(ns)
    }

    private fun assembleNamespace(ns: C_NsAsm_Namespace): Getter<C_Namespace> {
        val getter = nsMap[ns]
        if (getter != null) return getter

        val init = LateInit<C_Namespace>()
        nsMap[ns] = init.getter

        val resNs = assembleNamespace0(ns)
        init.set(resNs)
        ns.setter(resNs)

        return init.getter
    }

    private fun assembleNamespace0(ns: C_NsAsm_Namespace): C_Namespace {
        val b = C_NamespaceBuilder()
        for ((name, def) in ns.defs) {
            assembleNamespaceDef(b, name, def)
        }
        return b.build()
    }

    private fun assembleNamespaceDef(b: C_NamespaceBuilder, name: String, def: C_NsAsm_Def) {
        return when (def) {
            is C_NsAsm_Def_Simple -> b.add(name, def.elem)
            is C_NsAsm_Def_ExactImport -> TODO()
            is C_NsAsm_Def_Namespace -> {
                val ns = def.ns()
                val getter = assembleNamespace(ns)
                val proxy = C_DefProxy.createGetter(getter)
                val elem = C_NsDef_UserNamespace(proxy).toNamespaceElement()
                b.add(name, elem)
            }
        }
    }
}

private class C_NsAsm_ConflictsProcessor(
        private val globalCtx: C_GlobalContext,
        private val sysDefs: Map<String, C_DeclarationType>
) {
    private val errors = mutableSetOf<S_Pos>()

    fun process(ns: C_NsAsm_RawNamespace): C_NsAsm_RawNamespace {
        return C_NsAsm_Utils.transformNamespaces(ns, false) { oldNs, newNs ->
            process0(newNs, oldNs === ns)
        }
    }

    private fun process0(ns: C_NsAsm_RawNamespace, checkSysDefs: Boolean): C_NsAsm_RawNamespace {
        val map = mutableMapOf<String, C_NsAsm_RawEntry>()
        for (entry in ns.entries) {
            addEntry(map, entry, checkSysDefs)
        }
        return C_NsAsm_RawNamespace(map.values.toList(), ns.wildcardImports, ns.setter)
    }

    private fun addEntry(map: MutableMap<String, C_NsAsm_RawEntry>, entry: C_NsAsm_RawEntry, checkSysDefs: Boolean) {
        val name = entry.name

        val sysDefType = sysDefs[name.str]
        if (sysDefType != null && checkSysDefs) {
            report(name, sysDefType, null)
            return
        }

        val oldEntry = map[name.str]
        if (oldEntry != null) {
            if (oldEntry.identity != entry.identity) {
                report(oldEntry.name, entry.type, name)
                report(name, oldEntry.type, oldEntry.name)
            }
            return
        }

        map[name.str] = entry
    }

    private fun report(name: S_Name, otherType: C_DeclarationType, otherName: S_Name?) {
        if (errors.add(name.pos)) {
            globalCtx.error(C_Errors.errNameConflict(name, otherType, otherName?.pos))
        }
    }
}

private object C_NsAsm_Utils {
    fun transformNamespaces(
            ns: C_NsAsm_RawNamespace,
            pre: Boolean,
            f: (C_NsAsm_RawNamespace, C_NsAsm_RawNamespace) -> C_NsAsm_RawNamespace
    ): C_NsAsm_RawNamespace {
        val preNs = if (pre) f(ns, ns) else ns
        val transNs = transformNamespacesSub(preNs, pre, f)
        val postNs = if (!pre) f(ns, transNs) else transNs
        return postNs
    }

    private fun transformNamespacesSub(
            ns: C_NsAsm_RawNamespace,
            pre: Boolean,
            f: (C_NsAsm_RawNamespace, C_NsAsm_RawNamespace) -> C_NsAsm_RawNamespace
    ): C_NsAsm_RawNamespace {
        val entries = mutableListOf<C_NsAsm_RawEntry>()
        for (entry in ns.entries) {
            val entry2 = if (entry.def !is C_NsAsm_RawDef_Namespace) entry else {
                val ns2 = transformNamespaces(entry.def.ns, pre, f)
                val def2 = C_NsAsm_RawDef_Namespace(ns2, entry.def.merge)
                C_NsAsm_RawEntry(entry.name, entry.type, def2, entry.identity)
            }
            entries.add(entry2)
        }
        return C_NsAsm_RawNamespace(entries, ns.wildcardImports, ns.setter)
    }
}
