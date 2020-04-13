/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.utils.LateGetter
import net.postchain.rell.utils.LateInit
import net.postchain.rell.utils.ListVsMap
import net.postchain.rell.utils.toImmList

object C_NsRes_ResultMaker {
    fun make(modules: Map<C_ModuleKey, C_NsImp_Namespace>): Map<C_ModuleKey, C_Namespace> {
        val (impList, listVsMap) = ListVsMap.mapToList(modules)
        val nsList = make0(impList)
        val nsMap = listVsMap.listToMap(nsList)
        return nsMap
    }

    fun make(impNs: C_NsImp_Namespace): C_Namespace {
        val nsList = make0(listOf(impNs))
        check(nsList.size == 1)
        return nsList[0]
    }

    private fun make0(impList: List<C_NsImp_Namespace>): List<C_Namespace> {
        val maker = C_NsRes_InternalMaker()
        val nsList = impList.map { impNs -> maker.makeModule(impNs) }.toImmList()
        return nsList
    }
}

private class C_NsRes_InternalMaker {
    private val nsMap = mutableMapOf<C_NsImp_Namespace, LateGetter<C_Namespace>>()

    fun makeModule(ns: C_NsImp_Namespace): C_Namespace {
        val getter = makeNamespace(ns)
        val resNs = getter.get()
        return resNs
    }

    private fun makeNamespace(ns: C_NsImp_Namespace): LateGetter<C_Namespace> {
        val getter = nsMap[ns]
        if (getter != null) return getter

        val init = LateInit<C_Namespace>()
        nsMap[ns] = init.getter

        val resNs = makeNamespace0(ns)
        init.set(resNs)

        return init.getter
    }

    private fun makeNamespace0(ns: C_NsImp_Namespace): C_Namespace {
        val b = C_NamespaceBuilder()
        val names = ns.directDefs.keys + ns.importDefs.keySet()

        for (name in names) {
            val directDef = ns.directDefs[name]
            val importDefs = ns.importDefs.get(name) ?: listOf()
            val elem = makeDef(directDef, importDefs)
            b.add(name, elem)
        }

        return b.build()
    }

    private fun makeDef(directDef: C_NsImp_Def?, importDefs: Collection<C_NsImp_Def>): C_NamespaceElement {
        val directElem = if (directDef == null) null else makeDef0(directDef)
        val importElems = importDefs.map { makeDef0(it) }
        val importElem = mergeElements(importElems)

        return C_NamespaceElement(
                namespace = directElem?.namespace ?: importElem?.namespace,
                type = directElem?.type ?: importElem?.type,
                value = directElem?.value ?: importElem?.value,
                function = directElem?.function ?: importElem?.function
        )
    }

    private fun makeDef0(def: C_NsImp_Def): C_NamespaceElement {
        return when (def) {
            is C_NsImp_Def_Simple -> def.elem
            is C_NsImp_Def_Namespace -> {
                val impNs = def.ns()
                val nsGetter = makeNamespace(impNs)
                val nsProxy = C_DefProxy.createGetter(nsGetter)
                C_NsDef_UserNamespace(nsProxy).toNamespaceElement()
            }
        }
    }

    private fun mergeElements(elems: List<C_NamespaceElement>): C_NamespaceElement? {
        return if (elems.isEmpty()) null else C_NamespaceElement(
                namespace = mergeDefs(elems) { it.namespace },
                type = mergeDefs(elems) { it.type },
                value = mergeDefs(elems) { it.value },
                function = mergeDefs(elems) { it.function }
        )
    }

    private fun <T> mergeDefs(elems: List<C_NamespaceElement>, getter: (C_NamespaceElement) -> C_DefProxy<T>?): C_DefProxy<T>? {
        val defs = elems.map(getter).filterNotNull()
        return if (defs.isEmpty()) {
            null
        } else if (defs.size == 1) {
            defs[0]
        } else {
            defs[0].update(ambiguous = true)
        }
    }
}
