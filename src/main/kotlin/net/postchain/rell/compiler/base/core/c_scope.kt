/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_LateInit
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.R_EntityDefinition
import net.postchain.rell.model.R_ObjectDefinition
import net.postchain.rell.model.R_OperationDefinition
import net.postchain.rell.model.R_Type
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.Getter
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class C_ScopeBuilder {
    private val msgCtx: C_MessageContext
    private val scope: C_Scope

    constructor(msgCtx: C_MessageContext): this(msgCtx, null, { C_Namespace.EMPTY })

    private constructor(msgCtx: C_MessageContext, parentScope: C_Scope?, nsGetter: Getter<C_Namespace>) {
        this.msgCtx = msgCtx
        this.scope = C_Scope(msgCtx, parentScope, nsGetter)
    }

    fun nested(nsGetter: Getter<C_Namespace>): C_ScopeBuilder {
        return C_ScopeBuilder(msgCtx, scope, nsGetter)
    }

    fun scope() = scope
}

class C_DefResolution<T>(
        private val def: T,
        private val defRef: C_DefRef<*>,
        private val qNameHand: C_QualifiedNameHandle,
        ideInfos: List<IdeSymbolInfo>
) {
    val qName = qNameHand.qName

    private val ideInfos = let {
        val l = ideInfos.toImmList()
        checkEquals(l.size, qName.parts.size)
        l
    }

    fun getDef(): T {
        defRef.getDef() // Check access.
        for ((i, nameHand) in qNameHand.parts.withIndex()) {
            nameHand.setIdeInfo(ideInfos[i])
        }
        return def
    }
}

sealed class C_ScopeDefSelector<T> {
    companion object {
        val TYPE: C_ScopeDefSelector<R_Type> = C_ScopeDefSelector_Type
        val ENTITY: C_ScopeDefSelector<R_EntityDefinition> = C_ScopeDefSelector_Entity
        val OBJECT: C_ScopeDefSelector<R_ObjectDefinition> = C_ScopeDefSelector_Object
        val VALUE: C_ScopeDefSelector<C_NamespaceValue> = C_ScopeDefSelector_Value
        val FUNCTION: C_ScopeDefSelector<C_GlobalFunction> = C_ScopeDefSelector_Function
        val OPERATION: C_ScopeDefSelector<R_OperationDefinition> = C_ScopeDefSelector_Operation
    }
}

private sealed class C_PrivateScopeDefSelector<T>(val kind: C_CodeMsg): C_ScopeDefSelector<T>() {
    abstract fun getQualifiedDef(
            rootNs: C_ScopeNs,
            qNameHand: C_QualifiedNameHandle,
            setUnknownInfo: Boolean
    ): C_DefResolution<T>?
}

private sealed class C_GenericScopeDefSelector<T, P>(
        private val pass: C_CompilerPass,
        kind: C_CodeMsg,
        private val transformer: C_DefTransformer<P, T>
): C_PrivateScopeDefSelector<T>(kind) {
    protected abstract fun getDef(ns: C_ScopeNs, name: C_Name): C_DefRef<P>?

    final override fun getQualifiedDef(
            rootNs: C_ScopeNs,
            qNameHand: C_QualifiedNameHandle,
            setUnknownInfo: Boolean
    ): C_DefResolution<T>? {
        C_LateInit.checkPass(pass, null)

        val ideInfos = mutableListOf<IdeSymbolInfo>()
        val defRef = resolveDef(rootNs, qNameHand, ideInfos)

        if (defRef == null) {
            if (setUnknownInfo) {
                for ((i, ideInfo) in ideInfos.withIndex()) qNameHand.parts[i].setIdeInfo(ideInfo)
                for (i in ideInfos.size until qNameHand.parts.size) qNameHand.parts[i].setIdeInfo(IdeSymbolInfo.UNKNOWN)
            }
            return null
        }

        val res = defRef.toResolution(qNameHand, ideInfos, transformer)
        return res
    }

    private fun resolveDef(
            rootNs: C_ScopeNs,
            qNameHand: C_QualifiedNameHandle,
            ideInfos: MutableList<IdeSymbolInfo>
    ): C_DefRef<P>? {
        var ns = rootNs

        for (nameHand in qNameHand.parentParts()) {
            val pair = ns.sub(nameHand.name)
            if (pair == null) return null
            ideInfos.add(pair.second)
            ns = pair.first
        }

        val defRef = getDef(ns, qNameHand.last.name)
        if (defRef != null) {
            ideInfos.add(defRef.ideSymbolInfo())
        }

        return defRef
    }
}

private object C_ScopeDefSelector_Type: C_GenericScopeDefSelector<R_Type, R_Type>(
        C_CompilerPass.MEMBERS,
        "type" toCodeMsg "type",
        C_DefTransformer_None()
) {
    override fun getDef(ns: C_ScopeNs, name: C_Name) = ns.type(name)
}

private object C_ScopeDefSelector_Entity: C_GenericScopeDefSelector<R_EntityDefinition, R_Type>(
        C_CompilerPass.MEMBERS,
        "entity" toCodeMsg "entity",
        C_DefTransformer_Entity
) {
    override fun getDef(ns: C_ScopeNs, name: C_Name) = ns.type(name)
}

