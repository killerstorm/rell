/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_LibMemberFunction
import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.*
import java.util.function.Supplier
import kotlin.math.max

class Ld_TypeDefParent(private val typeName: Ld_FullName, private val args: List<Ld_Type>) {
    fun finish(ctx: Ld_TypeFinishContext): L_TypeDefParent {
        val typeDef = ctx.getTypeDef(typeName)
        Ld_Exception.check(typeDef.abstract) {
            "type_parent_not_abstract:$typeName" to "Parent type is not abstract: $typeName"
        }
        val mArgs = args.map { it.finish(ctx) }.toImmList()
        return L_TypeDefParent(typeDef, mArgs)
    }
}

sealed class Ld_TypeDefMember {
    abstract fun finish(ctx: Ld_TypeFinishContext, typeName: R_QualifiedName): List<L_TypeDefMember>
}

private class Ld_TypeDefMember_Constructor(private val constructor: Ld_Constructor): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: R_QualifiedName): List<L_TypeDefMember> {
        val lConstructor = constructor.finish(ctx, typeName)
        return immListOf(L_TypeDefMember_Constructor(lConstructor))
    }
}

private class Ld_TypeDefMember_Constant(private val constant: Ld_Constant): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: R_QualifiedName): List<L_TypeDefMember> {
        val lConstant = constant.finish(ctx)
        return immListOf(L_TypeDefMember_Constant(lConstant))
    }
}

private class Ld_TypeDefMember_Property(private val property: Ld_TypeProperty): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: R_QualifiedName): List<L_TypeDefMember> {
        val lProperty = property.finish(ctx)
        return immListOf(L_TypeDefMember_Property(property.simpleName, lProperty))
    }
}

private class Ld_TypeDefMember_Function(
    private val simpleName: R_Name,
    private val function: Ld_Function,
    private val isStatic: Boolean,
): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: R_QualifiedName): List<L_TypeDefMember> {
        val qualifiedName = typeName.append(simpleName)
        val lFunction = function.finish(ctx, qualifiedName)

        val res = mutableListOf<L_TypeDefMember>()
        res.add(L_TypeDefMember_Function(simpleName, lFunction, isStatic = isStatic, deprecated = function.deprecated))

        for (alias in function.aliases) {
            res.add(L_TypeDefMember_Function(alias.simpleName, lFunction, isStatic = isStatic, deprecated = alias.deprecated))
        }

        return res.toImmList()
    }
}

private class Ld_TypeDefMember_SpecialFunction(
    private val simpleName: R_Name,
    private val fn: C_LibMemberFunction,
): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: R_QualifiedName): List<L_TypeDefMember> {
        return immListOf(L_TypeDefMember_SpecialFunction(simpleName, fn))
    }
}

