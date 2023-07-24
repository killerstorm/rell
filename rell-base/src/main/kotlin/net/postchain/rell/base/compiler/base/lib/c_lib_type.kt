/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.ast.S_PosValue
import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.lmodel.L_Module
import net.postchain.rell.base.lmodel.L_Type
import net.postchain.rell.base.lmodel.L_TypeDef
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.lmodel.dsl.Ld_ModuleDsl
import net.postchain.rell.base.model.R_CtErrorType
import net.postchain.rell.base.model.R_EntityType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmMap

class C_LibModule(
    val lModule: L_Module,
    typeDefs: List<C_LibTypeDef>,
    val namespace: C_LibNamespace,
    val extensionTypes: List<C_LibExtensionType>,
) {
    private val typeDefsByName = typeDefs.associateBy { it.typeName }.toImmMap()

    fun getTypeDef(name: String): C_LibTypeDef {
        return typeDefsByName.getValue(name)
    }

    companion object {
        fun make(name: String, vararg imports: C_LibModule, block: Ld_ModuleDsl.() -> Unit): C_LibModule {
            val lModule = Ld_ModuleDsl.make(name) {
                for (imp in imports) {
                    this.imports(imp.lModule)
                }
                block(this)
            }

            return C_LibAdapter.makeModule(lModule)
        }
    }
}

class C_LibExtensionType(
    val typeDef: C_LibTypeDef,
    val target: M_TypeSet,
    private val staticMembers: C_LibTypeMembers<C_TypeStaticMember>,
    private val valueMembers: C_LibTypeMembers<C_TypeValueMember>,
) {
    fun getExtStaticMembers(mType: M_Type): C_LibTypeMembers<C_TypeStaticMember>? {
        return getExtMembers0(mType, staticMembers, C_TypeStaticMember::replaceTypeParams)
    }

    fun getExtValueMembers(mType: M_Type): C_LibTypeMembers<C_TypeValueMember>? {
        return getExtMembers0(mType, valueMembers, C_TypeValueMember::replaceTypeParams)
    }

    private fun <MemberT: C_TypeMember> getExtMembers0(
        mType: M_Type,
        members: C_LibTypeMembers<MemberT>,
        replacer: (MemberT, C_TypeMemberReplacement) -> MemberT,
    ): C_LibTypeMembers<MemberT>? {
        return if (!target.containsType(mType)) null else {
            val extType = typeDef.lTypeDef.getType(mType)
            val typeArgs = M_TypeUtils.getTypeArgs(extType.mType)
            val rep = C_TypeMemberReplacement(mType, typeArgs)
            C_LibTypeMembers.replace(members, rep, replacer)
        }
    }
}

sealed class C_LibType(val mType: M_Type) {
    abstract fun hasConstructor(): Boolean
    abstract fun getConstructor(): C_GlobalFunction?
    abstract fun getStaticMembers(): C_LibTypeMembers<C_TypeStaticMember>
    abstract fun getValueMembers(): C_LibTypeMembers<C_TypeValueMember>

    companion object {
        fun make(rType: R_Type): C_LibType = make(L_TypeUtils.makeMType(rType))
        fun make(mType: M_Type): C_LibType = C_LibType_MType(mType)

        fun make(typeDef: C_LibTypeDef, vararg args: R_Type): C_LibType {
            val mArgs = args.map { it.mType }
            val lType = L_Type.make(typeDef.lTypeDef, mArgs)
            return C_LibType_TypeDef(typeDef, lType)
        }
    }
}

private class C_LibType_MType(mType: M_Type): C_LibType(mType) {
    override fun hasConstructor() = false
    override fun getConstructor() = null
    override fun getStaticMembers() = C_LibTypeMembers.EMPTY_STATIC
    override fun getValueMembers() = C_LibTypeMembers.EMPTY_VALUE
}

private class C_LibType_TypeDef(
    private val typeDef: C_LibTypeDef,
    private val lType: L_Type,
): C_LibType(lType.mType) {
    private val constructorLazy: C_GlobalFunction? by lazy {
        C_LibAdapter.makeConstructor(lType)
    }

    override fun hasConstructor() = constructorLazy != null
    override fun getConstructor() = constructorLazy
    override fun getStaticMembers() = typeDef.staticMembers
    override fun getValueMembers() = typeDef.valueMembers
}

