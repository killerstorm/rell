/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.def.C_SysAttribute
import net.postchain.rell.base.compiler.base.utils.C_Utils
import net.postchain.rell.base.lmodel.L_FullName
import net.postchain.rell.base.lmodel.L_Struct
import net.postchain.rell.base.lmodel.L_StructAttribute
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.model.R_Attribute
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Struct
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.utils.doc.DocDeclaration_StructAttribute
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.doc.DocSymbolKind
import net.postchain.rell.base.utils.doc.DocSymbolName
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

@RellLibDsl
interface Ld_StructDsl {
    fun attribute(name: String, type: String, mutable: Boolean = false)
}

class Ld_StructDslBuilder: Ld_StructDsl {
    private val attributes = mutableMapOf<R_Name, Ld_StructAttribute>()

    override fun attribute(name: String, type: String, mutable: Boolean) {
        val rName = R_Name.of(name)
        check(rName !in attributes) { "Name conflict: $rName" }
        val ldType = Ld_Type.parse(type)
        attributes[rName] = Ld_StructAttribute(rName, ldType, mutable = mutable)
    }

    fun build(): Ld_Struct {
        return Ld_Struct(attributes.values.toImmList())
    }
}

class Ld_StructAttribute(val name: R_Name, val type: Ld_Type, val mutable: Boolean) {
    fun finish(ctx: Ld_TypeFinishContext, outerFullName: L_FullName): L_StructAttribute {
        val mType = type.finish(ctx)
        val doc = finishDoc(outerFullName, mType)
        return L_StructAttribute(name, mType, mutable = mutable, docSymbol = doc)
    }

    private fun finishDoc(outerFullName: L_FullName, mType: M_Type): DocSymbol {
        val fullName = outerFullName.append(name)
        return DocSymbol(
            kind = DocSymbolKind.STRUCT_ATTR,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
            mountName = null,
            declaration = DocDeclaration_StructAttribute(fullName.last, mType, mutable),
            comment = null,
        )
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

        fun finish(ctx: Ld_NamespaceFinishContext, fullName: L_FullName): L_Struct {
            check(!finished)
            finished = true

            val lAttributes = attributes.map { it.finish(ctx.typeCtx, fullName) }.toImmList()
            val lAttributesMap = lAttributes.associateBy { it.name.str }.toImmMap()

            val rAttributes = lAttributes
                .mapIndexed { i, lAttr -> lAttr.name to finishAttr(lAttr, i) }
                .toImmMap()
            rStruct.setAttributes(rAttributes)

            return L_Struct(qualifiedName.last, rStruct, lAttributesMap)
        }

        private fun finishAttr(lAttr: L_StructAttribute, i: Int): R_Attribute {
            val name = lAttr.name
            val mType = lAttr.type

            val rType = L_TypeUtils.getRType(mType)
            checkNotNull(rType) {
                "Cannot convert type of struct attribute $qualifiedName.$name to R_Type: ${mType.strCode()}"
            }

            val cAttr = C_SysAttribute(name.str, rType, mutable = lAttr.mutable, docSymbol = lAttr.docSymbol)
            return cAttr.compile(i, false)
        }
    }
}