private object C_ScopeDefSelector_Value: C_GenericScopeDefSelector<C_NamespaceValue, C_NamespaceValue>(
        C_CompilerPass.EXPRESSIONS,
        "value" toCodeMsg "name",
        C_DefTransformer_None()
) {
    override fun getDef(ns: C_ScopeNs, name: C_Name) = ns.value(name)
}

private object C_ScopeDefSelector_Object: C_GenericScopeDefSelector<R_ObjectDefinition, C_NamespaceValue>(
        C_CompilerPass.MEMBERS,
        "object" toCodeMsg "object",
        C_DefTransformer_Object
) {
    override fun getDef(ns: C_ScopeNs, name: C_Name) = ns.value(name)
}

private object C_ScopeDefSelector_Function: C_GenericScopeDefSelector<C_GlobalFunction, C_GlobalFunction>(
        C_CompilerPass.MEMBERS,
        "function" toCodeMsg "function",
        C_DefTransformer_None()
) {
    override fun getDef(ns: C_ScopeNs, name: C_Name) = ns.function(name)
}

private object C_ScopeDefSelector_Operation: C_GenericScopeDefSelector<R_OperationDefinition, C_GlobalFunction>(
        C_CompilerPass.MEMBERS,
        "operation" toCodeMsg "operation",
        C_DefTransformer_Operation
) {
    override fun getDef(ns: C_ScopeNs, name: C_Name) = ns.function(name)
}

private interface C_ScopeNsAdapter {
    fun <T> lookup(getter: (C_NamespaceRef) -> C_DefRef<T>?): C_DefRef<T>?
}

private abstract class C_ScopeNs(qName: List<C_Name>) {
    private val qName = qName.toImmList()

    abstract fun type(name: C_Name): C_DefRef<R_Type>?
    abstract fun namespace(name: C_Name): C_DefRef<C_Namespace>?
    abstract fun value(name: C_Name): C_DefRef<C_NamespaceValue>?
    abstract fun function(name: C_Name): C_DefRef<C_GlobalFunction>?

    fun sub(name: C_Name): Pair<C_ScopeNs, IdeSymbolInfo>? {
        val subRef = namespace(name)
        return if (subRef == null) null else {
            val subNsRef = C_NamespaceRef.create(subRef)
            val ideInfo = subRef.ideSymbolInfo()
            Pair(C_SubScopeNs(qName + name, subNsRef), ideInfo)
        }
    }
}

private class C_RootScopeNs(private val scope: C_ScopeNsAdapter): C_ScopeNs(immListOf()) {
    override fun type(name: C_Name) = scope.lookup { it.type(name) }
    override fun namespace(name: C_Name) = scope.lookup { it.namespace(name) }
    override fun value(name: C_Name) = scope.lookup { it.value(name) }
    override fun function(name: C_Name) = scope.lookup { it.function(name) }
}

private class C_SubScopeNs(qName: List<C_Name>, private val nsRef: C_NamespaceRef): C_ScopeNs(qName) {
    override fun type(name: C_Name) = nsRef.type(name)
    override fun namespace(name: C_Name) = nsRef.namespace(name)
    override fun value(name: C_Name) = nsRef.value(name)
    override fun function(name: C_Name) = nsRef.function(name)
}

class C_Scope(
        private val msgCtx: C_MessageContext,
        private val parent: C_Scope?,
        private val nsGetter: Getter<C_Namespace>
) {
    private val rootNsRef: C_NamespaceRef by lazy {
        C_NamespaceRef(msgCtx, listOf(), nsGetter())
    }

    private val rootScopeNs: C_ScopeNs = C_RootScopeNs(C_ScopeNsAdapterImpl())

    fun <T> getDefOpt(
            nameHand: C_QualifiedNameHandle,
            selector: C_ScopeDefSelector<T>,
            setUnknownInfo: Boolean
    ): C_DefResolution<T>? {
        val selector0 = when (selector) {
            is C_PrivateScopeDefSelector<T> -> selector
        }
        val res = selector0.getQualifiedDef(rootScopeNs, nameHand, setUnknownInfo)
        return res
    }

    fun <T> getDef(nameHand: C_QualifiedNameHandle, selector: C_ScopeDefSelector<T>): T {
        val selector0 = when (selector) {
            is C_PrivateScopeDefSelector<T> -> selector
        }

        val res = selector0.getQualifiedDef(rootScopeNs, nameHand, true)
        if (res == null) {
            val code = "unknown_def:${selector0.kind.code}:$nameHand"
            val msg = "Unknown ${selector0.kind.msg}: '$nameHand'"
            throw C_Error.stop(nameHand.pos, code, msg)
        }

        return res.getDef()
    }

    private fun <T> lookup0(getter: (C_NamespaceRef) -> C_DefRef<T>?): C_DefRef<T>? {
        var scope: C_Scope? = this
        while (scope != null) {
            val def = getter(scope.rootNsRef)
            if (def != null) return def
            scope = scope.parent
        }
        return null
    }

    private inner class C_ScopeNsAdapterImpl: C_ScopeNsAdapter {
        override fun <T> lookup(getter: (C_NamespaceRef) -> C_DefRef<T>?) = lookup0(getter)
    }
}
