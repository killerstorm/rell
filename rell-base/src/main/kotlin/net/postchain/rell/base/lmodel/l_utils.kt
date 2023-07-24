/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import java.util.*

class L_FullName(val moduleName: R_ModuleName, val qName: R_QualifiedName) {
    val last: R_Name = qName.last

    fun str(): String = "${moduleName.str()}:${qName.str()}"

    override fun equals(other: Any?) =
        this === other || (other is L_FullName && moduleName == other.moduleName && qName == other.qName)

    override fun hashCode() = Objects.hash(moduleName, qName)

    override fun toString(): String = str()
}

object L_InternalUtils {
    fun deprecatedStrCode(deprecated: C_Deprecated): String {
        val level = if (deprecated.error) "ERROR" else "WARNING"
        return "@deprecated($level)"
    }
}
