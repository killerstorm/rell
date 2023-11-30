/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SpecialLibMemberFunctionBody
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.*
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
    abstract fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember>
}

private class Ld_TypeDefMember_Constructor(private val constructor: Ld_Constructor): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val lConstructor = constructor.finish(ctx, typeName)
        val doc = makeDoc(typeName, lConstructor)
        return immListOf(L_TypeDefMember_Constructor(lConstructor, doc))
    }

    private fun makeDoc(typeName: L_FullName, lConstructor: L_Constructor): DocSymbol {
        val docDeclaration = DocDeclaration_TypeConstructor(
            L_TypeUtils.docTypeParams(lConstructor.header.typeParams),
            lConstructor.header.params.map { it.docSymbol.declaration }.toImmList(),
            deprecated = lConstructor.deprecated,
            pure = lConstructor.pure,
        )

        return DocSymbol(
            kind = DocSymbolKind.CONSTRUCTOR,
            symbolName = DocSymbolName.global(typeName.moduleName.str(), typeName.qName.str()), //TODO proper name
            mountName = null,
            declaration = docDeclaration,
            comment = null,
        )
    }
}

private class Ld_TypeDefMember_SpecialConstructor(private val fn: C_SpecialLibGlobalFunctionBody): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val doc = makeDoc(typeName)
        return immListOf(L_TypeDefMember_SpecialConstructor(fn, doc))
    }

    private fun makeDoc(typeName: L_FullName): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.CONSTRUCTOR,
            symbolName = DocSymbolName.global(typeName.moduleName.str(), typeName.qName.str()), //TODO proper name
            mountName = null,
            declaration = DocDeclaration_TypeSpecialConstructor(),
            comment = null,
        )
    }
}

private class Ld_TypeDefMember_Constant(
    private val simpleName: R_Name,
    private val constant: Ld_Constant,
): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val fullName = typeName.append(simpleName)
        val lConstant = constant.finish(ctx, simpleName)
        val doc = Ld_DocSymbols.constant(fullName, lConstant.type, lConstant.value)
        return immListOf(L_TypeDefMember_Constant(lConstant, doc))
    }
}

private class Ld_TypeDefMember_Property(private val property: Ld_TypeProperty): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val fullName = typeName.append(property.simpleName)
        val lProperty = property.finish(ctx)
        val doc = Ld_DocSymbols.property(fullName, lProperty.type, lProperty.body.pure)
        return immListOf(L_TypeDefMember_Property(property.simpleName, doc, lProperty))
    }
}

private class Ld_TypeDefMember_Function(
    private val simpleName: R_Name,
    private val function: Ld_Function,
    private val isStatic: Boolean,
): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val fullName = typeName.append(simpleName)
        val lFunction = function.finish(ctx, fullName)

        val res = mutableListOf<L_TypeDefMember>()
        res.add(makeMember(typeName, simpleName, lFunction, function.deprecated))

        for (alias in function.aliases) {
            res.add(makeMember(typeName, alias.simpleName, lFunction, alias.deprecated))
        }

        return res.toImmList()
    }

    private fun makeMember(
        typeName: L_FullName,
        name: R_Name,
        lFunction: L_Function,
        deprecated: C_Deprecated?,
    ): L_TypeDefMember {
        val qName = typeName.qName.append(name)
        val doc = Ld_DocSymbols.function(typeName.moduleName, qName, lFunction, isStatic, deprecated)
        return L_TypeDefMember_Function(name, doc, lFunction, isStatic = isStatic, deprecated = deprecated)
    }
}

private class Ld_TypeDefMember_ValueSpecialFunction(
    private val simpleName: R_Name,
    private val fn: C_SpecialLibMemberFunctionBody,
): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val fullName = typeName.append(simpleName)
        val doc = Ld_DocSymbols.specialFunction(fullName, isStatic = false)
        return immListOf(L_TypeDefMember_ValueSpecialFunction(simpleName, doc, fn))
    }
}

