/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol

class L_Module(
    val moduleName: R_ModuleName,
    val namespace: L_Namespace,
    val allImports: List<L_Module>,
    override val docSymbol: DocSymbol,
): DocDefinition {
    fun getTypeDef(qualifiedName: String): L_TypeDef {
        val qName = R_QualifiedName.of(qualifiedName)
        return getTypeDef(qName)
    }

    fun getTypeDef(qualifiedName: R_QualifiedName): L_TypeDef {
        val def = getTypeDefOrNull(qualifiedName)
        return checkNotNull(def) { "Type not found: $qualifiedName" }
    }

    fun getTypeDefOrNull(qualifiedName: R_QualifiedName): L_TypeDef? {
        val def = namespace.getDefOrNull(qualifiedName)
        return (def as? L_NamespaceMember_Type)?.typeDef
    }

    fun getType(name: String): L_Type {
        val typeDef = getTypeDef(name)
        return typeDef.getType()
    }

    fun getStruct(qualifiedName: String): L_Struct {
        val qName = R_QualifiedName.of(qualifiedName)
        val def = namespace.getDef(qName)
        return (def as L_NamespaceMember_Struct).struct
    }

    fun getMTypeOrNull(qualifiedName: R_QualifiedName): M_Type? {
        val def = namespace.getDefOrNull(qualifiedName)
        return when (def) {
            is L_NamespaceMember_Type -> def.typeDef.mGenericType.getTypeSimple()
            is L_NamespaceMember_Struct -> def.struct.rStruct.type.mType
            else -> null
        }
    }

    override fun getDocMember(name: String): DocDefinition? {
        return namespace.getDocMemberOrNull(name)
    }
}
