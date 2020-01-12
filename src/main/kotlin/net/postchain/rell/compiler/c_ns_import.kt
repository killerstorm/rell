package net.postchain.rell.compiler

import net.postchain.rell.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.model.R_Type
import java.util.*

object C_NsImp_ImportsProcessor {
    fun process(
            globalCtx: C_GlobalContext,
            modules: Map<C_ModuleKey, C_NsAsm_Namespace>
    ): Map<C_ModuleKey, C_NsAsm_Namespace> {
        val processor = C_NsImp_InternalImportsProcessor(globalCtx, modules)
        return processor.process()
    }
}

private class C_NsImp_InternalImportsProcessor(
        private val globalCtx: C_GlobalContext,
        private val modules: Map<C_ModuleKey, C_NsAsm_Namespace>
) {
    private val importResolver = C_NsImp_ImportResolver(modules)
    private val states = mutableMapOf<C_NsAsm_Namespace, C_NsImp_NamespaceState>()

    fun process(): Map<C_ModuleKey, C_NsAsm_Namespace> {
        val res = mutableMapOf<C_ModuleKey, C_NsAsm_Namespace>()

        for ((module, ns) in modules) {
            val state = processNamespace(ns)
            val nsGetter = state.getAsmNamespaceGetter()
            res[module] = nsGetter()
        }

        return res.toImmMap()
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

        val closure = importResolver.namespaceClosure(targetNs)
        for (ns in closure.filter { it !== targetNs }) {
            for ((name, def) in ns.defs) {
                processImportDef(builder, name, def)
            }
        }

        val resNs = builder.build()
        state.initNamespace(resNs)
    }

    private fun processDirectDef(builder: C_NsImp_NamespaceBuilder, name: String, def: C_NsAsm_Def) {
        return when (def) {
            is C_NsAsm_Def_Simple -> {
                builder.addDirectDef(name, def)
            }
            is C_NsAsm_Def_ExactImport -> {
                processDirectExactImport(builder, name, def.imp)
            }
            is C_NsAsm_Def_Namespace -> {
                val ns = def.ns()
                val nsState = processNamespace(ns)
                val nsGetter = nsState.getAsmNamespaceGetter()
                val nsDef = C_NsAsm_Def_Namespace.createLate(nsGetter)
                builder.addDirectDef(name, nsDef)
            }
        }
    }

    private fun processDirectExactImport(builder: C_NsImp_NamespaceBuilder, name: String, imp: C_NsAsm_ExactImport) {
        val res = importResolver.resolveExactImport(imp)
        val def = res.valueOrReport(globalCtx)
        return when (def) {
            null -> {}
            is C_NsAsm_Def_Simple -> {
                builder.addDirectDef(name, def)
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
        val nsProxy = C_DefProxy.createGetter(nsGetter)
        val nsDef = C_NsDef_UserNamespace(nsProxy)
        val elem = nsDef.toNamespaceElement()
        builder.addImportDef(name, elem)
    }

    private fun processDirectWildcardImport(wildcard: C_NsAsm_WildcardImport) {
        val res = importResolver.resolveNamespaceByPath(wildcard.module, wildcard.path)
        res.valueOrReport(globalCtx)
    }

    private fun getState(ns: C_NsAsm_Namespace) = states.computeIfAbsent(ns) { C_NsImp_NamespaceState(ns.setter) }

    private class C_NsImp_NamespaceState(private val oldSetter: Setter<C_Namespace>) {
        var processed = false

        private val asmNsInit = LateInit<C_NsAsm_Namespace>()
        private val nsInit = LateInit<C_Namespace>()
        private val ref: C_DefProxy<C_Namespace>

        init {
            val getter = nsInit.getter
            ref = C_DefProxy.createGetter(getter)
        }

        fun initNamespace(impNs: C_NsImp_Namespace) {
            val setter = CommonUtils.compoundSetter(listOf(oldSetter, nsInit.setter))
            val asmNs = C_NsAsm_Namespace(impNs.defs, listOf(), setter)
            asmNsInit.set(asmNs)
        }

        fun getNamespaceGetter() = nsInit.getter
        fun getAsmNamespaceGetter() = asmNsInit.getter
    }
}

private class C_NsImp_Namespace(defs: Map<String, C_NsAsm_Def>) {
    val defs = defs.toImmMap()
}

private class C_NsImp_NamespaceBuilder {
    private val directDefs = mutableMapOf<String, C_NsAsm_Def>()
    private val types = BuilderTable<R_Type>()
    private val namespaces = BuilderTable<C_Namespace>()
    private val values = BuilderTable<C_NamespaceValue>()
    private val functions = BuilderTable<C_GlobalFunction>()

    fun addDirectDef(name: String, def: C_NsAsm_Def) {
        check(name !in directDefs)
        directDefs[name] = def
    }

    fun addImportDef(name: String, elem: C_NamespaceElement) {
        types.add(name, elem.type)
        namespaces.add(name, elem.namespace)
        values.add(name, elem.value)
        functions.add(name, elem.function)
    }

    fun build(): C_NsImp_Namespace {
        val defs = mutableMapOf<String, C_NsAsm_Def>()

        for ((name, def) in directDefs) {
            val impElem = getImportElem(name)
            val resDef = mergeDefs(def, impElem)
            defs[name] = resDef
        }

        val names = types.keys() + namespaces.keys() + values.keys() + functions.keys()

        for (name in names) {
            if (name !in defs) {
                val elem = getImportElem(name)
                defs[name] = C_NsAsm_Def_Simple(elem)
            }
        }

        return C_NsImp_Namespace(defs.toImmMap())
    }

    private fun mergeDefs(def: C_NsAsm_Def, elem: C_NamespaceElement): C_NsAsm_Def {
        return if (def !is C_NsAsm_Def_Simple) def else {
            val resElem = C_NamespaceElement(
                    type = def.elem.type ?: elem.type,
                    namespace = def.elem.namespace ?: elem.namespace,
                    value = def.elem.value ?: elem.value,
                    function = def.elem.function ?: elem.function
            )
            C_NsAsm_Def_Simple(resElem)
        }
    }

    private fun getImportElem(name: String): C_NamespaceElement {
        return C_NamespaceElement(
                type = types.get(name),
                namespace = namespaces.get(name),
                value = values.get(name),
                function = functions.get(name)
        )
    }

    private class BuilderTable<T> {
        private val map = multiMapOf<String, C_DefProxy<T>>()

        fun add(name: String, def: C_DefProxy<T>?) {
            if (def != null) {
                map.put(name, def)
            }
        }

        fun keys() = map.keySet().toImmSet()

        fun get(name: String): C_DefProxy<T>? {
            val defs = map[name]
            return buildEntry(defs)
        }

        private fun buildEntry(defs: Collection<C_DefProxy<T>>): C_DefProxy<T>? {
            return if (defs.isEmpty()) null else {
                val def = defs.iterator().next()
                if (defs.size == 1) def else C_AmbiguousDefProxy(def)
            }
        }
    }
}

private class C_NsImp_ImportResolver(private val modules: Map<C_ModuleKey, C_NsAsm_Namespace>) {
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
        return C_Error(name.pos, "import:recursion:${name.str}", "Name '${name.str}' is a recursive definition")
    }

    fun resolveNamespaceByPath(module: C_ModuleKey, path: List<S_Name>): C_NsImp_Result<C_NsAsm_Namespace> {
        var ns = modules[module] ?: C_NsAsm_Namespace.EMPTY
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
                    C_Error(it.pos, "import:name_unresolved:${it.str}", "Cannot resolve name '${it.str}'")
                }
            }
        }

        if (def !is C_NsAsm_Def_Namespace) {
            return C_RecursionSafeResult.error {
                C_Error(it.pos, "import:not_ns:${it.str}", "Name '${it.str}' is not a namespace")
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
        val fullName = C_Utils.appLevelName(imp.module, imp.path + imp.name)
        val code = "import:exact:unresolved:$fullName"
        return C_Error(imp.name.pos, code, "Cannot resolve import: '$fullName'")
    }

    private fun errExactRecursion(imp: C_NsAsm_ExactImport): C_Error {
        val fullName = C_Utils.appLevelName(imp.module, imp.path + imp.name)
        val code = "import:exact:recursion:$fullName"
        return C_Error(imp.name.pos, code, "Recursive import: '$fullName' points to itself")
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
                }
            }
        }

        if (defs.isEmpty()) {
            return C_RecursionSafeResult.error {
                C_Error(it.pos, "import:name_unknown:${it.str}", "Unknown name: '${it.str}'")
            }
        } else if (defs.size >= 2) {
            return C_RecursionSafeResult.error {
                C_Error(it.pos, "import:name_ambig:${it.str}", "Name '${it.str}' is ambiguous")
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

    fun valueOrReport(globalCtx: C_GlobalContext): T? {
        if (value == null) {
            val err = error()
            globalCtx.error(err)
        }
        return value
    }

    companion object {
        fun <T> error(error: Getter<C_Error>) = C_NsImp_Result<T>(null, error)
    }
}
