/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import com.google.common.collect.Iterables
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

sealed class L_NamespaceMember(
    fullName: R_FullName,
    override val docSymbol: DocSymbol,
): DocDefinition {
    val fullName: R_FullName = R_FullName(fullName.moduleName, fullName.qualifiedName)
    val qualifiedName: R_QualifiedName = fullName.qualifiedName
    val simpleName: R_Name = qualifiedName.last

    abstract fun strCode(): String

    open fun getTypeDefOrNull(): L_TypeDef? = null
    open fun getAbstractTypeDefOrNull(): L_AbstractTypeDef? = null
    open fun getTypeExtensionOrNull(): L_TypeExtension? = null
    open fun getStructOrNull(): L_Struct? = null
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

    private val typeExtensions = this.members
        .mapNotNull { (it as? L_NamespaceMember_TypeExtension)?.typeExt }
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

    fun allTypeExtensions(): List<L_TypeExtension> {
        return allTypeExtensions0().toImmList()
    }

    private fun allTypeExtensions0(): Iterable<L_TypeExtension> {
        val nested = Iterables.concat(Iterables.transform(namespaces.values) { it.allTypeExtensions0() })
        return Iterables.concat(typeExtensions, nested)
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
    fullName: R_FullName,
    val namespace: L_Namespace,
    doc: DocSymbol,
): L_NamespaceMember(fullName, doc) {
    override fun strCode() = "namespace $qualifiedName"

    override fun getDocMember(name: String): DocDefinition? {
        return namespace.getDocMemberOrNull(name)
    }
}

class L_NamespaceMember_Alias(
    fullName: R_FullName,
    doc: DocSymbol,
    val targetMember: L_NamespaceMember,
    val finalTargetMember: L_NamespaceMember,
    val deprecated: C_Deprecated?,
): L_NamespaceMember(fullName, doc) {
    override fun strCode(): String {
        val parts = listOfNotNull(
            L_InternalUtils.deprecatedStrCodeOrNull(deprecated),
            "alias $qualifiedName = ${targetMember.qualifiedName}",
        )
        return parts.joinToString(" ")
    }

    override fun getTypeDefOrNull(): L_TypeDef? = finalTargetMember.getTypeDefOrNull()
    override fun getAbstractTypeDefOrNull(): L_AbstractTypeDef? = finalTargetMember.getAbstractTypeDefOrNull()
    override fun getTypeExtensionOrNull(): L_TypeExtension? = finalTargetMember.getTypeExtensionOrNull()
    override fun getStructOrNull(): L_Struct? = finalTargetMember.getStructOrNull()
}
