/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_TypeParam
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol

class L_TypeExtension(
    val qualifiedName: R_QualifiedName,
    val typeParams: List<M_TypeParam>,
    val selfType: M_Type,
    val members: L_TypeDefMembers,
    val docSymbol: DocSymbol,
) {
    fun strCode(): String {
        val parts = mutableListOf<String>()

        parts.add("extension ")
        parts.add(qualifiedName.str())

        if (typeParams.isNotEmpty()) {
            val s = typeParams.joinToString(",", "<", ">") { it.strCode() }
            parts.add(s)
        }

        parts.add(": ")
        parts.add(selfType.strCode())

        return parts.joinToString("")
    }
}

class L_NamespaceMember_TypeExtension(
    fullName: R_FullName,
    val typeExt: L_TypeExtension,
): L_NamespaceMember(fullName, typeExt.docSymbol) {
    override fun strCode(): String {
        return typeExt.strCode()
    }

    override fun getTypeExtensionOrNull() = typeExt

    override fun getDocMember(name: String): DocDefinition? {
        return typeExt.members.getDocDefinition(name)
    }
}
