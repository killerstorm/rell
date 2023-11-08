/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.mtype.M_TypeParam
import net.postchain.rell.base.utils.doc.DocSymbol

class L_ConstructorHeader(
    val typeParams: List<M_TypeParam>,
    val params: List<L_FunctionParam>,
) {
    fun strCode(): String {
        val parts = mutableListOf<String>()
        if (typeParams.isNotEmpty()) parts.add(typeParams.joinToString(",", "<", ">") { it.strCode() })
        parts.add(params.joinToString(", ", "(", ")") { it.strCode() })
        return parts.joinToString(" ")
    }
}

class L_Constructor(
    val header: L_ConstructorHeader,
    val deprecated: C_Deprecated?,
    val body: L_FunctionBody,
    val pure: Boolean,
) {
    fun strCode(): String {
        val parts = mutableListOf<String>()
        if (deprecated != null) parts.add("@deprecated")
        if (pure) parts.add("pure")
        parts.add("constructor")
        parts.add(header.strCode())
        return parts.joinToString(" ")
    }
}

class L_TypeDefMember_Constructor(val constructor: L_Constructor, doc: DocSymbol): L_TypeDefMember("!init", doc) {
    override fun strCode() = constructor.strCode()
}
