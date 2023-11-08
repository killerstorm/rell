/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.lib.C_SpecialLibGlobalFunctionBody
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.utils.C_RNamePath
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_BigIntegerValue
import net.postchain.rell.base.runtime.Rt_DecimalValue
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.*
import java.math.BigDecimal
import java.math.BigInteger

abstract class Ld_NamespaceMember(val simpleName: R_Name) {
    open val conflictKind: Ld_ConflictMemberKind = Ld_ConflictMemberKind.OTHER

    open fun getAliases(): List<Ld_Alias> = immListOf()

    abstract fun declare(ctx: Ld_DeclareContext): Declaration

    abstract class Declaration(val fullName: L_FullName) {
        val qualifiedName = fullName.qName

        abstract fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember>
    }
}

@RellLibDsl
interface Ld_CommonNamespaceDsl {
    fun constant(name: String, value: Long)
    fun constant(name: String, value: BigInteger)
    fun constant(name: String, value: BigDecimal)
    fun constant(name: String, type: String, value: Rt_Value)
    fun constant(name: String, type: String, getter: (R_Type) -> Rt_Value)
}

@RellLibDsl
interface Ld_NamespaceDsl: Ld_CommonNamespaceDsl {
    fun include(namespace: Ld_Namespace)
    fun link(target: String, name: String? = null)

    fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit)

    fun type(
        name: String,
        abstract: Boolean = false,
        extension: Boolean = false,
        hidden: Boolean = false,
        rType: R_Type? = null,
        block: Ld_TypeDefDsl.() -> Unit = {},
    )

    fun struct(name: String, block: Ld_StructDsl.() -> Unit)

    fun property(
        name: String,
        type: String,
        pure: Boolean = false,
        block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody,
    )

    fun property(name: String, property: C_NamespaceProperty)

    fun function(
        name: String,
        result: String? = null,
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_SpecialLibGlobalFunctionBody)

    companion object {
        fun make(block: Ld_NamespaceDsl.() -> Unit): Ld_Namespace {
            val builder = Ld_NamespaceBuilder()
            val dsl = Ld_NamespaceDslBuilder(builder)
            block(dsl)
            return builder.build()
        }
    }
}

interface Ld_CommonNamespaceMaker {
    fun constant(name: String, type: String, value: Ld_ConstantValue)
}

interface Ld_NamespaceMaker: Ld_CommonNamespaceMaker {
    fun include(namespace: Ld_Namespace)
    fun link(target: String, name: String? = null)

    fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit)

    fun type(
        name: String,
        abstract: Boolean = false,
        extension: Boolean = false,
        hidden: Boolean = false,
        rType: R_Type? = null,
        block: Ld_TypeDefDsl.() -> Unit = {},
    )

    fun struct(name: String, block: Ld_StructDsl.() -> Unit)

    fun property(
        name: String,
        type: String,
        pure: Boolean = false,
        block: Ld_NamespacePropertyDsl.() -> Ld_PropertyBody,
    )

    fun property(name: String, property: C_NamespaceProperty)

    fun function(
        name: String,
        result: String? = null,
        params: List<String>? = null,
        pure: Boolean? = null,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    )

    fun function(name: String, fn: C_SpecialLibGlobalFunctionBody)
}