private class Ld_TypeDefMember_StaticSpecialFunction(
    private val simpleName: R_Name,
    private val fn: C_SpecialLibGlobalFunctionBody,
): Ld_TypeDefMember() {
    override fun finish(ctx: Ld_TypeFinishContext, typeName: L_FullName): List<L_TypeDefMember> {
        val fullName = typeName.append(simpleName)
        val doc = Ld_DocSymbols.specialFunction(fullName, isStatic = true)
        return immListOf(L_TypeDefMember_StaticSpecialFunction(simpleName, doc, fn))
    }
}

class Ld_TypeDef(
    val simpleName: R_Name,
    val aliases: List<Ld_Alias>,
    private val flags: L_TypeDefFlags,
    private val typeParams: List<Ld_TypeParam>,
    private val parent: Ld_TypeDefParent?,
    private val rTypeFactory: L_TypeDefRTypeFactory?,
    private val docCodeStrategy: L_TypeDefDocCodeStrategy?,
    private val supertypeStrategy: L_TypeDefSupertypeStrategy,
    private val members: List<Ld_TypeDefMember>,
) {
    fun declare(ctx: Ld_DeclareContext, fullName: L_FullName): Declaration {
        val dec = Declaration(fullName, aliases)
        ctx.declareType(fullName.qName, dec)
        return dec
    }

    inner class Declaration(
        private val fullName: L_FullName,
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
                val i = stack.indexOf(fullName)
                val cycle = (stack.drop(max(i, 0)) + listOf(fullName)).map { it.str() }
                val cycleCode = cycle.joinToString(",")
                val cycleMsg = cycle.joinToString(", ")
                "type_cycle:$cycleCode" to "Type recursion: $cycleMsg"
            }

            typeDefMaking = true

            val subCtx = ctx.pushType(fullName)
            val typeCtx = subCtx.typeCtx
            val (mTypeParams, mTypeParamMap) = Ld_TypeParam.finishList(typeCtx, typeParams)

            val subTypeCtx = typeCtx.subCtx(typeParams = mTypeParamMap)

            val lParent = parent?.finish(subTypeCtx)

            val mParent = if (lParent == null) null else M_GenericTypeParent(lParent.typeDef.mGenericType, lParent.args)
            val mGenericType = makeGenericType(fullName.qName, mTypeParams, mParent)

            val bodyRef = Ld_TypeDefMembersRef()

            val docSym = makeDocSymbol(fullName, mTypeParams, lParent)

            val typeDef = L_TypeDef(
                fullName,
                flags = flags,
                mGenericType = mGenericType,
                parent = lParent,
                rTypeFactory = rTypeFactory,
                membersSupplier = bodyRef,
                docSymbol = docSym,
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
                    return L_TypeUtils.makeMGenericType(rType, mParent, docCodeStrategy)
                }
            }

            return L_TypeUtils.makeMGenericType(
                qualifiedName.str(),
                mTypeParams,
                mParent,
                rTypeFactory = rTypeFactory,
                docCodeStrategy = docCodeStrategy,
                supertypeStrategy = supertypeStrategy,
            )
        }

        private fun makeDocSymbol(
            fullName: L_FullName,
            mTypeParams: List<M_TypeParam>,
            lParent: L_TypeDefParent?,
        ): DocSymbol {
            val docTypeParams = L_TypeUtils.docTypeParams(mTypeParams)
            return DocSymbol(
                kind = DocSymbolKind.TYPE,
                symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
                mountName = null,
                declaration = DocDeclaration_Type(fullName.last, docTypeParams, lParent, flags),
                comment = null,
            )
        }

        fun finish(ctx: Ld_NamespaceFinishContext): L_TypeDef {
            check(!finished)
            finished = true

            val typeDefRec = getTypeDef0(ctx)

            val typeCtx = ctx.typeCtx.subCtx(typeParams = typeDefRec.typeParams)
            val lMembers = members.flatMap { it.finish(typeCtx, fullName) }.toImmList()

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

            val dsl = Ld_TypeDefDsl(simpleName.str, builder)
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
        val fullName = ctx.getFullName(simpleName)
        val typeDeclaration = typeDef.declare(ctx, fullName)
        return MemDeclaration(fullName, typeDeclaration)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val typeDeclaration: Ld_TypeDef.Declaration,
    ): Declaration(fullName) {
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
    fun docCodeStrategy(strategy: L_TypeDefDocCodeStrategy)
    fun supertypeStrategy(strategy: L_TypeDefSupertypeStrategy)

    fun property(name: String, type: String, body: C_SysFunctionBody)

    fun constructor(params: List<String>?, pure: Boolean?, block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef)
    fun constructor(fn: C_SpecialLibGlobalFunctionBody)

    fun function(
        isStatic: Boolean,
        name: String,
        result: String?,
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_SpecialLibGlobalFunctionBody)
    fun function(name: String, fn: C_SpecialLibMemberFunctionBody)
}

