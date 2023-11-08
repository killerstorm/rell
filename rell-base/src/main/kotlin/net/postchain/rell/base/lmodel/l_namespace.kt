/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import com.google.common.collect.Iterables
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

sealed class L_NamespaceMember(
    val qualifiedName: R_QualifiedName,
    override val docSymbol: DocSymbol,
): DocDefinition {
    val simpleName: R_Name = qualifiedName.last

    abstract fun strCode(): String
}

class L_Namespace(members: List<L_NamespaceMember>) {
    val members: List<L_NamespaceMember> = members.toImmList()

    private val namespaces: Map<R_Name, L_Namespace> = let {
        val map = mutableMapOf<R_Name, L_Namespace>()
        for (member in this.members) {
            if (member is L_NamespaceMember_Namespace) {
                check(member.simpleName !in map) { "Name conflict: ${member.qualifiedName}" }
                map[member.simpleName] = member.namespace
            }
        }
        map.toImmMap()
    }

    // Ignoring name conflicts, assuming clients ask for unique entries (e.g. a type, not a function).
    private val membersMap: Map<R_Name, L_NamespaceMember> = let {
        val map = mutableMapOf<R_Name, L_NamespaceMember>()
        for (member in this.members) {
            map.putIfAbsent(member.simpleName, member)
        }
        map.toImmMap()
    }

    private val extensionTypes = this.members
        .mapNotNull {
            if (it is L_NamespaceMember_Type && it.typeDef.extension) it.typeDef else null
        }
        .toImmList()

    fun getDef(qName: R_QualifiedName): L_NamespaceMember {
        val def = getDefOrNull(qName)
        checkNotNull(def) { "Definition not found: $qName" }
        return def
    }

    fun getDefOrNull(qName: R_QualifiedName): L_NamespaceMember? {
        var ns = this
        for (rName in qName.parts.dropLast(1)) {
            val nextNs = ns.namespaces[rName]
            nextNs ?: return null
            ns = nextNs
        }
        return ns.membersMap[qName.last]
    }

    fun getAllDefs(): List<L_NamespaceMember> {
        val res = mutableListOf<L_NamespaceMember>()
        getAllDefs0(res)
        return res.toImmList()
    }

    private fun getAllDefs0(res: MutableList<L_NamespaceMember>) {
        for (def in members) {
            res.add(def)
            if (def is L_NamespaceMember_Namespace) {
                def.namespace.getAllDefs0(res)
            }
        }
    }

    fun allExtensionTypes(): List<L_TypeDef> {
        return allExtensionTypes0().toImmList()
    }

    private fun allExtensionTypes0(): Iterable<L_TypeDef> {
        val subTypes = Iterables.concat(Iterables.transform(namespaces.values) { it.allExtensionTypes0() })
        return Iterables.concat(extensionTypes, subTypes)
    }

    fun getDocMemberOrNull(name: String): DocDefinition? {
        val rName = R_Name.of(name)
        val mem = membersMap[rName]
        return mem
    }

    companion object {
        val EMPTY = L_Namespace(immListOf())
    }
}

class L_NamespaceMember_Namespace(
    qualifiedName: R_QualifiedName,
    val namespace: L_Namespace,
    doc: DocSymbol,
): L_NamespaceMember(qualifiedName, doc) {
    override fun strCode() = "namespace $qualifiedName"

    override fun getDocMember(name: String): DocDefinition? {
        return namespace.getDocMemberOrNull(name)
    }
}
