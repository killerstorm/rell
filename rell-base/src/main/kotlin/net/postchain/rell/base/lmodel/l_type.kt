/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.toImmList

sealed class L_Type(val typeDef: L_TypeDef) {
    abstract val mType: M_Type

    fun strCode(): String = mType.strCode()

    final override fun toString() = strCode()

    companion object {
        fun make(typeDef: L_TypeDef, vararg args: M_Type): L_Type {
            return make(typeDef, args.toImmList())
        }

        fun make(typeDef: L_TypeDef, args: List<M_Type>): L_Type {
            val args2 = args.toImmList()
            return if (args2.isEmpty()) L_Type_Basic(typeDef) else L_Type_Generic(typeDef, args2)
        }
    }
}

class L_Type_Basic(typeDef: L_TypeDef): L_Type(typeDef) {
    init {
        checkEquals(typeDef.mGenericType.params.size, 0) {
            "Wrong number of arguments for generic type '${typeDef.qualifiedName}'"
        }
    }

    override val mType: M_Type by lazy {
        typeDef.mGenericType.getType()
    }
}

class L_Type_Generic(typeDef: L_TypeDef, val args: List<M_Type>): L_Type(typeDef) {
    init {
        checkEquals(args.size, typeDef.mGenericType.params.size)
        check(args.isNotEmpty())
    }

    override val mType: M_Type by lazy {
        typeDef.mGenericType.getTypeSimple(args)
    }
}