class Ld_TypeDef(
    val simpleName: R_Name,
    val aliases: List<Ld_Alias>,
    private val flags: L_TypeDefFlags,
    private val typeParams: List<Ld_TypeParam>,
    private val parent: Ld_TypeDefParent?,
    private val rTypeFactory: L_TypeDefRTypeFactory?,
    private val strCodeStrategy: L_TypeDefStrCodeStrategy?,
    private val supertypeStrategy: L_TypeDefSupertypeStrategy,
    private val members: List<Ld_TypeDefMember>,
) {
    fun declare(ctx: Ld_DeclareContext, qualifiedName: R_QualifiedName): Declaration {
        val dec = Declaration(qualifiedName, aliases)
        ctx.declareType(qualifiedName, dec)
        return dec
    }

    inner class Declaration(
        private val qualifiedName: R_QualifiedName,
        val aliases: List<Ld_Alias>,
    ) {
        private var typeDefMaking = false
        private var typeDefMade: Ld_TypeDefRec? = null
        private var finished = false

        fun getTypeDef(ctx: Ld_NamespaceFinishContext): L_TypeDef {
            val rec = getTypeDef0(ctx)
            return rec.typeDef
        }

        private fun getTypeDef0(ctx: Ld_NamespaceFinishContext): Ld_TypeDefRec {
            val typeDef0 = typeDefMade
            if (typeDef0 != null) {
                return typeDef0
            }

            Ld_Exception.check(!typeDefMaking) {
                val stack = ctx.getTypeStack()
                val i = stack.indexOf(qualifiedName)
                val cycle = (stack.drop(max(i, 0)) + listOf(qualifiedName)).map { it.str() }
                val cycleCode = cycle.joinToString(",")
                val cycleMsg = cycle.joinToString(", ")
                "type_cycle:$cycleCode" to "Type recursion: $cycleMsg"
            }

            typeDefMaking = true

            val subCtx = ctx.pushType(qualifiedName)
            val typeCtx = subCtx.typeCtx
            val (mTypeParams, mTypeParamMap) = Ld_TypeParam.finishList(typeCtx, typeParams)

            val subTypeCtx = typeCtx.subCtx(typeParams = mTypeParamMap)

            val lParent = parent?.finish(subTypeCtx)

            val mParent = if (lParent == null) null else M_GenericTypeParent(lParent.typeDef.mGenericType, lParent.args)
            val mGenericType = makeGenericType(qualifiedName, mTypeParams, mParent)

            val fullName = L_FullName(ctx.moduleName, qualifiedName)
            val bodyRef = Ld_TypeDefMembersRef()

            val typeDef = L_TypeDef(
                fullName,
                flags = flags,
                mGenericType = mGenericType,
                parent = lParent,
                rTypeFactory = rTypeFactory,
                membersSupplier = bodyRef,
            )

            val res = Ld_TypeDefRec(typeDef, bodyRef, mTypeParamMap)
            typeDefMade = res
            return res
        }

        private fun makeGenericType(
            qualifiedName: R_QualifiedName,
            mTypeParams: List<M_TypeParam>,
            mParent: M_GenericTypeParent?,
        ): M_GenericType {
            if (mTypeParams.isEmpty() && rTypeFactory != null) {
                val rType = rTypeFactory.getRType(immListOf())
                if (rType != null) {
                    return L_TypeUtils.makeMGenericType(rType, mParent)
                }
            }

            return L_TypeUtils.makeMGenericType(
                qualifiedName.str(),
                mTypeParams,
                mParent,
                rTypeFactory = rTypeFactory,
                strCodeStrategy = strCodeStrategy,
                supertypeStrategy = supertypeStrategy,
            )
        }

        fun finish(ctx: Ld_NamespaceFinishContext): L_TypeDef {
            check(!finished)
            finished = true

            val typeDefRec = getTypeDef0(ctx)

            val typeCtx = ctx.typeCtx.subCtx(typeParams = typeDefRec.typeParams)
            val lMembers = members.flatMap { it.finish(typeCtx, qualifiedName) }.toImmList()

            val body = L_TypeDefMembers(lMembers)
            typeDefRec.membersRef.set(body)

            return typeDefRec.typeDef
        }
    }

    private class Ld_TypeDefRec(
        val typeDef: L_TypeDef,
        val membersRef: Ld_TypeDefMembersRef,
        val typeParams: Map<R_Name, M_Type>,
    )

    private class Ld_TypeDefMembersRef: Supplier<L_TypeDefMembers> {
        private var body: L_TypeDefMembers? = null

        override fun get(): L_TypeDefMembers {
            return checkNotNull(body) { "Body not defined yet" }
        }

        fun set(body: L_TypeDefMembers) {
            check(this.body == null)
            this.body = body
        }
    }

    companion object {
        fun make(
            simpleName: R_Name,
            flags: L_TypeDefFlags,
            rType: R_Type?,
            block: Ld_TypeDefDsl.() -> Unit,
        ): Ld_TypeDef {
            val builder = Ld_TypeDefBuilder(simpleName, flags = flags)

            val dsl = Ld_TypeDefDsl(builder)
            if (rType != null) {
                dsl.rType(rType)
            }

            block(dsl)
            return builder.build()
        }
    }
}

