/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunctionBody
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.utils.C_DocUtils
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_BigIntegerValue
import net.postchain.rell.base.runtime.Rt_DecimalValue
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.*
import net.postchain.rell.base.utils.futures.FcFuture
import net.postchain.rell.base.utils.futures.component1
import net.postchain.rell.base.utils.futures.component2
import java.math.BigDecimal
import java.math.BigInteger

abstract class Ld_NamespaceMember(val simpleName: R_Name) {
    open val conflictKind: Ld_ConflictMemberKind = Ld_ConflictMemberKind.OTHER

    open fun getAliases(): List<Ld_Alias> = immListOf()

    abstract fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>>
}

interface Ld_CommonNamespaceMaker {
    fun constant(name: String, type: String, value: Ld_ConstantValue)
}

interface Ld_NamespaceMaker: Ld_CommonNamespaceMaker {
    fun include(namespace: Ld_Namespace)
    fun alias(name: String?, target: String, deprecated: C_Deprecated?)

    fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit)

    fun type(
        name: String,
        abstract: Boolean,
        hidden: Boolean,
        rType: R_Type?,
        block: Ld_TypeDefDsl.() -> Unit,
    )

    fun extension(name: String, type: String, block: Ld_TypeExtensionDsl.() -> Unit)

    fun struct(name: String, block: Ld_StructDsl.() -> Unit)

    fun property(
        name: String,
        type: String,
        pure: Boolean,
        block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody,
    )

    fun property(name: String, property: C_NamespaceProperty)

    fun function(
        name: String,
        result: String?,
        description: String?,
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_SpecialLibGlobalFunctionBody)
}

class Ld_CommonNamespaceDslImpl(
    private val maker: Ld_CommonNamespaceMaker,
): Ld_CommonNamespaceDsl {
    override fun constant(name: String, value: Long) {
        constant(name, type = "integer", value = Rt_IntValue.get(value))
    }

    override fun constant(name: String, value: BigInteger) {
        constant(name, type = "big_integer", value = Rt_BigIntegerValue.get(value))
    }

    override fun constant(name: String, value: BigDecimal) {
        constant(name, type = "decimal", value = Rt_DecimalValue.get(value))
    }

    override fun constant(name: String, type: String, value: Rt_Value) {
        val ldValue = Ld_ConstantValue.make(value)
        maker.constant(name, type, ldValue)
    }

    override fun constant(name: String, type: String, getter: (R_Type) -> Rt_Value) {
        val ldValue = Ld_ConstantValue.make(getter)
        maker.constant(name, type, ldValue)
    }
}

class Ld_NamespaceDslImpl(
    private val maker: Ld_NamespaceMaker,
): Ld_NamespaceDsl, Ld_CommonNamespaceDsl by Ld_CommonNamespaceDslImpl(maker) {
    override fun include(namespace: Ld_Namespace) {
        maker.include(namespace)
    }

    override fun alias(name: String?, target: String, deprecated: C_Deprecated?) {
        maker.alias(name, target, deprecated)
    }

    override fun alias(name: String?, target: String, deprecated: C_MessageType) {
        val cDeprecated = C_Deprecated.makeOrNull(deprecated, useInstead = target)
        maker.alias(name, target, cDeprecated)
    }

    override fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit) {
        maker.namespace(name, block)
    }

    override fun type(
        name: String,
        abstract: Boolean,
        hidden: Boolean,
        rType: R_Type?,
        block: Ld_TypeDefDsl.() -> Unit,
    ) {
        maker.type(name, abstract, hidden, rType, block)
    }

    override fun extension(name: String, type: String, block: Ld_TypeExtensionDsl.() -> Unit) {
        maker.extension(name, type, block)
    }

    override fun struct(name: String, block: Ld_StructDsl.() -> Unit) {
        maker.struct(name, block)
    }

    override fun property(
        name: String,
        type: String,
        pure: Boolean,
        block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody,
    ) {
        maker.property(name, type, pure, block)
    }

    override fun property(name: String, property: C_NamespaceProperty) {
        maker.property(name, property)
    }

    override fun function(
        name: String,
        result: String?,
        description: String?,
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    ) {
        maker.function(name, result, description, params, pure, block)
    }

    override fun function(name: String, fn: C_SpecialLibGlobalFunctionBody) {
        maker.function(name, fn)
    }
}