@RellLibDsl
class Ld_TypeDefDsl(
    val typeSimpleName: String,
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

    fun rTypeFactory(factory: L_TypeDefRTypeFactory) {
        maker.rTypeFactory(factory)
    }

    fun docCode(calculator: (DocCode) -> DocCode) {
        maker.docCodeStrategy { args ->
            checkEquals(args.size, 1)
            calculator(args[0])
        }
    }

    fun docCode(calculator: (DocCode, DocCode) -> DocCode) {
        maker.docCodeStrategy { args ->
            checkEquals(args.size, 2)
            calculator(args[0], args[1])
        }
    }

    fun docCode(calculator: (DocCode, DocCode, DocCode) -> DocCode) {
        maker.docCodeStrategy { args ->
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
        val body = C_SysFunctionBody.simple(pure = pure, rCode = getter)
        maker.property(name, type, body)
    }

    fun property(name: String, type: String, body: C_SysFunctionBody) {
        maker.property(name, type, body)
    }

    fun constructor(
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef,
    ) {
        maker.constructor(params, pure = pure, block = block)
    }

    fun constructor(fn: C_SpecialLibGlobalFunctionBody) {
        maker.constructor(fn)
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

    fun function(name: String, fn: C_SpecialLibMemberFunctionBody) {
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

    fun staticFunction(name: String, fn: C_SpecialLibGlobalFunctionBody) {
        maker.function(name, fn)
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
    private var docCodeStrategy: L_TypeDefDocCodeStrategy? = null
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

    override fun docCodeStrategy(strategy: L_TypeDefDocCodeStrategy) {
        check(docCodeStrategy == null) { "strCode strategy already set" }
        check(members.isEmpty()) { "Trying to set strCode strategy after a member" }
        docCodeStrategy = strategy
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

    override fun constant(name: String, type: String, value: Ld_ConstantValue) {
        val rName = R_Name.of(name)
        staticConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)

        val ldType = Ld_Type.parse(type)
        val constant = Ld_Constant(ldType, value)
        members.add(Ld_TypeDefMember_Constant(rName, constant))
    }

    override fun property(name: String, type: String, body: C_SysFunctionBody) {
        val rName = R_Name.of(name)
        valueConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)

        val ldType = Ld_Type.parse(type)
        val property = Ld_TypeProperty(rName, ldType, body)
        members.add(Ld_TypeDefMember_Property(property))
    }

    override fun constructor(params: List<String>?, pure: Boolean?, block: Ld_ConstructorDsl.() -> Ld_FunctionBodyRef) {
        checkCanHaveConstructor()

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

    override fun constructor(fn: C_SpecialLibGlobalFunctionBody) {
        checkCanHaveConstructor()
        members.add(Ld_TypeDefMember_SpecialConstructor(fn))
    }

    private fun checkCanHaveConstructor() {
        Ld_Exception.check(!flags.abstract) {
            "type:abstract_constructor:$simpleName" to "Abstract type cannot have a constructor: $simpleName"
        }
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

    override fun function(name: String, fn: C_SpecialLibGlobalFunctionBody) {
        val rName = R_Name.of(name)
        staticConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)
        members.add(Ld_TypeDefMember_StaticSpecialFunction(rName, fn))
    }

    override fun function(name: String, fn: C_SpecialLibMemberFunctionBody) {
        val rName = R_Name.of(name)
        valueConflictChecker.addMember(rName, Ld_ConflictMemberKind.OTHER)
        members.add(Ld_TypeDefMember_ValueSpecialFunction(rName, fn))
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
            docCodeStrategy = docCodeStrategy,
            supertypeStrategy = supertypeStrategy ?: L_TypeDefSupertypeStrategy_None,
            members = members.toImmList(),
        )
    }
}
