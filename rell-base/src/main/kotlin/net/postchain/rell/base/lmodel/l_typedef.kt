/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.doc.DocCode
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.futures.FcFuture
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

sealed class L_AbstractTypeDef {
    abstract fun getMTypeOrNull(): M_Type?
    abstract fun getTypeDefOrNull(): L_TypeDef?
}

class L_MTypeDef(private val mType: M_Type): L_AbstractTypeDef() {
    override fun getMTypeOrNull() = mType
    override fun getTypeDefOrNull() = null
}

class L_TypeDefFlags(
    val abstract: Boolean,
    val hidden: Boolean,
)

class L_TypeDefMembers(members: List<L_TypeDefMember>) {
    val all = members.toImmList()

    private val allBySymName: Map<String, L_TypeDefMember> by lazy {
        all
            .groupBy { it.symName }
            .flatMap { (name, defs) ->
                if (defs.size == 1) listOf(name to defs[0]) else defs.mapIndexed { i, def -> "$name#$i" to def }
            }
            .toImmMap()
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_TypeDefMembers {
        val replace = ReplaceState(map)
        val resAll = all.mapNotNull { member ->
            replaceTypeParamsCache(replace, member)
        }
        return L_TypeDefMembers(resAll)
    }

    private class ReplaceState(val map: Map<M_TypeParam, M_TypeSet>) {
        val cache = mutableMapOf<L_TypeDefMember, L_TypeDefMember?>()
    }

    private fun replaceTypeParamsCache(replace: ReplaceState, member: L_TypeDefMember): L_TypeDefMember? {
        return replace.cache.computeIfAbsent(member) {
            replaceTypeParams0(replace, member)
        }
    }

    private fun replaceTypeParams0(replace: ReplaceState, member: L_TypeDefMember): L_TypeDefMember? {
        return when (member) {
            is L_TypeDefMember_Constant -> member
            is L_TypeDefMember_Property -> member.replaceTypeParams(replace.map)
            is L_TypeDefMember_Constructor -> null
            is L_TypeDefMember_SpecialConstructor -> null
            is L_TypeDefMember_Function -> member.replaceTypeParams(replace.map)
            is L_TypeDefMember_ValueSpecialFunction -> member
            is L_TypeDefMember_StaticSpecialFunction -> member
            is L_TypeDefMember_Alias -> {
                val targetMember = replaceTypeParamsCache(replace, member.targetMember)
                if (targetMember == null) null else L_TypeDefMember_Alias(
                    member.simpleName,
                    member.docSymbol,
                    targetMember,
                    member.deprecated,
                )
            }
        }
    }

    fun getDocDefinition(name: String): DocDefinition? {
        return allBySymName[name]
    }
}

fun interface L_TypeDefRTypeFactory {
    fun getRType(args: List<R_Type>): R_Type?
}

fun interface L_TypeDefDocCodeStrategy {
    fun docCode(args: List<DocCode>): DocCode
}

abstract class L_TypeDefSupertypeStrategy {
    open fun isSpecialSuperTypeOf(type: M_Type): Boolean = false
    open fun isPossibleSpecialCompositeSuperTypeOf(type: M_Type_Composite): Boolean = false
}

object L_TypeDefSupertypeStrategy_None: L_TypeDefSupertypeStrategy()

sealed class L_TypeDefMember(
    val symName: String,
    override val docSymbol: DocSymbol,
): DocDefinition {
    final override fun toString() = strCode()

    abstract fun strCode(): String
}

class L_TypeDefMember_Alias(
    val simpleName: R_Name,
    doc: DocSymbol,
    val targetMember: L_TypeDefMember,
    val deprecated: C_Deprecated?,
): L_TypeDefMember(simpleName.str, doc) {
    override fun strCode(): String {
        val parts = listOfNotNull(
            L_InternalUtils.deprecatedStrCodeOrNull(deprecated),
            "alias $simpleName = ${targetMember.symName}",
        )
        return parts.joinToString(" ")
    }
}

class L_TypeDefParent(val typeDef: L_TypeDef, args: List<M_Type>) {
    val args = args.toImmList()

    init {
        checkEquals(this.args.size, typeDef.mGenericType.params.size)
    }
}

class L_TypeDef(
    val fullName: R_FullName,
    flags: L_TypeDefFlags,
    val mGenericType: M_GenericType,
    val parent: L_TypeDefParent?,
    val rTypeFactory: L_TypeDefRTypeFactory?,
    private val membersFuture: FcFuture<L_TypeDefMembers>,
    val docSymbol: DocSymbol,
): L_AbstractTypeDef() {
    val qualifiedName: R_QualifiedName = fullName.qualifiedName
    val simpleName: R_Name = qualifiedName.last
    val abstract: Boolean = flags.abstract
    val hidden: Boolean = flags.hidden

    val members: L_TypeDefMembers get() = membersFuture.getResult()

    val allMembers: L_TypeDefMembers by lazy {
        if (parent == null) members else {
            val parentMembers = if (parent.args.isEmpty()) parent.typeDef.allMembers else {
                val mParentGenType = parent.typeDef.mGenericType
                val typeArgs = mParentGenType.params
                    .mapIndexed { i, param -> param to M_TypeSets.one(parent.args[i]) }
                    .toImmMap()
                parent.typeDef.allMembers.replaceTypeParams(typeArgs)
            }

            val allMems = members.all + parentMembers.all
            L_TypeDefMembers(allMems)
        }
    }

    override fun getMTypeOrNull(): M_Type? {
        return if (mGenericType.params.isEmpty()) mGenericType.commonType else null
    }

    override fun getTypeDefOrNull() = this

    fun strCode(actualName: R_QualifiedName = qualifiedName): String {
        val parts = mutableListOf<String>()

        if (abstract) parts.add("@abstract ")
        if (hidden) parts.add("@hidden ")

        parts.add("type ")
        parts.add(actualName.str())

        if (mGenericType.params.isNotEmpty()) {
            val s = mGenericType.params.joinToString(",", "<", ">") { it.strCode() }
            parts.add(s)
        }

        if (mGenericType.parent != null) {
            parts.add(": ")
            parts.add(mGenericType.parent.strCode())
        }

        return parts.joinToString("")
    }

    fun getMType(): M_Type {
        return mGenericType.getTypeSimple()
    }

    fun getType(vararg args: M_Type): L_Type {
        checkEquals(args.size, mGenericType.params.size)
        return L_Type.make(this, args.toImmList())
    }
}

class L_NamespaceMember_Type(
    fullName: R_FullName,
    val typeDef: L_TypeDef,
    val deprecated: C_Deprecated?,
): L_NamespaceMember(fullName, typeDef.docSymbol) {
    override fun strCode(): String {
        val parts = listOfNotNull(
            L_InternalUtils.deprecatedStrCodeOrNull(deprecated),
            typeDef.strCode(qualifiedName),
        )
        return parts.joinToString(" ")
    }

    override fun getTypeDefOrNull() = typeDef
    override fun getAbstractTypeDefOrNull() = typeDef

    override fun getDocMember(name: String): DocDefinition? {
        return typeDef.members.getDocDefinition(name)
    }
}
