/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap
import java.util.function.Supplier

class L_TypeDefFlags(
    val abstract: Boolean,
    val extension: Boolean,
    val hidden: Boolean,
)

class L_TypeDefMembers(members: List<L_TypeDefMember>) {
    val all = members.toImmList()

    val constants: List<L_Constant>
    val properties: List<L_TypeDefMember_Property>
    val constructors: List<L_Constructor>
    val specialValueFunctions: List<L_TypeDefMember_SpecialFunction>
    val valueFunctionsByName: Map<R_Name, List<L_TypeDefMember_Function>>
    val staticFunctionsByName: Map<R_Name, List<L_TypeDefMember_Function>>

    init {
        val constants = mutableListOf<L_Constant>()
        val properties = mutableListOf<L_TypeDefMember_Property>()
        val constructors = mutableListOf<L_Constructor>()
        val staticFunctions = mutableListOf<L_TypeDefMember_Function>()
        val valueFunctions = mutableListOf<L_TypeDefMember_Function>()
        val specialValueFunctions = mutableListOf<L_TypeDefMember_SpecialFunction>()

        for (member in all) {
            when (member) {
                is L_TypeDefMember_Constant -> constants.add(member.constant)
                is L_TypeDefMember_Property -> properties.add(member)
                is L_TypeDefMember_Constructor -> constructors.add(member.constructor)
                is L_TypeDefMember_SpecialFunction -> specialValueFunctions.add(member)
                is L_TypeDefMember_Function -> {
                    val list = if (member.isStatic) staticFunctions else valueFunctions
                    list.add(member)
                }
            }
        }

        this.constants = constants.toImmList()
        this.properties = properties.toImmList()
        this.constructors = constructors.toImmList()
        this.specialValueFunctions = specialValueFunctions.toImmList()

        valueFunctionsByName = valueFunctions
            .groupBy { it.simpleName }
            .mapValues { it.value.toImmList() }
            .toImmMap()

        staticFunctionsByName = staticFunctions
            .groupBy { it.simpleName }
            .mapValues { it.value.toImmList() }
            .toImmMap()
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_TypeDefMembers {
        val resAll = all.mapNotNull { member ->
            when (member) {
                is L_TypeDefMember_Constant -> member
                is L_TypeDefMember_Property -> member.replaceTypeParams(map)
                is L_TypeDefMember_Constructor -> null
                is L_TypeDefMember_Function -> member.replaceTypeParams(map)
                is L_TypeDefMember_SpecialFunction -> member
            }
        }
        return L_TypeDefMembers(resAll)
    }
}

fun interface L_TypeDefRTypeFactory {
    fun getRType(args: List<R_Type>): R_Type?
}

fun interface L_TypeDefStrCodeStrategy {
    fun strCode(typeName: String, args: List<M_TypeSet>): String
}

abstract class L_TypeDefSupertypeStrategy {
    open fun isSpecialSuperTypeOf(type: M_Type): Boolean = false
    open fun isPossibleSpecialCompositeSuperTypeOf(type: M_Type_Composite): Boolean = false
}

object L_TypeDefSupertypeStrategy_None: L_TypeDefSupertypeStrategy()

sealed class L_TypeDefMember {
    final override fun toString() = strCode()

    abstract fun strCode(): String
}

class L_TypeDefParent(val typeDef: L_TypeDef, args: List<M_Type>) {
    val args = args.toImmList()

    init {
        checkEquals(this.args.size, typeDef.mGenericType.params.size)
    }
}

class L_TypeDef(
    val fullName: L_FullName,
    flags: L_TypeDefFlags,
    val mGenericType: M_GenericType,
    val parent: L_TypeDefParent?,
    val rTypeFactory: L_TypeDefRTypeFactory?,
    private val membersSupplier: Supplier<L_TypeDefMembers>,
) {
    val qualifiedName: R_QualifiedName = fullName.qName
    val simpleName: R_Name = qualifiedName.last
    val abstract: Boolean = flags.abstract
    val extension: Boolean = flags.extension
    val hidden: Boolean = flags.hidden

    val members: L_TypeDefMembers by lazy {
        membersSupplier.get()
    }

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

    fun strCode(actualName: R_QualifiedName = qualifiedName): String {
        val parts = mutableListOf<String>()

        if (abstract) parts.add("@abstract ")
        if (extension) parts.add("@extension ")
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
        val mArgs = args.toImmList()
        return if (args.isEmpty()) L_Type_Basic(this) else L_Type_Generic(this, mArgs)
    }
}

class L_NamespaceMember_Type(
    qualifiedName: R_QualifiedName,
    val typeDef: L_TypeDef,
    val deprecated: C_Deprecated?,
): L_NamespaceMember(qualifiedName) {
    override fun strCode(): String {
        val parts = listOfNotNull(
            if (deprecated == null) null else L_InternalUtils.deprecatedStrCode(deprecated),
            typeDef.strCode(qualifiedName),
        )
        return parts.joinToString(" ")
    }
}