class Ld_NamespaceBuilder(
    baseNamespace: Ld_Namespace = Ld_Namespace.EMPTY
): Ld_NamespaceMaker {
    private val conflictChecker = Ld_MemberConflictChecker(baseNamespace.nameKinds)
    private val namespaces: MutableMap<R_Name, Ld_Namespace> = baseNamespace.namespaces.toMutableMap()
    private val members: MutableList<Ld_NamespaceMember> = baseNamespace.members.toMutableList()

    override fun include(namespace: Ld_Namespace) {
        for ((simpleName, ns) in namespace.namespaces) {
            namespace0(R_QualifiedName.of(simpleName)) { subBuilder ->
                subBuilder.include(ns)
            }
        }
        for (member in namespace.members) {
            addMember(member)
        }
    }

    override fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit) {
        val qName = R_QualifiedName.of(name)
        namespace0(qName) { subBuilder ->
            val subDslBuilder = Ld_NamespaceDslImpl(subBuilder)
            block(subDslBuilder)
        }
    }

    private fun namespace0(namespaceName: R_QualifiedName, block: (Ld_NamespaceBuilder) -> Unit) {
        val simpleName = namespaceName.first
        conflictChecker.addMember(simpleName, Ld_ConflictMemberKind.NAMESPACE)

        val oldNs = namespaces[simpleName] ?: Ld_Namespace.EMPTY
        val subBuilder = Ld_NamespaceBuilder(oldNs)

        if (namespaceName.size() > 1) {
            val subQualifiedName = R_QualifiedName(namespaceName.parts.drop(1))
            subBuilder.namespace0(subQualifiedName, block)
        } else {
            block(subBuilder)
        }

        val ns = subBuilder.build()
        namespaces[simpleName] = ns
    }

    override fun alias(name: String?, target: String, deprecated: C_Deprecated?) {
        val targetName = R_QualifiedName.of(target)
        val simpleName = if (name != null) getSimpleName(name) else targetName.last
        addMember(Ld_NamespaceMember_Alias(simpleName, targetName, deprecated, Exception()))
    }

    override fun type(
        name: String,
        abstract: Boolean,
        hidden: Boolean,
        rType: R_Type?,
        block: Ld_TypeDefDsl.() -> Unit,
    ) {
        val simpleName = getSimpleName(name)

        val flags = L_TypeDefFlags(
            abstract = abstract,
            hidden = hidden,
        )

        val typeDef = Ld_TypeDef.make(
            simpleName,
            flags = flags,
            rType = rType,
            block = block,
        )

        val member = Ld_NamespaceMember_Type(simpleName, typeDef)
        addMember(member)
    }

    override fun extension(name: String, type: String, block: Ld_TypeExtensionDsl.() -> Unit) {
        val simpleName = getSimpleName(name)

        val ldType = Ld_Type.parse(type)

        // Using a type def internally to collect type members. No obvious reason to create a separate class for
        // extensions - no problems using L_TypeDef.

        val typeDefBlock: Ld_TypeDefDsl.() -> Unit = {
            val typeDsl: Ld_TypeDefDsl = this
            val extDsl = object: Ld_TypeExtensionDsl, Ld_CommonTypeDsl by typeDsl {}
            block(extDsl)
        }

        val typeDef = Ld_TypeDef.make(
            simpleName,
            flags = L_TypeDefFlags(abstract = true, hidden = true),
            rType = null,
            block = typeDefBlock,
        )

        val member = Ld_NamespaceMember_TypeExtension(simpleName, ldType, typeDef)
        addMember(member)
    }

    override fun struct(name: String, block: Ld_StructDsl.() -> Unit) {
        val simpleName = getSimpleName(name)

        val builder = Ld_StructDslImpl()
        block(builder)

        val struct = builder.build()
        val member = Ld_NamespaceMember_Struct(simpleName, struct)
        addMember(member)
    }

    override fun constant(name: String, type: String, value: Ld_ConstantValue) {
        val simpleName = getSimpleName(name)
        val ldType = Ld_Type.parse(type)
        val constant = Ld_Constant(ldType, value)
        val member = Ld_NamespaceMember_Constant(simpleName, constant)
        addMember(member)
    }

    override fun property(
        name: String,
        type: String,
        pure: Boolean,
        block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody,
    ) {
        val simpleName = getSimpleName(name)
        val ldType = Ld_Type.parse(type)

        val builder = Ld_NamespacePropertyDslImpl(ldType, pure = pure)
        val property = builder.build(block)

        val member = Ld_NamespaceMember_Property(simpleName, property)
        addMember(member)
    }

    override fun property(name: String, property: C_NamespaceProperty) {
        val simpleName = getSimpleName(name)
        val member = Ld_NamespaceMember_SpecialProperty(simpleName, property)
        addMember(member)
    }

    override fun function(
        name: String,
        result: String?,
        description: String?,
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    ) {
        val simpleName = getSimpleName(name)

        val fn = Ld_FunctionBuilder.build(
            simpleName = simpleName,
            result = result,
            params = params,
            pure = pure,
            outerTypeParams = immSetOf(),
            block = block,
                description = description,
        )

        conflictChecker.addMember(simpleName, Ld_ConflictMemberKind.FUNCTION)
        addMember(Ld_NamespaceMember_Function(simpleName, fn, false))
    }

    override fun function(name: String, fn: C_SpecialLibGlobalFunctionBody) {
        val simpleName = getSimpleName(name)
        val member = Ld_NamespaceMember_SpecialFunction(simpleName, fn)
        addMember(member)
    }

    private fun addMember(member: Ld_NamespaceMember) {
        val kind = member.conflictKind
        conflictChecker.addMember(member.simpleName, kind)
        for (alias in member.getAliases()) {
            conflictChecker.addMember(alias.simpleName, kind)
        }
        members.add(member)
    }

    private fun getSimpleName(name: String): R_Name {
        return R_Name.of(name)
    }

    fun build(): Ld_Namespace {
        return Ld_Namespace(
            namespaces = namespaces.toImmMap(),
            members = members.toImmList(),
            nameKinds = conflictChecker.finish(),
        )
    }
}

