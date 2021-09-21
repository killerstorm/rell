/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_QualifiedName
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.def.C_OperationGlobalFunction
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.*
import net.postchain.rell.utils.Getter

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

class C_Scope(
        private val msgCtx: C_MessageContext,
        private val parent: C_Scope?,
        private val nsGetter: Getter<C_Namespace>
) {
    private val rootNsRef: C_NamespaceRef by lazy { C_NamespaceRef(msgCtx, listOf(), nsGetter()) }

    fun getType(name: S_QualifiedName): R_Type {
        val typeZ = getTypeOpt(name)
        val type = C_Errors.checkNotNull(typeZ, name.pos) {
            val nameStr = name.str()
            "unknown_type:$nameStr" toCodeMsg "Unknown type: '$nameStr'"
        }
        return type
    }

    fun getTypeOpt(name: S_QualifiedName): R_Type? {
        val typeDef = getTypeDefOpt(name)
        return typeDef?.getDef()
    }

    private fun getTypeDefOpt(qName: S_QualifiedName): C_DefRef<R_Type>? {
        return getQualifiedDef(qName, GeneralNs::type)
    }

    fun getEntity(name: S_QualifiedName): R_EntityDefinition {
        val entity = getEntityOpt(name)
        if (entity == null) {
            val nameStr = name.str()
            throw C_Error.stop(name.pos, "unknown_entity:$nameStr", "Unknown entity: '$nameStr'")
        }
        return entity
    }

    fun getEntityOpt(name: S_QualifiedName): R_EntityDefinition? {
        val type = getTypeOpt(name)
        if (type == null) {
            return null
        }

        if (type !is R_EntityType) {
            val nameStr = name.str()
            throw C_Error.stop(name.pos, "unknown_entity:$nameStr", "Unknown entity: '$nameStr'")
        }

        return type.rEntity
    }

    fun getObjectOpt(name: S_QualifiedName): R_ObjectDefinition? {
        val value = getQualifiedDef(name, GeneralNs::value)
        return (value?.getDef() as? C_NamespaceValue_Object)?.rObject
    }

    fun getValueOpt(qName: S_QualifiedName): C_DefRef<C_NamespaceValue>? {
        return getQualifiedDef(qName, GeneralNs::value)
    }

    fun getFunctionOpt(qName: S_QualifiedName): C_DefRef<C_GlobalFunction>? {
        return getQualifiedDef(qName, GeneralNs::function)
    }

    fun getOperationOpt(qName: S_QualifiedName): R_OperationDefinition? {
        val def = getQualifiedDef(qName, GeneralNs::function)
        return (def?.getDef() as? C_OperationGlobalFunction)?.rOp
    }

    private fun <T> getQualifiedDef(qName: S_QualifiedName, getter: (GeneralNs, S_Name) -> C_DefRef<T>?): C_DefRef<T>? {
        val (ns, lastName) = processQualifiedName(qName)
        return if (ns == null) null else getter(ns, lastName)
    }

    private fun processQualifiedName(qName: S_QualifiedName): Pair<GeneralNs?, S_Name> {
        val lastName = qName.last

        var ns: GeneralNs = RootGeneralNs()
        for (i in 0 .. qName.parts.size - 2) {
            val ns2 = ns.sub(qName.parts[i])
            if (ns2 == null) return Pair(null, lastName)
            ns = ns2
        }

        return Pair(ns, lastName)
    }

    private fun <T> getDefOpt(getter: (C_NamespaceRef) -> C_DefRef<T>?): C_DefRef<T>? {
        var scope: C_Scope? = this
        while (scope != null) {
            val def = getter(scope.rootNsRef)
            if (def != null) return def
            scope = scope.parent
        }
        return null
    }

    private abstract inner class GeneralNs(private val qName: List<S_Name>) {
        abstract fun type(name: S_Name): C_DefRef<R_Type>?
        abstract fun namespace(name: S_Name): C_DefRef<C_Namespace>?
        abstract fun value(name: S_Name): C_DefRef<C_NamespaceValue>?
        abstract fun function(name: S_Name): C_DefRef<C_GlobalFunction>?

        fun sub(name: S_Name): GeneralNs? {
            val subRef = namespace(name)
            return if (subRef == null) null else {
                val subNsRef = C_NamespaceRef.create(subRef)
                SubGeneralNs(qName + name, subNsRef)
            }
        }
    }

    private inner class SubGeneralNs(qName: List<S_Name>, private val nsRef: C_NamespaceRef): GeneralNs(qName) {
        override fun type(name: S_Name) = nsRef.type(name)
        override fun namespace(name: S_Name) = nsRef.namespace(name)
        override fun value(name: S_Name) = nsRef.value(name)
        override fun function(name: S_Name) = nsRef.function(name)
    }

    private inner class RootGeneralNs: GeneralNs(listOf()) {
        override fun type(name: S_Name) = getDefOpt { it.type(name) }
        override fun namespace(name: S_Name) = getDefOpt { it.namespace(name) }
        override fun value(name: S_Name) = getDefOpt { it.value(name) }
        override fun function(name: S_Name) = getDefOpt { it.function(name) }
    }
}
