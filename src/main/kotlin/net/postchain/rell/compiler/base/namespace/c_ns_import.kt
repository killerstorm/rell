/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.namespace

import com.google.common.collect.Multimap
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_QualifiedName
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.module.C_ModuleKey
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.utils.*
import java.util.*

class C_NsImp_Namespace(directDefs: Map<String, C_NsImp_Def>, importDefs: Multimap<String, C_NsImp_Def>) {
    val directDefs = directDefs.toImmMap()
    val importDefs = importDefs.toImmMultimap()

    companion object { val EMPTY = C_NsImp_Namespace(mapOf(), immMultimapOf()) }
}

sealed class C_NsImp_Def

class C_NsImp_Def_Simple(val elem: C_NamespaceElement): C_NsImp_Def()

class C_NsImp_Def_Namespace(private val getter: LateGetter<C_NsImp_Namespace>): C_NsImp_Def() {
    fun ns() = getter.get()
}

object C_NsImp_ImportsProcessor {
    fun process(
            msgCtx: C_MessageContext,
            modules: Map<C_ModuleKey, C_NsAsm_Namespace>,
            preModules: Map<C_ModuleKey, C_NsImp_Namespace>
    ): Map<C_ModuleKey, C_NsImp_Namespace> {
        val converter = C_NsImp_NamespaceConverter()
        val asmPreModules = preModules.mapValues { (_, v) -> converter.convert(v) }
        val processor = C_NsImp_InternalImportsProcessor(msgCtx, modules, asmPreModules)

        val (asmList, listVsMap) = ListVsMap.mapToList(modules)
        val impList = processor.process(asmList)
        val impMap = listVsMap.listToMap(impList)

        return impMap
    }

    fun process(
            msgCtx: C_MessageContext,
            ns: C_NsAsm_Namespace,
            preModules: Map<C_ModuleKey, C_NsImp_Namespace>
    ): C_NsImp_Namespace {
        val impList = process0(msgCtx, mapOf(), preModules, listOf(ns))
        checkEquals(impList.size, 1)
        return impList[0]
    }

    private fun process0(
            msgCtx: C_MessageContext,
            modules: Map<C_ModuleKey, C_NsAsm_Namespace>,
            preModules: Map<C_ModuleKey, C_NsImp_Namespace>,
            asmList: List<C_NsAsm_Namespace>
    ): List<C_NsImp_Namespace> {
        val converter = C_NsImp_NamespaceConverter()
        val asmPreModules = preModules.mapValues { (_, v) -> converter.convert(v) }
        val processor = C_NsImp_InternalImportsProcessor(msgCtx, modules, asmPreModules)
        return processor.process(asmList)
    }
}

