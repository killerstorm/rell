/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.lib.C_LibType
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_OperationDefinition
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.GtvRtConversion_None
import net.postchain.rell.base.runtime.utils.toGtv
import net.postchain.rell.base.utils.checkEquals

object Lib_Type_Operation {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("operation", abstract = true, hidden = true) {
            supertypeStrategySpecial { mType ->
                val rType = L_TypeUtils.getRType(mType)
                rType is R_OperationType
            }
        }
    }
}

// A fake type allowing to represent struct<operation> in a standard way.
class R_OperationType(val rOperation: R_OperationDefinition): R_Type(rOperation.appLevelName, rOperation.cDefName) {
    init {
        checkEquals(rOperation.type, null) // during initialization
    }

    override fun equals0(other: R_Type): Boolean = other is R_OperationType && other.rOperation == rOperation
    override fun hashCode0(): Int = rOperation.hashCode()

    override fun isDirectVirtualable() = false
    override fun createGtvConversion() = GtvRtConversion_None
    override fun strCode(): String = name
    override fun toMetaGtv() = rOperation.appLevelName.toGtv()
    override fun getLibType0() = C_LibType.make(this)
}