class Ld_Namespace(
    val namespaces: Map<R_Name, Ld_Namespace>,
    val members: List<Ld_NamespaceMember>,
    val nameKinds: Map<R_Name, Ld_ConflictMemberKind>,
) {
    fun process(ctx: Ld_NamespaceContext): FcFuture<L_Namespace> {
        val namespaceFutures = namespaces.mapValues {
            val subCtx = ctx.nestedNamespaceContext(it.key)
            it.value.process(subCtx)
        }

        val membersFutures = members.map {
            processMember(ctx, it)
        }

        return ctx.fcExec.future()
            .after(namespaceFutures)
            .after(membersFutures)
            .compute { (lNamespaces, lOtherMembers) ->
                val lNsMembers = lNamespaces.map {
                    val fullName = ctx.getFullName(it.key)
                    val doc = makeDoc(fullName)
                    L_NamespaceMember_Namespace(fullName, it.value, doc)
                }

                val lAllMembers = (lNsMembers + lOtherMembers.flatten()).toImmList()
                L_Namespace(lAllMembers)
            }
    }

    private fun processMember(ctx: Ld_NamespaceContext, member: Ld_NamespaceMember): FcFuture<List<L_NamespaceMember>> {
        val future = member.process(ctx)
        val fullName = ctx.getFullName(member.simpleName)
        ctx.declareMember(fullName.qualifiedName, future)

        future.getResult().first()
        val futures = mutableListOf(future)

        for (alias in member.getAliases()) {
            val aliasFullName = fullName.replaceLast(alias.simpleName)
            val aliasFuture = ctx.fcExec.future().after(future).compute { targetMembers ->
                targetMembers.map {
                    Ld_NamespaceMember_Alias.finishMember(aliasFullName, it, alias.deprecated)
                }
            }
            futures.add(aliasFuture)
        }

        return ctx.fcExec.future().after(futures.toImmList()).compute { lists ->
            lists.flatten()
        }
    }

    private fun makeDoc(fullName: R_FullName): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.NAMESPACE,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = DocDeclaration_Namespace(DocModifiers.NONE, fullName.last),
            comment = null,
        )
    }

    companion object {
        val EMPTY = Ld_Namespace(
            namespaces = immMapOf(),
            members = immListOf(),
            nameKinds = immMapOf(),
        )
    }
}

private class Ld_NamespaceMember_Alias(
    simpleName: R_Name,
    private val targetName: R_QualifiedName,
    private val deprecated: C_Deprecated?,
    private val errPos: Exception,
): Ld_NamespaceMember(simpleName) {
    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        return ctx.fcExec.future().after(ctx.finishCtxFuture).compute { finCtx ->
            finish(finCtx, fullName)
        }
    }

    private fun finish(ctx: Ld_NamespaceFinishContext, fullName: R_FullName): List<L_NamespaceMember> {
        val members = ctx.getNamespaceMembers(targetName, errPos)
        check(members.isNotEmpty()) { "Alias target not found: ${fullName.qualifiedName} -> $targetName" }
        return members.map {
            finishMember(fullName, it, deprecated)
        }
    }

    companion object {
        fun finishMember(
            fullName: R_FullName,
            targetMember: L_NamespaceMember,
            deprecated: C_Deprecated?,
        ): L_NamespaceMember {
            val targetMembersChain = CommonUtils.chainToList(targetMember) {
                (it as? L_NamespaceMember_Alias)?.targetMember
            }
            val finalTargetMember = targetMembersChain.last()

            val doc = makeDocSymbol(fullName, targetMember, deprecated)

            return L_NamespaceMember_Alias(
                fullName,
                doc,
                targetMember,
                finalTargetMember,
                deprecated,
            )
        }

        private fun makeDocSymbol(
            fullName: R_FullName,
            targetMember: L_NamespaceMember,
            deprecated: C_Deprecated?,
        ): DocSymbol {
            val docDec = DocDeclaration_Alias(
                C_DocUtils.docModifiers(deprecated),
                fullName.last,
                targetMember.qualifiedName,
                targetMember.docSymbol.declaration,
            )

            return DocSymbol(
                kind = DocSymbolKind.ALIAS,
                symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
                mountName = null,
                declaration = docDec,
                comment = null,
            )
        }
    }
}