private class C_NsImp_InternalImportsProcessor(
        private val msgCtx: C_MessageContext,
        private val modules: Map<C_ModuleKey, C_NsAsm_Namespace>,
        preModules: Map<C_ModuleKey, C_NsAsm_Namespace>
) {
    private val importResolver = C_NsImp_ImportResolver(modules, preModules)
    private val states = mutableMapOf<C_NsAsm_Namespace, C_NsImp_NamespaceState>()

    fun process(asmList: List<C_NsAsm_Namespace>): List<C_NsImp_Namespace> {
        for (ns in asmList) {
            processNamespace(ns)
        }

        val impList = asmList
                .map { states.getValue(it).getNamespace() }
                .toImmList()

        return impList
    }

    private fun processNamespace(targetNs: C_NsAsm_Namespace): C_NsImp_NamespaceState {
        val state = getState(targetNs)
        if (state.processed) {
            return state
        }
        state.processed = true

        processNamespace0(targetNs, state)

        return state
    }

    private fun processNamespace0(targetNs: C_NsAsm_Namespace, state: C_NsImp_NamespaceState) {
        val builder = C_NsImp_NamespaceBuilder()

        for ((name, def) in targetNs.defs) {
            processDirectDef(builder, name, def)
        }

        for (wildcard in targetNs.wildcardImports) {
            processDirectWildcardImport(wildcard)
        }

        for ((name, defs) in targetNs.importDefs.asMap()) {
            for (def in defs) {
                processImportDef(builder, name, def)
            }
        }

        val closure = importResolver.namespaceClosure(targetNs)
        for (ns in closure.filter { it !== targetNs }) {
            for ((name, def) in ns.defs) {
                processImportDef(builder, name, def)
            }
            for ((name, defs) in ns.importDefs.asMap()) {
                for (def in defs) {
                    processImportDef(builder, name, def)
                }
            }
        }

        val resNs = builder.build()
        state.initNamespace(resNs)
    }

    private fun processDirectDef(builder: C_NsImp_NamespaceBuilder, name: String, def: C_NsAsm_Def) {
        return when (def) {
            is C_NsAsm_Def_Simple -> {
                builder.addDirectDef(name, def.elem)
            }
            is C_NsAsm_Def_ExactImport -> {
                processDirectExactImport(builder, name, def.imp)
            }
            is C_NsAsm_Def_Namespace -> {
                val ns = def.ns()
                val nsState = processNamespace(ns)
                val nsGetter = nsState.getNamespaceGetter()
                val nsDef = C_NsImp_Def_Namespace(nsGetter)
                builder.addDirectDef(name, nsDef)
            }
        }
    }

    private fun processDirectExactImport(builder: C_NsImp_NamespaceBuilder, name: String, imp: C_NsAsm_ExactImport) {
        val res = importResolver.resolveExactImport(imp)
        val def = res.valueOrReport(msgCtx)
        return when (def) {
            null -> {}
            is C_NsAsm_Def_Simple -> {
                builder.addDirectDef(name, def.elem)
            }
            is C_NsAsm_Def_Namespace -> {
                processImportNamespaceDef(builder, name, def)
            }
            is C_NsAsm_Def_ExactImport -> {
                // Must not get here, do nothing instead of throwing.
            }
        }
    }

    private fun processImportDef(builder: C_NsImp_NamespaceBuilder, name: String, def: C_NsAsm_Def) {
        return when (def) {
            is C_NsAsm_Def_Simple -> {
                builder.addImportDef(name, def.elem)
            }
            is C_NsAsm_Def_Namespace -> {
                processImportNamespaceDef(builder, name, def)
            }
            is C_NsAsm_Def_ExactImport -> {
                val res = importResolver.resolveExactImport(def.imp)
                if (res.value != null) {
                    // Must be no recursion here - the def must not be an import.
                    processImportDef(builder, name, res.value)
                }
                Unit
            }
        }
    }

    private fun processImportNamespaceDef(builder: C_NsImp_NamespaceBuilder, name: String, def: C_NsAsm_Def_Namespace) {
        val ns = def.ns()
        val nsState = processNamespace(ns)
        val nsGetter = nsState.getNamespaceGetter()
        val nsDef = C_NsImp_Def_Namespace(nsGetter)
        builder.addImportDef(name, nsDef)
    }

    private fun processDirectWildcardImport(wildcard: C_NsAsm_WildcardImport) {
        val res = importResolver.resolveNamespaceByPath(wildcard.module, wildcard.path)
        res.valueOrReport(msgCtx)
    }

    private fun getState(ns: C_NsAsm_Namespace) = states.computeIfAbsent(ns) { C_NsImp_NamespaceState() }

    private class C_NsImp_NamespaceState {
        var processed = false

        private val nsInit = LateInit<C_NsImp_Namespace>()

        fun initNamespace(ns: C_NsImp_Namespace) {
            nsInit.set(ns)
        }

        fun getNamespace() = nsInit.get()
        fun getNamespaceGetter() = nsInit.getter
    }
}

private class C_NsImp_NamespaceBuilder {
    private val directDefs = mutableMapOf<String, C_NsImp_Def>()
    private val importDefs = mutableMultimapOf<String, C_NsImp_Def>()

    fun addDirectDef(name: String, def: C_NsImp_Def) {
        check(name !in directDefs)
        directDefs[name] = def
    }

    fun addDirectDef(name: String, elem: C_NamespaceElement) {
        val def = C_NsImp_Def_Simple(elem)
        addDirectDef(name, def)
    }

    fun addImportDef(name: String, def: C_NsImp_Def) {
        importDefs.put(name, def)
    }

    fun addImportDef(name: String, elem: C_NamespaceElement) {
        val def = C_NsImp_Def_Simple(elem)
        addImportDef(name, def)
    }

    fun build(): C_NsImp_Namespace {
        return C_NsImp_Namespace(directDefs.toImmMap(), importDefs.toImmMultimap())
    }
}

private class C_NsImp_NamespaceConverter {
    private val map = mutableMapOf<C_NsImp_Namespace, LateGetter<C_NsAsm_Namespace>>()

    fun convert(impNs: C_NsImp_Namespace): C_NsAsm_Namespace {
        val late = convertNamespaceLate(impNs)
        return late.get()
    }

    private fun convertNamespaceLate(impNs: C_NsImp_Namespace): LateGetter<C_NsAsm_Namespace> {
        var getter = map[impNs]
        if (getter == null) {
            val late = LateInit<C_NsAsm_Namespace>()
            getter = late.getter
            map[impNs] = getter
            val ns = convertNamespace(impNs)
            late.set(ns)
        }
        return getter
    }

