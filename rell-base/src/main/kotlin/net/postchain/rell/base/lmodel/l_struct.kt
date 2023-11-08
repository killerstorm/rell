/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Struct
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol

class L_StructAttribute(
    val name: R_Name,
    val type: M_Type,
    val mutable: Boolean,
    override val docSymbol: DocSymbol,
): DocDefinition

class L_Struct(
    val simpleName: R_Name,
    val rStruct: R_Struct,
    val attributesMap: Map<String, L_StructAttribute>,
)

class L_NamespaceMember_Struct(
    qualifiedName: R_QualifiedName,
    doc: DocSymbol,
    val struct: L_Struct,
): L_NamespaceMember(qualifiedName, doc) {
    override fun strCode() = "struct $qualifiedName"

    override fun getDocMember(name: String): DocDefinition? {
        return struct.attributesMap[name]
    }
}
