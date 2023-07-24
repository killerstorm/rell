/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProto
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_RNamePath
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_Struct
import net.postchain.rell.base.utils.immMapOf
import net.postchain.rell.base.utils.toImmMap

class C_LibNamespace private constructor(
    private val namePath: C_RNamePath,
    private val members: Map<R_Name, C_LibNamespaceMember>,
) {
    fun toSysNsProto(): C_SysNsProto {
        val defPath = namePath.toDefPath()
        val b = C_SysNsProtoBuilder(defPath)
        for ((name, member) in members) {
            member.toSysNsProto(b, name)
        }
        return b.build()
    }

    abstract class Maker(val basePath: C_RNamePath) {
        abstract fun addMember(name: R_Name, member: C_LibNamespaceMember)
        abstract fun addNamespace(name: R_Name, block: (Maker) -> Unit)
    }

    class Builder private constructor(
        basePath: C_RNamePath,
        private var active: Boolean,
    ): Maker(basePath) {
        constructor(basePath: C_RNamePath): this(basePath, active = true)

        private var done = false

        private val members = mutableMapOf<R_Name, BuilderMember>()
        private val namespaces = mutableMapOf<R_Name, BuilderMember_Namespace>()

        override fun addMember(name: R_Name, member: C_LibNamespaceMember) {
            check(active)
            check(!done)
            checkNameConflict(name)
            members[name] = BuilderMember_Simple(member)
        }

        override fun addNamespace(name: R_Name, block: (Maker) -> Unit) {
            check(active)
            check(!done)

            var ns = namespaces[name]
            if (ns == null) {
                checkNameConflict(name)
                val builder = Builder(basePath.append(name), active = false)
                ns = BuilderMember_Namespace(builder)
                namespaces[name] = ns
                members[name] = ns
            }

            check(!ns.builder.active)
            check(!ns.builder.done)
            ns.builder.active = true
            active = false

            block(ns.builder)

            ns.builder.active = false
            active = true
        }

        private fun checkNameConflict(name: R_Name) {
            check(name !in members) {
                val fullName = basePath.qualifiedName(name)
                "Name conflict: ${fullName.str()}"
            }
        }

        fun build(): C_LibNamespace {
            check(!done)
            done = true

            val resMembers = members
                .mapValues { it.value.build() }
                .toImmMap()

            return if (resMembers.isEmpty()) EMPTY else C_LibNamespace(basePath, resMembers)
        }

        private abstract class BuilderMember {
            abstract fun build(): C_LibNamespaceMember
        }

        private class BuilderMember_Simple(val member: C_LibNamespaceMember): BuilderMember() {
            override fun build() = member
        }

        private class BuilderMember_Namespace(val builder: Builder): BuilderMember() {
            override fun build(): C_LibNamespaceMember {
                val ns = builder.build()
                return C_LibNamespaceMember_Namespace(ns)
            }
        }
    }

    companion object {
        val EMPTY: C_LibNamespace = C_LibNamespace(C_RNamePath.EMPTY, immMapOf())

        fun merge(namespaces: List<C_LibNamespace>): C_LibNamespace {
            val b = Builder(C_RNamePath.EMPTY)
            for (ns in namespaces) {
                mergeNamespace(b, ns)
            }
            return b.build()
        }

        private fun mergeNamespace(m: Maker, ns: C_LibNamespace) {
            for ((name, member) in ns.members) {
                mergeMember(m, name, member)
            }
        }

        private fun mergeMember(m: Maker, name: R_Name, member: C_LibNamespaceMember) {
            when (member) {
                is C_LibNamespaceMember_Namespace -> {
                    m.addNamespace(name) { subB ->
                        mergeNamespace(subB, member.namespace)
                    }
                }
                else -> {
                    m.addMember(name, member)
                }
            }
        }
    }
}

sealed class C_LibNamespaceMember {
    abstract fun toSysNsProto(b: C_SysNsProtoBuilder, name: R_Name)

    companion object {
        fun makeType(typeDef: C_TypeDef, deprecated: C_Deprecated?): C_LibNamespaceMember {
            return C_LibNamespaceMember_Type(typeDef, deprecated)
        }

        fun makeStruct(rStruct: R_Struct): C_LibNamespaceMember {
            return C_LibNamespaceMember_Struct(rStruct)
        }

        fun makeProperty(property: C_NamespaceProperty): C_LibNamespaceMember {
            return C_LibNamespaceMember_Property(property)
        }

        fun makeFunction(function: C_GlobalFunction): C_LibNamespaceMember {
            return C_LibNamespaceMember_Function(function)
        }
    }
}

private class C_LibNamespaceMember_Namespace(
    val namespace: C_LibNamespace,
): C_LibNamespaceMember() {
    override fun toSysNsProto(b: C_SysNsProtoBuilder, name: R_Name) {
        val ns = namespace.toSysNsProto().toNamespace()
        b.addNamespace(name.str, ns)
    }
}

private class C_LibNamespaceMember_Type(
    private val typeDef: C_TypeDef,
    private val deprecated: C_Deprecated?,
): C_LibNamespaceMember() {
    override fun toSysNsProto(b: C_SysNsProtoBuilder, name: R_Name) {
        b.addType(name, typeDef, deprecated)
    }
}

private class C_LibNamespaceMember_Struct(
    private val rStruct: R_Struct,
): C_LibNamespaceMember() {
    override fun toSysNsProto(b: C_SysNsProtoBuilder, name: R_Name) {
        b.addStruct(name.str, rStruct)
    }
}

private class C_LibNamespaceMember_Property(
    private val property: C_NamespaceProperty,
): C_LibNamespaceMember() {
    override fun toSysNsProto(b: C_SysNsProtoBuilder, name: R_Name) {
        b.addProperty(name, property)
    }
}

private class C_LibNamespaceMember_Function(
    private val function: C_GlobalFunction,
): C_LibNamespaceMember() {
    override fun toSysNsProto(b: C_SysNsProtoBuilder, name: R_Name) {
        b.addFunction(name, function)
    }
}