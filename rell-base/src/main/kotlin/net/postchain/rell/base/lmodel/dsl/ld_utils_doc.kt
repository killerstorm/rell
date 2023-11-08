/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.lmodel.L_FullName
import net.postchain.rell.base.lmodel.L_Function
import net.postchain.rell.base.lmodel.L_FunctionParam
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

        val docParams = lFunction.header.params.map { lazyOf(it.docSymbol.declaration) }.toImmList()
        val dec = DocDeclaration_Function(docModifiers, qName.last, lFunction.header.mHeader, docParams)

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

    fun docCodeParams(b: DocCode.Builder, params: List<L_FunctionParam>) {
        b.raw("(")

        if (params.isNotEmpty()) {
            for ((i, param) in params.withIndex()) {
                if (i > 0) b.sep(",")
                b.newline().tab()
                b.append(param.docSymbol.declaration.code)
            }
            b.newline()
        }

        b.raw(")")
    }

    fun constant(fullName: L_FullName, mType: M_Type, rValue: Rt_Value): DocSymbol {
        val dec = DocDeclaration_Constant(DocModifiers.NONE, fullName.last, mType, rValue)
        return DocSymbol(
            kind = DocSymbolKind.CONSTANT,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
            mountName = null,
            declaration = dec,
            comment = null,
        )
    }

    fun property(fullName: L_FullName, mType: M_Type, pure: Boolean): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.PROPERTY,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
            mountName = null,
            declaration = DocDeclaration_Property(fullName.last, mType, pure),
            comment = null,
        )
    }
}
