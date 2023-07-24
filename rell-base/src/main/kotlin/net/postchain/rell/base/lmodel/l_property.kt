/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_TypeParam
import net.postchain.rell.base.mtype.M_TypeSet

class L_NamespaceProperty(val type: M_Type, val ideInfo: IdeSymbolInfo, val fn: C_SysFunction)

class L_NamespaceMember_Property(
    qualifiedName: R_QualifiedName,
    val property: L_NamespaceProperty,
): L_NamespaceMember(qualifiedName) {
    override fun strCode() = "property $qualifiedName: ${property.type.strCode()}"
}

class L_NamespaceMember_SpecialProperty(
    qualifiedName: R_QualifiedName,
    val property: C_NamespaceProperty,
): L_NamespaceMember(qualifiedName) {
    override fun strCode() = "property $qualifiedName"
}

class L_TypeProperty(val simpleName: R_Name, val type: M_Type, val fn: C_SysFunction) {
    fun strCode() = "property $simpleName: ${type.strCode()}"

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_TypeProperty {
        val type2 = type.replaceParamsOut(map)
        return if (type2 === type) this else L_TypeProperty(simpleName, type2, fn = fn)
    }
}

class L_TypeDefMember_Property(val simpleName: R_Name, val property: L_TypeProperty): L_TypeDefMember() {
    override fun strCode() = property.strCode()

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_TypeDefMember_Property {
        val property2 = property.replaceTypeParams(map)
        return if (property2 === property) this else L_TypeDefMember_Property(simpleName, property2)
    }
}