class Ld_NamespaceMember_Type(
    simpleName: R_Name,
    private val typeDef: Ld_TypeDef,
): Ld_NamespaceMember(simpleName) {
    override fun getAliases(): List<Ld_Alias> = typeDef.aliases

    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val qualifiedName = ctx.getQualifiedName(simpleName)
        val typeDeclaration = typeDef.declare(ctx, qualifiedName)
        return MemDeclaration(qualifiedName, typeDeclaration)
    }

    private class MemDeclaration(
        qualifiedName: R_QualifiedName,
        private val typeDeclaration: Ld_TypeDef.Declaration,
    ): Declaration(qualifiedName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val lTypeDef = typeDeclaration.finish(ctx)

            val res = mutableListOf<L_NamespaceMember>()
            res.add(L_NamespaceMember_Type(qualifiedName, lTypeDef, null))

            for (alias in typeDeclaration.aliases) {
                val qName = qualifiedName.replaceLast(alias.simpleName)
                res.add(L_NamespaceMember_Type(qName, lTypeDef, alias.deprecated))
            }

            return res.toImmList()
        }
    }
}

@RellLibDsl
interface Ld_TypeDefMaker: Ld_CommonNamespaceMaker {
    fun alias(name: String, deprecated: C_MessageType?)
    fun generic(name: String, subOf: String?, superOf: String?)

    fun parent(type: String)

    fun rTypeFactory(factory: L_TypeDefRTypeFactory)
    fun strCodeStrategy(strategy: L_TypeDefStrCodeStrategy)
    fun supertypeStrategy(strategy: L_TypeDefSupertypeStrategy)

    fun property(name: String, type: String, fn: C_SysFunction)

    fun constructor(params: List<String>?, pure: Boolean?, block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef)

    fun function(
        isStatic: Boolean,
        name: String,
        result: String?,
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_LibMemberFunction)
}

@RellLibDsl
class Ld_TypeDefDsl(
    private val maker: Ld_TypeDefMaker,
): Ld_CommonNamespaceDsl by Ld_CommonNamespaceDslBuilder(maker) {
    fun alias(name: String, deprecated: C_MessageType? = null) {
        maker.alias(name, deprecated)
    }

    fun generic(name: String, subOf: String? = null, superOf: String? = null) {
        maker.generic(name, subOf = subOf, superOf = superOf)
    }

    fun parent(type: String) {
        maker.parent(type)
    }

    fun rType(rType: R_Type) {
        maker.rTypeFactory { rType }
    }

    fun rType(factory: (R_Type) -> R_Type?) {
        maker.rTypeFactory { args ->
            checkEquals(args.size, 1)
            factory(args[0])
        }
    }

    fun rType(factory: (R_Type, R_Type) -> R_Type?) {
        maker.rTypeFactory { args ->
            checkEquals(args.size, 2)
            factory(args[0], args[1])
        }
    }

    fun rType(factory: (R_Type, R_Type, R_Type) -> R_Type?) {
        maker.rTypeFactory { args ->
            checkEquals(args.size, 3)
            factory(args[0], args[1], args[2])
        }
    }

    fun strCode(str: String) {
        maker.strCodeStrategy { _, _ ->
            str
        }
    }

    fun strCode(calculator: (M_TypeSet) -> String) {
        maker.strCodeStrategy { _, args ->
            checkEquals(args.size, 1)
            calculator(args[0])
        }
    }

    fun strCode(calculator: (M_TypeSet, M_TypeSet) -> String) {
        maker.strCodeStrategy { _, args ->
            checkEquals(args.size, 2)
            calculator(args[0], args[1])
        }
    }

    fun strCode(calculator: (M_TypeSet, M_TypeSet, M_TypeSet) -> String) {
        maker.strCodeStrategy { _, args ->
            checkEquals(args.size, 3)
            calculator(args[0], args[1], args[2])
        }
    }

    fun supertypeStrategySpecial(predicate: (M_Type) -> Boolean) {
        maker.supertypeStrategy(object: L_TypeDefSupertypeStrategy() {
            override fun isSpecialSuperTypeOf(type: M_Type): Boolean {
                val res = predicate(type)
                return res
            }
        })
    }

    fun supertypeStrategyComposite(predicate: (M_Type_Composite) -> Boolean) {
        maker.supertypeStrategy(object: L_TypeDefSupertypeStrategy() {
            override fun isPossibleSpecialCompositeSuperTypeOf(type: M_Type_Composite): Boolean {
                val res = predicate(type)
                return res
            }
        })
    }

    fun property(name: String, type: String, pure: Boolean = false, getter: (Rt_Value) -> Rt_Value) {
        val fn = C_SysFunction.simple(pure = pure, rCode = getter)
        maker.property(name, type, fn)
    }

    fun property(name: String, type: String, fn: C_SysFunction) {
        maker.property(name, type, fn)
    }

    fun constructor(
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef,
    ) {
        maker.constructor(params, pure = pure, block = block)
    }

    fun function(
        name: String,
        result: String? = null,
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    ) {
        maker.function(isStatic = false, name = name, result = result, params = params, pure = pure, block = block)
    }

    fun function(name: String, fn: C_LibMemberFunction) {
        maker.function(name, fn)
    }

    fun staticFunction(
        name: String,
        result: String? = null,
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    ) {
        maker.function(isStatic = true, name = name, result = result, params = params, pure = pure, block = block)
    }
}