    private fun convertNamespace(impNs: C_NsImp_Namespace): C_NsAsm_Namespace {
        val defs = impNs.directDefs.mapValues { (_, v) -> convertDef(v) }

        val importDefs = impNs.importDefs.asMap()
                .mapValues { (_, v) -> v.map { convertDef(it) } }
                .toImmMultimap()

        return C_NsAsm_Namespace(defs, importDefs, listOf())
    }

    private fun convertDef(def: C_NsImp_Def): C_NsAsm_Def {
        return when (def) {
            is C_NsImp_Def_Simple -> C_NsAsm_Def_Simple(def.elem)
            is C_NsImp_Def_Namespace -> {
                val ns = def.ns()
                val late = convertNamespaceLate(ns)
                C_NsAsm_Def_Namespace.createLate(late)
            }
        }
    }
}

private class C_NsImp_ImportResolver(
        private val modules: Map<C_ModuleKey, C_NsAsm_Namespace>,
        private val preModules: Map<C_ModuleKey, C_NsAsm_Namespace>
) {
    private data class NsNameKey(val ns: C_NsAsm_Namespace, val name: String)

    private val resolveNamespaceByNameCalc: C_RecursionSafeCalculator<NsNameKey, S_Name, C_NsAsm_Namespace>
    private val resolveDefCalc: C_RecursionSafeCalculator<NsNameKey, S_Name, C_NsAsm_Def>

    init {
        val recCtx = C_RecursionSafeContext()

        resolveNamespaceByNameCalc = recCtx.createCalculator(
                keyToResult = { resolveNamespaceByName0(it.ns, it.name) },
                recursionError = { errRecursion(it) }
        )

        resolveDefCalc = recCtx.createCalculator(
                keyToResult = { resolveDef0(it.ns, it.name) },
                recursionError = { errRecursion(it) }
        )
    }

    private fun errRecursion(name: S_Name): C_Error {
        return C_Error.stop(name.pos, "import:recursion:${name.str}", "Name '${name.str}' is a recursive definition")
    }

    fun resolveNamespaceByPath(module: C_ModuleKey, path: List<S_Name>): C_NsImp_Result<C_NsAsm_Namespace> {
        var ns = preModules[module] ?: modules[module] ?: C_NsAsm_Namespace.EMPTY
        for (name in path) {
            val res = resolveNamespaceByName(ns, name.str)
            if (res.value == null) {
                return convertResult(name, res)
            }
            ns = res.value
        }
        return C_NsImp_Result(ns)
    }

    private fun resolveNamespaceByName(ns: C_NsAsm_Namespace, name: String): C_RecursionSafeResult<S_Name, C_NsAsm_Namespace> {
        val key = NsNameKey(ns, name)
        val res = resolveNamespaceByNameCalc.calculate(key)
        return res
    }

    private fun resolveNamespaceByName0(ns: C_NsAsm_Namespace, name: String): C_RecursionSafeResult<S_Name, C_NsAsm_Namespace> {
        val res = resolveDef(ns, name)

        var def = res.value
        if (def == null) {
            return C_RecursionSafeResult.error {
                res.error(it)
            }
        }

        if (def is C_NsAsm_Def_ExactImport) {
            val res2 = resolveExactImport(def.imp)
            def = res2.value
            if (def == null) {
                return C_RecursionSafeResult.error {
                    C_Error.stop(it.pos, "import:name_unresolved:${it.str}", "Cannot resolve name '${it.str}'")
                }
            }
        }

        if (def !is C_NsAsm_Def_Namespace) {
            return C_RecursionSafeResult.error {
                C_Error.stop(it.pos, "import:not_ns:${it.str}", "Name '${it.str}' is not a namespace")
            }
        }

        val defNs = def.ns()
        return C_RecursionSafeResult(defNs)
    }

    fun resolveExactImport(imp: C_NsAsm_ExactImport): C_NsImp_Result<C_NsAsm_Def> {
        val res0 = resolveExactImportDirect(imp)
        if (res0.value == null) return res0

        val def0 = res0.value
        if (def0 !is C_NsAsm_Def_ExactImport) return res0

        val set = mutableSetOf(imp)

        var curImp = def0.imp
        while (true) {
            if (!set.add(curImp)) {
                return if (curImp == imp) {
                    C_NsImp_Result.error { errExactRecursion(imp) }
                } else {
                    C_NsImp_Result.error { errExactUnresolved(imp) }
                }
            }

            val res = resolveExactImportDirect(curImp)
            if (res.value == null) {
                return C_NsImp_Result.error { errExactUnresolved(imp) }
            }

            val def = res.value
            if (def !is C_NsAsm_Def_ExactImport) return res
            curImp = def.imp
        }
    }

    private fun errExactUnresolved(imp: C_NsAsm_ExactImport): C_Error {
        val fullName = C_Utils.appLevelName(imp.module, S_QualifiedName(imp.path + imp.name))
        val code = "import:exact:unresolved:$fullName"
        return C_Error.stop(imp.name.pos, code, "Cannot resolve import: '$fullName'")
    }

    private fun errExactRecursion(imp: C_NsAsm_ExactImport): C_Error {
        val fullName = C_Utils.appLevelName(imp.module, S_QualifiedName(imp.path + imp.name))
        val code = "import:exact:recursion:$fullName"
        return C_Error.stop(imp.name.pos, code, "Recursive import: '$fullName' points to itself")
    }

    private fun resolveExactImportDirect(imp: C_NsAsm_ExactImport): C_NsImp_Result<C_NsAsm_Def> {
        val nsRes = resolveNamespaceByPath(imp.module, imp.path)
        val ns = nsRes.value
        if (ns == null) {
            return nsRes.castType()
        }

        val defRes = resolveDef(ns, imp.name.str)
        return convertResult(imp.name, defRes)
    }

    private fun resolveDef(ns: C_NsAsm_Namespace, name: String): C_RecursionSafeResult<S_Name, C_NsAsm_Def> {
        val key = NsNameKey(ns, name)
        val res = resolveDefCalc.calculate(key)
        return res
    }

    private fun resolveDef0(ns: C_NsAsm_Namespace, name: String): C_RecursionSafeResult<S_Name, C_NsAsm_Def> {
        val def0 = ns.defs[name]
        if (def0 != null) {
            return C_RecursionSafeResult(def0)
        }

        val defs = mutableListOf<C_NsAsm_Def>()
        defs.addAll(ns.importDefs.get(name))

        val queue: Queue<C_NsAsm_WildcardImport> = ArrayDeque()
        val set = mutableSetOf<C_NsAsm_Namespace>()

        queue.addAll(ns.wildcardImports)
        set.add(ns)

        while (queue.isNotEmpty() && defs.size < 2) {
            val wildcard = queue.remove()
            val importedNs = resolveNamespaceByPath(wildcard.module, wildcard.path).value
            if (importedNs != null && set.add(importedNs)) {
                queue.addAll(importedNs.wildcardImports)
                val def = importedNs.defs[name]
                if (def != null) {
                    defs.add(def)
                } else {
                    defs.addAll(importedNs.importDefs.get(name))
                }
            }
        }

        if (defs.isEmpty()) {
            return C_RecursionSafeResult.error {
                C_Error.stop(it.pos, "import:name_unknown:${it.str}", "Unknown name: '${it.str}'")
            }
        } else if (defs.size >= 2) {
            return C_RecursionSafeResult.error {
                C_Error.stop(it.pos, "import:name_ambig:${it.str}", "Name '${it.str}' is ambiguous")
            }
        }

        val def = defs[0]
        return C_RecursionSafeResult(def)
    }

    fun namespaceClosure(ns: C_NsAsm_Namespace): List<C_NsAsm_Namespace> {
        val queue: Queue<C_NsAsm_WildcardImport> = ArrayDeque()
        val set = mutableSetOf<C_NsAsm_Namespace>()

        set.add(ns)
        queue.addAll(ns.wildcardImports)

        while (queue.isNotEmpty()) {
            val wildcard = queue.remove()
            val importedNs = resolveNamespaceByPath(wildcard.module, wildcard.path).value
            if (importedNs != null && set.add(importedNs)) {
                queue.addAll(importedNs.wildcardImports)
            }
        }

        return set.toImmList()
    }

    private fun <T> convertResult(name: S_Name, heteroRes: C_RecursionSafeResult<S_Name, T>): C_NsImp_Result<T> {
        return if (heteroRes.value != null) {
            C_NsImp_Result(heteroRes.value)
        } else {
            C_NsImp_Result.error {
                heteroRes.error(name)
            }
        }
    }
}

private class C_NsImp_Result<T> private constructor(val value: T?, val error: Getter<C_Error>) {
    constructor(value: T): this(value, { throw IllegalStateException("error: no error") })

    fun <R> castType(): C_NsImp_Result<R> {
        check(value == null)
        return C_NsImp_Result(null, error)
    }

    fun valueOrReport(msgCtx: C_MessageContext): T? {
        if (value == null) {
            val err = error()
            msgCtx.error(err)
        }
        return value
    }

    companion object {
        fun <T> error(error: Getter<C_Error>) = C_NsImp_Result<T>(null, error)
    }
}