class Ld_CommonNamespaceDslBuilder(
    private val maker: Ld_CommonNamespaceMaker,
): Ld_CommonNamespaceDsl {
    override fun constant(name: String, value: Long) {
        constant(name, type = "integer", value = Rt_IntValue(value))
    }

    override fun constant(name: String, value: BigInteger) {
        constant(name, type = "big_integer", value = Rt_BigIntegerValue.of(value))
    }

    override fun constant(name: String, value: BigDecimal) {
        constant(name, type = "decimal", value = Rt_DecimalValue.of(value))
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

class Ld_NamespaceDslBuilder(
    private val maker: Ld_NamespaceMaker,
): Ld_NamespaceDsl, Ld_CommonNamespaceDsl by Ld_CommonNamespaceDslBuilder(maker) {
    override fun include(namespace: Ld_Namespace) {
        maker.include(namespace)
    }

    override fun link(target: String, name: String?) {
        maker.link(target, name)
    }

    override fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit) {
        maker.namespace(name, block)
    }

    override fun type(
        name: String,
        abstract: Boolean,
        extension: Boolean,
        hidden: Boolean,
        rType: R_Type?,
        block: Ld_TypeDefDsl.() -> Unit,
    ) {
        maker.type(name, abstract, extension, hidden, rType, block)
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
        params: List<String>?,
        pure: Boolean?,
        block: Ld_FunctionDsl.() -> Ld_FunctionBodyRef,
    ) {
        maker.function(name, result, params, pure, block)
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
    private val links: MutableList<Ld_NamespaceLink> = baseNamespace.links.toMutableList()

    override fun include(namespace: Ld_Namespace) {
        for ((simpleName, ns) in namespace.namespaces) {
            namespace0(R_QualifiedName.of(simpleName)) { subBuilder ->
                subBuilder.include(ns)
            }
        }
        for (member in namespace.members) {
            addMember(member)
        }
        for (link in namespace.links) {
            addLink(link)
        }
    }

    override fun link(target: String, name: String?) {
        val targetName = R_QualifiedName.of(target)
        val simpleName = if (name != null) R_Name.of(name) else targetName.last
        addLink(Ld_NamespaceLink(simpleName, targetName, Exception()))
    }

    override fun namespace(name: String, block: Ld_NamespaceDsl.() -> Unit) {
        val qName = R_QualifiedName.of(name)
        namespace0(qName) { subBuilder ->
            val subDslBuilder = Ld_NamespaceDslBuilder(subBuilder)
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

    override fun type(
        name: String,
        abstract: Boolean,
        extension: Boolean,
        hidden: Boolean,
        rType: R_Type?,
        block: Ld_TypeDefDsl.() -> Unit,
    ) {
        val simpleName = getSimpleName(name)

        val flags = L_TypeDefFlags(
            abstract = abstract,
            extension = extension,
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

    override fun struct(name: String, block: Ld_StructDsl.() -> Unit) {
        val simpleName = getSimpleName(name)

        val builder = Ld_StructDslBuilder()
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

        val builder = Ld_NamespacePropertyDslBuilder(ldType, pure = pure)
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
        )

        val member = Ld_NamespaceMember_Function(simpleName, fn)
        addMember(member)
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

    private fun addLink(link: Ld_NamespaceLink) {
        conflictChecker.addMember(link.simpleName, Ld_ConflictMemberKind.LINK)
        links.add(link)
    }

    private fun getSimpleName(name: String): R_Name {
        return R_Name.of(name)
    }

    fun build(): Ld_Namespace {
        return Ld_Namespace(
            namespaces = namespaces.toImmMap(),
            members = members.toImmList(),
            links = links.toImmList(),
            nameKinds = conflictChecker.finish(),
        )
    }
}

class Ld_NamespaceLink(
    val simpleName: R_Name,
    val targetName: R_QualifiedName,
    val pos: Exception,
)

class Ld_Namespace(
    val namespaces: Map<R_Name, Ld_Namespace>,
    val members: List<Ld_NamespaceMember>,
    val links: List<Ld_NamespaceLink>,
    val nameKinds: Map<R_Name, Ld_ConflictMemberKind>,
) {
    fun declare(ctx: Ld_DeclareContext): Declaration {
        val namespaceDecs = namespaces.map { (simpleName, ns) ->
            val fullName = ctx.getFullName(simpleName)
            val subCtx = ctx.nestedNamespaceContext(simpleName)
            val dec = ns.declare(subCtx)
            MemDeclaration(fullName, dec)
        }

        val memberDecs = members.map { it.declare(ctx) }
        val allDecs = (namespaceDecs + memberDecs).toImmList()

        return Declaration(allDecs, links, ctx.namePath)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val ns: Declaration,
    ): Ld_NamespaceMember.Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val lNs = ns.finish(ctx)
            val doc = finishDoc()
            return immListOf(L_NamespaceMember_Namespace(qualifiedName, lNs, doc))
        }

        private fun finishDoc(): DocSymbol {
            return DocSymbol(
                kind = DocSymbolKind.NAMESPACE,
                symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
                mountName = null,
                declaration = DocDeclaration_Namespace(DocModifiers.NONE, fullName.last),
                comment = null,
            )
        }
    }

    class Declaration(
        private val members: List<Ld_NamespaceMember.Declaration>,
        private val links: List<Ld_NamespaceLink>,
        private val namePath: C_RNamePath,
    ) {
        fun finish(ctx: Ld_NamespaceFinishContext): L_Namespace {
            val lMembers = members.flatMap { it.finish(ctx) }

            val linkedMembers = links.flatMap { link ->
                val qualifiedName = namePath.qualifiedName(link.simpleName)
                val mems = resolveLink(link, qualifiedName, lMembers)
                Ld_Exception.check(mems.isNotEmpty(), link.pos) {
                    val code = "namespace:link_not_found:$qualifiedName:${link.targetName}"
                    code to "Link target not found: $qualifiedName -> ${link.targetName}"
                }
                mems
            }

            val resMembers = (lMembers + linkedMembers).toImmList()
            return L_Namespace(resMembers)
        }

        private fun resolveLink(
            link: Ld_NamespaceLink,
            qualifiedName: R_QualifiedName,
            lMembers: List<L_NamespaceMember>,
        ): List<L_NamespaceMember> {
            var nsMembers = lMembers

            for (name in link.targetName.parts.dropLast(1)) {
                val matching = nsMembers.filter { it.simpleName == name }
                val member = matching.singleOrNull()
                if (member !is L_NamespaceMember_Namespace) {
                    return immListOf()
                }
                nsMembers = member.namespace.members
            }

            return nsMembers
                .filter { it.simpleName == link.targetName.last }
                .map { makeLink(it, qualifiedName, link.pos) }
                .toImmList()
        }

        private fun makeLink(m: L_NamespaceMember, sourceName: R_QualifiedName, pos: Exception): L_NamespaceMember {
            return when (m) {
                is L_NamespaceMember_Constant -> L_NamespaceMember_Constant(sourceName, m.docSymbol, m.constant)
                is L_NamespaceMember_Function -> L_NamespaceMember_Function(sourceName, m.docSymbol, m.function, m.deprecated)
                is L_NamespaceMember_SpecialFunction -> L_NamespaceMember_SpecialFunction(sourceName, m.docSymbol, m.fn)
                is L_NamespaceMember_Property -> L_NamespaceMember_Property(sourceName, m.docSymbol, m.property)
                is L_NamespaceMember_SpecialProperty -> L_NamespaceMember_SpecialProperty(sourceName, m.docSymbol, m.property)
                is L_NamespaceMember_Struct -> L_NamespaceMember_Struct(sourceName, m.docSymbol, m.struct)
                is L_NamespaceMember_Type -> L_NamespaceMember_Type(sourceName, m.typeDef, m.deprecated)
                is L_NamespaceMember_Namespace -> {
                    val msg = "Links to a namespace are not supported: $sourceName"
                    throw Ld_Exception("namespace:bad_link:${sourceName}", msg, pos)
                }
            }
        }
    }

    companion object {
        val EMPTY = Ld_Namespace(
            namespaces = immMapOf(),
            members = immListOf(),
            links = immListOf(),
            nameKinds = immMapOf(),
        )
    }
}

private class Ld_NamespaceMember_Function(
    simpleName: R_Name,
    private val function: Ld_Function,
): Ld_NamespaceMember(simpleName) {
    override val conflictKind = Ld_ConflictMemberKind.FUNCTION

    override fun getAliases(): List<Ld_Alias> = function.aliases

    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val fullName = ctx.getFullName(simpleName)
        return MemDeclaration(fullName, function)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val function: Ld_Function,
    ): Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val lFunction = function.finish(ctx.typeCtx, fullName)

            val res = mutableListOf<L_NamespaceMember>()
            res.add(makeMember(qualifiedName, lFunction, function.deprecated))

            for (alias in function.aliases) {
                val qName = qualifiedName.replaceLast(alias.simpleName)
                res.add(makeMember(qName, lFunction, alias.deprecated ?: function.deprecated))
            }

            return res.toImmList()
        }

        private fun makeMember(
            qName: R_QualifiedName,
            lFunction: L_Function,
            deprecated: C_Deprecated?,
        ): L_NamespaceMember {
            val doc = Ld_DocSymbols.function(fullName.moduleName, qName, lFunction, false, deprecated)
            return L_NamespaceMember_Function(qName, doc, lFunction, deprecated)
        }
    }
}

private class Ld_NamespaceMember_SpecialFunction(
    simpleName: R_Name,
    private val fn: C_SpecialLibGlobalFunctionBody,
): Ld_NamespaceMember(simpleName) {
    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val fullName = ctx.getFullName(simpleName)
        return MemDeclaration(fullName, fn)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val fn: C_SpecialLibGlobalFunctionBody,
    ): Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val doc = Ld_DocSymbols.specialFunction(fullName, isStatic = false)
            return immListOf(L_NamespaceMember_SpecialFunction(qualifiedName, doc, fn))
        }
    }
}

