/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.utils.C_DocUtils
import net.postchain.rell.base.lmodel.L_FunctionFlags
import net.postchain.rell.base.lmodel.L_FunctionHeader
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.doc.*
import net.postchain.rell.base.utils.toImmList

object Ld_DocSymbols {
    fun function(
        fullName: R_FullName,
        header: L_FunctionHeader,
        flags: L_FunctionFlags,
        deprecated: C_Deprecated?,
    ): DocSymbol {
        val docModifiers = DocModifiers(listOfNotNull(
            C_DocUtils.docModifier(deprecated),
            if (flags.isPure) DocModifier.PURE else null,
            if (flags.isStatic) DocModifier.STATIC else null,
        ))

        val docHeader = L_TypeUtils.docFunctionHeader(header.mHeader)
        val docParams = header.params.map { it.docSymbol.declaration }.toImmList()
        val dec = DocDeclaration_Function(docModifiers, fullName.last, docHeader, docParams)

        return DocSymbol(
            kind = DocSymbolKind.FUNCTION,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = dec,
            comment = null,
        )
    }

    fun specialFunction(fullName: R_FullName, isStatic: Boolean): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.FUNCTION,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = DocDeclaration_SpecialFunction(fullName.last, isStatic = isStatic),
            comment = null,
        )
    }

    fun constant(fullName: R_FullName, mType: M_Type, rValue: Rt_Value): DocSymbol {
        val docType = L_TypeUtils.docType(mType)
        val docValue = C_DocUtils.docValue(rValue)
        val dec = DocDeclaration_Constant(DocModifiers.NONE, fullName.last, docType, docValue)

        return DocSymbol(
            kind = DocSymbolKind.CONSTANT,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = dec,
            comment = null,
        )
    }

    fun property(fullName: R_FullName, mType: M_Type, pure: Boolean): DocSymbol {
        val docType = L_TypeUtils.docType(mType)
        return DocSymbol(
            kind = DocSymbolKind.PROPERTY,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = DocDeclaration_Property(fullName.last, docType, pure),
            comment = null,
        )
    }
}