private class Ld_NamespaceMember_Function(
    simpleName: R_Name,
    private val function: Ld_Function,
    private val isStatic: Boolean,
): Ld_NamespaceMember(simpleName) {
    override val conflictKind = Ld_ConflictMemberKind.FUNCTION

    override fun getAliases(): List<Ld_Alias> = function.aliases

    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        return ctx.fcExec.future().after(ctx.finishCtxFuture).compute { finCtx ->
            finish(finCtx, fullName)
        }
    }

    private fun finish(ctx: Ld_NamespaceFinishContext, fullName: R_FullName): List<L_NamespaceMember> {
        val lFunction = function.finish(ctx.typeCtx, fullName, isStatic)

        val docSymbol = Ld_DocSymbols.function(
            fullName,
            header = lFunction.header,
            flags = lFunction.flags,
            deprecated = function.deprecated,
        )

        val member = L_NamespaceMember_Function(fullName, docSymbol, lFunction, function.deprecated)
        return immListOf(member)
    }
}

private class Ld_NamespaceMember_SpecialFunction(
    simpleName: R_Name,
    private val fn: C_SpecialLibGlobalFunctionBody,
): Ld_NamespaceMember(simpleName) {
    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        val doc = Ld_DocSymbols.specialFunction(fullName, isStatic = false)
        return ctx.fcExec.future().compute {
            val member = L_NamespaceMember_SpecialFunction(fullName, doc, fn)
            immListOf(member)
        }
    }
}

private class Ld_NamespaceMember_Struct(
    simpleName: R_Name,
    private val struct: Ld_Struct,
): Ld_NamespaceMember(simpleName) {
    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        val structFuture = struct.process(ctx, fullName)
        return ctx.fcExec.future().after(structFuture).compute { lStruct ->
            val doc = finishDoc(fullName)
            val member = L_NamespaceMember_Struct(fullName, doc, lStruct)
            immListOf(member)
        }
    }

    private fun finishDoc(fullName: R_FullName): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.STRUCT,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = DocDeclaration_Struct(DocModifiers.NONE, fullName.last),
            comment = null,
        )
    }
}

private class Ld_NamespaceMember_Constant(
    simpleName: R_Name,
    private val constant: Ld_Constant,
): Ld_NamespaceMember(simpleName) {
    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        val future = constant.process(ctx, simpleName)
        return ctx.fcExec.future().after(future).compute { lConstant ->
            val doc = Ld_DocSymbols.constant(fullName, lConstant.type, lConstant.value)
            val member = L_NamespaceMember_Constant(fullName, doc, lConstant)
            immListOf(member)
        }
    }
}

private class Ld_NamespaceMember_Property(
    simpleName: R_Name,
    private val property: Ld_NamespaceProperty,
): Ld_NamespaceMember(simpleName) {
    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        return ctx.fcExec.future().after(ctx.finishCtxFuture).compute { finCtx ->
            val lProperty = property.finish(finCtx.typeCtx)
            val doc = Ld_DocSymbols.property(fullName, lProperty.type, lProperty.pure)
            val member = L_NamespaceMember_Property(fullName, doc, lProperty)
            immListOf(member)
        }
    }
}

private class Ld_NamespaceMember_SpecialProperty(
    simpleName: R_Name,
    private val property: C_NamespaceProperty,
): Ld_NamespaceMember(simpleName) {
    override fun process(ctx: Ld_NamespaceContext): FcFuture<List<L_NamespaceMember>> {
        val fullName = ctx.getFullName(simpleName)
        val doc = finishDoc(fullName)
        return ctx.fcExec.future().compute {
            val member = L_NamespaceMember_SpecialProperty(fullName, doc, property)
            immListOf(member)
        }
    }

    private fun finishDoc(fullName: R_FullName): DocSymbol {
        return DocSymbol(
            kind = DocSymbolKind.PROPERTY,
            symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qualifiedName.str()),
            mountName = null,
            declaration = DocDeclaration_SpecialProperty(fullName.last),
            comment = null,
        )
    }
}