private class Ld_NamespaceMember_Struct(
    simpleName: R_Name,
    private val struct: Ld_Struct,
): Ld_NamespaceMember(simpleName) {
    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val fullName = ctx.getFullName(simpleName)
        val structDec = struct.declare(ctx, fullName.qName)
        return MemDeclaration(fullName, structDec)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val struct: Ld_Struct.Declaration,
    ): Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val lStruct = struct.finish(ctx, fullName)
            val doc = finishDoc(fullName)
            return immListOf(L_NamespaceMember_Struct(qualifiedName, doc, lStruct))
        }

        private fun finishDoc(fullName: L_FullName): DocSymbol {
            return DocSymbol(
                kind = DocSymbolKind.STRUCT,
                symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
                mountName = null,
                declaration = DocDeclaration_Struct(DocModifiers.NONE, fullName.last),
                comment = null,
            )
        }
    }
}

private class Ld_NamespaceMember_Constant(
    simpleName: R_Name,
    private val constant: Ld_Constant,
): Ld_NamespaceMember(simpleName) {
    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val fullName = ctx.getFullName(simpleName)
        return MemDeclaration(fullName, constant)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val constant: Ld_Constant,
    ): Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val lConstant = constant.finish(ctx.typeCtx, fullName.last)
            val doc = Ld_DocSymbols.constant(fullName, lConstant.type, lConstant.value)
            return immListOf(L_NamespaceMember_Constant(fullName.qName, doc, lConstant))
        }
    }
}

