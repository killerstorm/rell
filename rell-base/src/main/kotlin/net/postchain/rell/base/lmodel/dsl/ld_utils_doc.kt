/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.utils.C_DocUtils
import net.postchain.rell.base.lmodel.L_FullName
import net.postchain.rell.base.lmodel.L_Function
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.doc.*
import net.postchain.rell.base.utils.toImmList

object Ld_DocSymbols {
    fun function(
        moduleName: R_ModuleName,
        qName: R_QualifiedName,
        lFunction: L_Function,
        isStatic: Boolean,
        deprecated: C_Deprecated?,
    ): DocSymbol {
        val docModifiers = DocModifiers(listOfNotNull(
            if (deprecated != null) DocModifier.deprecated(deprecated.error) else null,
            if (lFunction.pure) DocModifier.PURE else null,
            if (isStatic) DocModifier.STATIC else null,
        ))

        val docHeader = L_TypeUtils.docFunctionHeader(lFunction.header.mHeader)
        val docParams = lFunction.header.params.map { it.docSymbol.declaration }.toImmList()
        val dec = DocDeclaration_Function(docModifiers, qName.last, docHeader, docParams)

        return DocSymbol(
            kind = DocSymbolKind.FUNCTION,
            symbolName = DocSymbolName.global(moduleName.str(), qName.str()),
            mountName = null,
            declaration = dec,
            comment = null,
        )
    }

    fun specialFunction(fullName: L_FullName, isStatic: Boolean): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.FUNCTION,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
            mountName = null,
            declaration = DocDeclaration_SpecialFunction(fullName.last, isStatic = isStatic),
            comment = null,
        )
    }

    fun constant(fullName: L_FullName, mType: M_Type, rValue: Rt_Value): DocSymbol {
        val docType = L_TypeUtils.docType(mType)
        val docValue = C_DocUtils.docValue(rValue)
        val dec = DocDeclaration_Constant(DocModifiers.NONE, fullName.last, docType, docValue)

        return DocSymbol(
            kind = DocSymbolKind.CONSTANT,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
            mountName = null,
            declaration = dec,
            comment = null,
        )
    }

    fun property(fullName: L_FullName, mType: M_Type, pure: Boolean): DocSymbol {
        val docType = L_TypeUtils.docType(mType)
        return DocSymbol(
            kind = DocSymbolKind.PROPERTY,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
            mountName = null,
            declaration = DocDeclaration_Property(fullName.last, docType, pure),
            comment = null,
        )
    }
}
