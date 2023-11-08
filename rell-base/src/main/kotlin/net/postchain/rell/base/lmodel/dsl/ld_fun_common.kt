/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.lmodel.L_FunctionParam
import net.postchain.rell.base.lmodel.L_ParamArity
import net.postchain.rell.base.lmodel.L_ParamImplication
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.mtype.M_FunctionParam
import net.postchain.rell.base.mtype.M_ParamArity
import net.postchain.rell.base.mtype.M_Type_Nullable
import net.postchain.rell.base.mtype.M_Types
import net.postchain.rell.base.utils.doc.DocDeclaration_Parameter
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.doc.DocSymbolKind
import net.postchain.rell.base.utils.doc.DocSymbolName
import net.postchain.rell.base.utils.toImmList

@RellLibDsl
interface Ld_FunctionContextDsl {
    val fnSimpleName: String
}

@RellLibDsl
abstract class Ld_CommonFunctionDsl(
    private val commonMaker: Ld_CommonFunctionMaker,
    private val bodyDsl: Ld_FunctionBodyDsl,
): Ld_FunctionContextDsl, Ld_FunctionBodyDsl by bodyDsl {
    override val fnSimpleName: String get() = bodyDsl.fnSimpleName

    fun deprecated(newName: String, error: Boolean = true) {
        commonMaker.deprecated(C_Deprecated(useInstead = newName, error = error))
    }

    fun generic(name: String, subOf: String? = null, superOf: String? = null) {
        commonMaker.generic(name = name, subOf = subOf, superOf = superOf)
    }

    fun param(
        type: String,
        name: String? = null,
        arity: L_ParamArity = L_ParamArity.ONE,
        exact: Boolean = false,
        nullable: Boolean = false,
        lazy: Boolean = false,
        implies: L_ParamImplication? = null,
    ) {
        commonMaker.param(
            type = type,
            name = name,
            arity = arity,
            exact = exact,
            nullable = nullable,
            lazy = lazy,
            implies = implies,
        )
    }
}

interface Ld_CommonFunctionMaker {
    fun deprecated(deprecated: C_Deprecated)
    fun generic(name: String, subOf: String? = null, superOf: String? = null)

    fun param(
        type: String,
        name: String?,
        arity: L_ParamArity = L_ParamArity.ONE,
        exact: Boolean = false,
        nullable: Boolean = false,
        lazy: Boolean = false,
        implies: L_ParamImplication? = null,
    )
}

abstract class Ld_CommonFunctionBuilder(
    private val outerTypeParams: Set<R_Name>,
    private val bodyBuilder: Ld_FunctionBodyBuilder,
): Ld_CommonFunctionMaker {
    private var deprecated: C_Deprecated? = null
    private val typeParams = mutableMapOf<R_Name, Ld_TypeParam>()
    private val params = mutableListOf<Ld_FunctionParam>()
    private var paramsDefined = false

    final override fun deprecated(deprecated: C_Deprecated) {
        require(this.deprecated == null)
        this.deprecated = deprecated
    }

    final override fun generic(name: String, subOf: String?, superOf: String?) {
        require(subOf == null || superOf == null)
        require(params.isEmpty()) { "Trying to add a type parameter after a function parameter" }
        require(bodyBuilder.isEmpty()) { "Body already set" }

        val typeParam = Ld_TypeParam.make(name, subOf = subOf, superOf = superOf)

        Ld_Exception.check(typeParam.name !in typeParams) {
            "fun:type_param_conflict:$name" to "Name conflict: $name"
        }
        Ld_Exception.check(typeParam.name !in outerTypeParams) {
            "fun:type_param_conflict_outer:$name" to "Name conflict (outer type parameter): $name"
        }

        typeParams[typeParam.name] = typeParam
    }

    final override fun param(
        type: String,
        name: String?,
        arity: L_ParamArity,
        exact: Boolean,
        nullable: Boolean,
        lazy: Boolean,
        implies: L_ParamImplication?
    ) {
        require(bodyBuilder.isEmpty()) { "Body already set" }

        Ld_Exception.check(!paramsDefined) {
            "common_fun:params_already_defined:$type" to "Parameters already defined"
        }

        val param = Ld_FunctionParam(
            index = params.size,
            name = if (name == null) null else R_Name.of(name),
            type = Ld_Type.parse(type),
            arity = arity.mArity,
            exact = exact,
            nullable = nullable,
            lazy = lazy,
            implies = implies,
        )

        params.add(param)
    }

    fun paramsDefined() {
        require(!paramsDefined)
        paramsDefined = true
    }

    protected fun commonBuild(bodyRes: Ld_FunctionBodyRef): Ld_CommonFunction {
        val header = Ld_CommonFunctionHeader(
            typeParams = typeParams.values.toImmList(),
            params = params.toImmList(),
        )

        val body = bodyBuilder.build(bodyRes)

        return Ld_CommonFunction(
            header = header,
            deprecated = deprecated,
            body = body,
        )
    }
}

class Ld_CommonFunctionHeader(
    val typeParams: List<Ld_TypeParam>,
    val params: List<Ld_FunctionParam>,
)

class Ld_CommonFunction(
    val header: Ld_CommonFunctionHeader,
    val deprecated: C_Deprecated?,
    val body: Ld_FunctionBody,
)

class Ld_FunctionParam(
    val index: Int,
    val name: R_Name?,
    val type: Ld_Type,
    val arity: M_ParamArity,
    val exact: Boolean,
    val nullable: Boolean,
    val lazy: Boolean,
    val implies: L_ParamImplication?,
) {
    fun finish(ctx: Ld_TypeFinishContext): L_FunctionParam {
        val mType = type.finish(ctx)

        if (nullable) {
            Ld_Exception.check(mType is M_Type_Nullable || mType == M_Types.ANYTHING) {
                "function:param_not_nullable:${mType.strCode()}" to
                        "Parameter marked as nullable, but type is not nullable: ${mType.strMsg()}"
            }
        }

        val mParam = M_FunctionParam(
            name = name?.str,
            type = mType,
            arity = arity,
            exact = exact,
            nullable = nullable,
        )

        val docName = if (name != null) name.str else "#$index"

        val doc = DocSymbol(
            kind = DocSymbolKind.PARAMETER,
            symbolName = DocSymbolName.local(docName),
            mountName = null,
            declaration = DocDeclaration_Parameter(mParam, lazy, implies, null),
            comment = null,
        )

        return L_FunctionParam(
            name = name,
            mParam = mParam,
            lazy = lazy,
            implies = implies,
            docSymbol = doc,
        )
    }
}