private class Ld_NamespaceMember_Property(
    simpleName: R_Name,
    private val property: Ld_NamespaceProperty,
): Ld_NamespaceMember(simpleName) {
    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val fullName = ctx.getFullName(simpleName)
        return MemDeclaration(fullName, property)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val property: Ld_NamespaceProperty,
    ): Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val lProperty = property.finish(ctx.typeCtx)
            val doc = Ld_DocSymbols.property(fullName, lProperty.type, lProperty.pure)
            return immListOf(L_NamespaceMember_Property(qualifiedName, doc, lProperty))
        }
    }
}

private class Ld_NamespaceMember_SpecialProperty(
    simpleName: R_Name,
    private val property: C_NamespaceProperty,
): Ld_NamespaceMember(simpleName) {
    override fun declare(ctx: Ld_DeclareContext): Declaration {
        val fullName = ctx.getFullName(simpleName)
        return MemDeclaration(fullName, property)
    }

    private class MemDeclaration(
        fullName: L_FullName,
        private val property: C_NamespaceProperty,
    ): Declaration(fullName) {
        override fun finish(ctx: Ld_NamespaceFinishContext): List<L_NamespaceMember> {
            val doc = finishDoc()
            return immListOf(L_NamespaceMember_SpecialProperty(qualifiedName, doc, property))
        }

        private fun finishDoc(): DocSymbol {
            return DocSymbol(
                kind = DocSymbolKind.PROPERTY,
                symbolName = DocSymbolName.global(fullName.moduleName.str(), fullName.qName.str()),
                mountName = null,
                declaration = DocDeclaration_SpecialProperty(fullName.last),
                comment = null,
            )
        }
    }
}