class Ld_TypeParamBound(private val type: Ld_Type, private val subOf: Boolean) {
    fun finish(ctx: Ld_TypeFinishContext): M_TypeSet {
        val mType = type.finish(ctx)
        return if (subOf) M_TypeSets.subOf(mType) else M_TypeSets.superOf(mType)
    }
}

private class Ld_TypeDefBuilder(
    private val simpleName: R_Name,
    private val flags: L_TypeDefFlags,
): Ld_TypeDefMaker {
    private val aliasesBuilder = Ld_AliasesBuilder(simpleName)
    private val typeParams = mutableMapOf<R_Name, Ld_TypeParam>()
    private var selfType: String? = null
    private var parentType: Ld_TypeDefParent? = null
    private var rTypeFactory: L_TypeDefRTypeFactory? = null
    private var strCodeStrategy: L_TypeDefStrCodeStrategy? = null
    private var supertypeStrategy: L_TypeDefSupertypeStrategy? = null

    private val staticConflictChecker = Ld_MemberConflictChecker(immMapOf())
    private val valueConflictChecker = Ld_MemberConflictChecker(immMapOf())
    private val members = mutableListOf<Ld_TypeDefMember>()

    override fun alias(name: String, deprecated: C_MessageType?) {
        aliasesBuilder.alias(name, deprecated)
    }

    override fun generic(name: String, subOf: String?, superOf: String?) {
        check(selfType == null) { "Trying to add a type parameter after self type was requested" }
        check(parentType == null) { "Trying to add a type parameter after a parent type" }
        check(rTypeFactory == null) { "Trying to add a type parameter after R_Type" }
        check(members.isEmpty()) { "Trying to add a type parameter after a member" }

        val (name0, variance) = parseTypeParamName(name)
        val typeParam = Ld_TypeParam.make(name0, subOf = subOf, superOf = superOf, variance = variance)

        Ld_Exception.check(typeParam.name !in typeParams) {
            "type:type_param_conflict:$name" to "Name conflict: $name"
        }

        typeParams[typeParam.name] = typeParam
    }

    private fun parseTypeParamName(s: String): Pair<String, M_TypeVariance> {
        return when {
            s.startsWith("-") -> s.substring(1) to M_TypeVariance.OUT
            s.startsWith("+") -> s.substring(1) to M_TypeVariance.IN
            else -> s to M_TypeVariance.NONE
        }
    }

    override fun parent(type: String) {
        check(parentType == null) { "Parent type already set" }
        check(members.isEmpty()) { "Trying to set parent type after a member" }

        val ldType = Ld_Type.parse(type)
        val ldParentType = convertParentType(ldType)
        ldParentType ?: throw Ld_Exception("typedef:bad_parent_type:$type", "Bad parent type: $type")

        parentType = ldParentType
    }

    override fun rTypeFactory(factory: L_TypeDefRTypeFactory) {
        check(rTypeFactory == null) { "R_Type already set" }
        check(members.isEmpty()) { "Trying to set R_Type after a member" }
        rTypeFactory = factory
    }

    override fun strCodeStrategy(strategy: L_TypeDefStrCodeStrategy) {
        check(strCodeStrategy == null) { "strCode strategy already set" }
        check(members.isEmpty()) { "Trying to set strCode strategy after a member" }
        strCodeStrategy = strategy
    }

    override fun supertypeStrategy(strategy: L_TypeDefSupertypeStrategy) {
        check(supertypeStrategy == null) { "Supertype strategy already set" }
        check(members.isEmpty()) { "Trying to set supertype strategy after a member" }
        supertypeStrategy = strategy
    }

    private fun convertParentType(ldType: Ld_Type): Ld_TypeDefParent? {
        return when (ldType) {
            is Ld_Type_Name -> Ld_TypeDefParent(ldType.typeName, immListOf())
            is Ld_Type_Generic -> {
                val ldArgs = ldType.args.mapNotNullAllOrNull { (it as? Ld_TypeSet_One)?.type }
                if (ldArgs == null) null else Ld_TypeDefParent(ldType.typeName, ldArgs)
            }
            else -> null
        }
    }

    override fun constant(name: String, type: String, value: L_ConstantValue) {
        val rName = R_Name.of(name)
        staticConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)

        val ldType = Ld_Type.parse(type)
        val constant = Ld_Constant(rName, ldType, value)
        members.add(Ld_TypeDefMember_Constant(constant))
    }

    override fun property(name: String, type: String, fn: C_SysFunction) {
        val rName = R_Name.of(name)
        valueConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)

        val ldType = Ld_Type.parse(type)
        val property = Ld_TypeProperty(rName, ldType, fn)
        members.add(Ld_TypeDefMember_Property(property))
    }

    override fun constructor(params: List<String>?, pure: Boolean?, block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef) {
        Ld_Exception.check(!flags.abstract) {
            "type:abstract_constructor:$simpleName" to "Abstract type cannot have a constructor: $simpleName"
        }

        val bodyBuilder = Ld_FunctionBodyBuilder(simpleName, pure = pure)
        val conBuilder = Ld_ConstructorBuilder(outerTypeParams = typeParams.keys.toImmSet(), bodyBuilder)
        val bodyDslBuilder = Ld_FunctionBodyDslBuilder(bodyBuilder)
        val dsl = Ld_ConstructorDsl(conBuilder, bodyDslBuilder)

        if (params != null) {
            for (param in params) {
                dsl.param(param)
            }
            conBuilder.paramsDefined()
        }

        val bodyRes = block(dsl)

        val constructor = conBuilder.build(bodyRes)
        members.add(Ld_TypeDefMember_Constructor(constructor))
    }

    override fun function(
        isStatic: Boolean,
        name: String,
        result: String?,
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    ) {
        val rName = R_Name.of(name)

        val fn = Ld_FunctionBuilder.build(
            simpleName = rName,
            result = result,
            params = params,
            pure = pure,
            outerTypeParams = typeParams.keys.toImmSet(),
            block = block,
        )

        val conflictChecker = if (isStatic) staticConflictChecker else valueConflictChecker
        val kind = Ld_ConflictMemberKind.FUNCTION
        conflictChecker.addMember(rName, kind)
        for (alias in fn.aliases) {
            conflictChecker.addMember(alias.simpleName, kind)
        }

        members.add(Ld_TypeDefMember_Function(rName, fn, isStatic = isStatic))
    }

    override fun function(name: String, fn: C_LibMemberFunction) {
        val rName = R_Name.of(name)
        valueConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)
        members.add(Ld_TypeDefMember_SpecialFunction(rName, fn))
    }

    fun build(): Ld_TypeDef {
        val typeParamsCopy = typeParams.values.toImmList()
        Ld_Exception.check(!flags.extension || typeParamsCopy.size == 1) {
            val msg = "Extension type $simpleName must have exactly one type parameter"
            "type:extension_bad_type_params:$simpleName:${typeParamsCopy.size}" to msg
        }

        return Ld_TypeDef(
            simpleName,
            aliases = aliasesBuilder.build(),
            flags = flags,
            typeParams = typeParamsCopy,
            parent = parentType,
            rTypeFactory = rTypeFactory,
            strCodeStrategy = strCodeStrategy,
            supertypeStrategy = supertypeStrategy ?: L_TypeDefSupertypeStrategy_None,
            members = members.toImmList(),
        )
    }
}
