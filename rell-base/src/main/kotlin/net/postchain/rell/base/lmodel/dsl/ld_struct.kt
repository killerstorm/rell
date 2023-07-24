/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.def.C_SysAttribute
import net.postchain.rell.base.compiler.base.utils.C_Utils
import net.postchain.rell.base.lmodel.L_Struct
import net.postchain.rell.base.lmodel.L_StructAttribute
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_SimpleType
import net.postchain.rell.base.model.R_Struct
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

@RellLibDsl
interface Ld_StructDsl {
    fun attribute(name: String, type: String)
}

class Ld_StructDslBuilder: Ld_StructDsl {
    private val attributes = mutableMapOf<R_Name, Ld_StructAttribute>()

    override fun attribute(name: String, type: String) {
        val rName = R_Name.of(name)
        check(rName !in attributes) { "Name conflict: $rName" }
        val ldType = Ld_Type.parse(type)
        attributes[rName] = Ld_StructAttribute(rName, ldType)
    }

    fun build(): Ld_Struct {
        return Ld_Struct(attributes.values.toImmList())
    }
}

class Ld_StructAttribute(val name: R_Name, val type: Ld_Type) {
    fun finish(ctx: Ld_TypeFinishContext): L_StructAttribute {
        val mType = type.finish(ctx)
        return L_StructAttribute(name, mType)
    }
}

class Ld_Struct(private val attributes: List<Ld_StructAttribute>) {
    fun declare(ctx: Ld_DeclareContext, qualifiedName: R_QualifiedName): Declaration {
        val rStruct = C_Utils.createSysStruct(qualifiedName.str())
        ctx.declareType(qualifiedName, rStruct.type.mType)
        return Declaration(qualifiedName, rStruct, attributes)
    }

    class Declaration(
        private val qualifiedName: R_QualifiedName,
        private val rStruct: R_Struct,
        private val attributes: List<Ld_StructAttribute>,
    ) {
        private var finished = false

        fun finish(ctx: Ld_NamespaceFinishContext): L_Struct {
            check(!finished)
            finished = true

            val lAttributes = attributes.map { it.finish(ctx.typeCtx) }.toImmList()
            val rAttributes = lAttributes
                .mapIndexed { i, lAttr ->
                    val name = lAttr.name
                    val mType = lAttr.type
                    val rType = L_TypeUtils.getRType(mType)
                    checkNotNull(rType) { "Cannot convert type of struct attribute $qualifiedName.$name to R_Type: ${mType.strCode()}" }
                    val cAttr = C_SysAttribute(name.str, rType)
                    name to cAttr.compile(i, false)
                }
                .toImmMap()
            rStruct.setAttributes(rAttributes)

            return L_Struct(qualifiedName.last, rStruct)
        }
    }
}
