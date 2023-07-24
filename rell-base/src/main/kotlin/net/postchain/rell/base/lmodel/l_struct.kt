/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Struct
import net.postchain.rell.base.mtype.M_Type

class L_StructAttribute(val name: R_Name, val type: M_Type)
class L_Struct(val simpleName: R_Name, val rStruct: R_Struct)

class L_NamespaceMember_Struct(
    qualifiedName: R_QualifiedName,
    val struct: L_Struct,
): L_NamespaceMember(qualifiedName) {
    override fun strCode() = "struct $qualifiedName"
}