sealed class C_TypeDef {
    abstract fun hasConstructor(): Boolean
    open fun isEntity(): Boolean = false
    abstract fun compileExpr(msgCtx: C_MessageContext, pos: S_Pos): C_Expr
    abstract fun compileType(ctx: C_AppContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type

    companion object {
        fun makeRType(rType: R_Type): C_TypeDef {
            return C_RTypeDef(rType)
        }
    }
}

private class C_RTypeDef(
    private val rType: R_Type,
): C_TypeDef() {
    override fun hasConstructor() = rType.hasConstructor()
    override fun isEntity() = rType is R_EntityType
    override fun compileExpr(msgCtx: C_MessageContext, pos: S_Pos): C_Expr = C_SpecificTypeExpr(pos, rType)

    override fun compileType(ctx: C_AppContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
        return if (args.isEmpty()) rType else {
            ctx.msgCtx.error(pos, "type:not_generic:${rType.strCode()}", "Type '${rType.str()}' is not generic")
            return R_CtErrorType
        }
    }
}

class C_LibTypeDef(
    val typeName: String,
    val lTypeDef: L_TypeDef,
    val rawConstructor: C_GlobalFunction?,
    val staticMembers: C_LibTypeMembers<C_TypeStaticMember>,
    val valueMembers: C_LibTypeMembers<C_TypeValueMember>,
): C_TypeDef() {
    val mGenericType: M_GenericType = lTypeDef.mGenericType

    private val mType0: M_Type? = if (mGenericType.params.isEmpty()) mGenericType.getType() else null

    val mType: M_Type get() {
        return checkNotNull(mType0) { "Not a simple type: ${mGenericType.strCode()}" }
    }

    override fun hasConstructor() = rawConstructor != null

    override fun compileExpr(msgCtx: C_MessageContext, pos: S_Pos): C_Expr {
        return if (mGenericType.params.isEmpty()) {
            val rType = getRType(msgCtx, pos, immListOf())
            C_SpecificTypeExpr(pos, rType)
        } else {
            C_RawGenericTypeExpr(pos, this)
        }
    }

    override fun compileType(ctx: C_AppContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
        if (!checkArgCount(ctx.msgCtx, pos, args.size)) {
            return R_CtErrorType
        }

        val checkedArgs = checkTypeArgs(ctx, args)
        return getRType(ctx.msgCtx, pos, checkedArgs)
    }

    private fun checkArgCount(msgCtx: C_MessageContext, pos: S_Pos, argCount: Int): Boolean {
        val paramCount = mGenericType.params.size
        if (paramCount == 0) {
            if (argCount != 0) {
                msgCtx.error(pos, "type:not_generic:$typeName", "Type '$typeName' is not generic")
                return false
            }
        } else if (argCount == 0) {
            msgCtx.error(pos, "type:generic:no_args:$typeName",
                "Type arguments not specified for generic type '$typeName'")
            return false
        } else if (argCount != paramCount) {
            msgCtx.error(pos, "type:generic:wrong_arg_count:$typeName:$paramCount:$argCount",
                "Wrong number of type arguments for type '$typeName': $argCount instead of $paramCount")
            return false
        }
        return true
    }

    private fun checkTypeArgs(ctx: C_AppContext, args: List<S_PosValue<R_Type>>): List<R_Type> {
        val checkedArgs = args.map { arg ->
            C_Types.checkNotUnit(ctx.msgCtx, arg.pos, arg.value, null) {
                val typeName = lTypeDef.fullName.qName
                "$typeName:component" toCodeMsg "$typeName component"
            }
        }

        val toValidate = checkedArgs.withIndex().filter { (i, rType) ->
            mGenericType.params[i].bounds != M_TypeSets.ALL && rType.isNotError()
        }

        if (toValidate.isNotEmpty()) {
            ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                validateTypeArgs(ctx.msgCtx, args, toValidate)
            }
        }

        return checkedArgs
    }

    private fun getRType(msgCtx: C_MessageContext, pos: S_Pos, args: List<R_Type>): R_Type {
        val rType = lTypeDef.rTypeFactory?.getRType(args)
        return if (rType != null) rType else {
            msgCtx.error(pos, "generic_type:no_rtype_factory:${mGenericType.name}",
                "Cannot use type ${mGenericType.name}")
            R_CtErrorType
        }
    }

    private fun validateTypeArgs(
        msgCtx: C_MessageContext,
        args: List<S_PosValue<R_Type>>,
        toValidate: List<IndexedValue<R_Type>>,
    ) {
        val genType = lTypeDef.mGenericType

        for ((i, rType) in toValidate) {
            val param = genType.params[i]
            val mTypeArg = rType.mType.oneTypeSet
            val codeMsg = M_GenericType.checkParamBounds(genType, param, mTypeArg)
            if (codeMsg != null) {
                val argPos = args[i].pos
                msgCtx.error(argPos, codeMsg.first, codeMsg.second)
            }
        }
    }
}

private class C_RawGenericTypeExpr(
    private val pos: S_Pos,
    private val typeDef: C_LibTypeDef,
): C_NoValueExpr() {
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        val nameCode = "[${typeDef.lTypeDef.qualifiedName}]:$memberName"
        val nameMsg = "${typeDef.typeName}.$memberName"
        C_Errors.errUnknownName(ctx.msgCtx, pos, nameCode, nameMsg)
        return C_ExprUtils.errorMember(ctx, memberName.pos)
    }

    override fun isCallable(): Boolean {
        return typeDef.rawConstructor != null
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val fn = typeDef.rawConstructor
        // Handle no-constructor case: super throws error; TODO better handling
        fn ?: return super.call(ctx, pos, args, resTypeHint)

        val lazyName = LazyPosString.of(this.pos) { typeDef.typeName }
        val vExpr = fn.compileCall(ctx, lazyName, args, resTypeHint).vExpr()
        return C_ValueExpr(vExpr)
    }

    override fun errKindName() = "type" to typeDef.lTypeDef.qualifiedName.str()
}
