/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.utils

import net.postchain.rell.base.compiler.base.core.C_QualifiedName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList

abstract class C_GenericQualifiedName<NameT, FullNameT: C_GenericQualifiedName<NameT, FullNameT>>
protected constructor(parts: List<NameT>) {
    val parts = parts.let {
        check(it.isNotEmpty())
        it.toImmList()
    }

    val last = parts.last()

    fun add(other: FullNameT): FullNameT = create(parts + other.parts)

    fun add(name: NameT): FullNameT {
        checkName(name)
        return create(parts + immListOf(name))
    }

    fun str() = parts.joinToString(".")

    protected abstract fun create(names: List<NameT>): FullNameT
    protected abstract fun checkName(name: NameT)

    final override fun equals(other: Any?): Boolean {
        return this === other || (other is C_GenericQualifiedName<*, *> && other.javaClass == javaClass && parts == other.parts)
    }

    final override fun hashCode() = parts.hashCode()

    final override fun toString() = str()

    protected companion object {
        fun <NameT, FullNameT: C_GenericQualifiedName<NameT, FullNameT>> ofNames0(
                parts: List<NameT>,
                ctor: (List<NameT>) -> FullNameT
        ): FullNameT {
            val copy = parts.toImmList()
            require(copy.isNotEmpty())
            val res = ctor(copy)
            for (part in parts) {
                res.checkName(part)
            }
            return res
        }

        fun <NameT, FullNameT: C_GenericQualifiedName<NameT, FullNameT>> ofName0(
                name: NameT,
                ctor: (List<NameT>) -> FullNameT
        ): FullNameT {
            val parts = immListOf(name)
            val res = ctor(parts)
            res.checkName(name)
            return res
        }
    }
}

class C_StringQualifiedName private constructor(parts: List<String>): C_GenericQualifiedName<String, C_StringQualifiedName>(parts) {
    override fun create(names: List<String>) = C_StringQualifiedName(names)

    override fun checkName(name: String) {
        require(name.isNotBlank())
    }

    companion object {
        fun of(parts: List<String>): C_StringQualifiedName = ofNames0(parts) { C_StringQualifiedName(it) }
        fun of(name: String): C_StringQualifiedName = ofName0(name) { C_StringQualifiedName(it) }
        fun of(cName: C_QualifiedName): C_StringQualifiedName = of(cName.parts.map { it.str })
        fun of(parent: List<R_Name>, name: String): C_StringQualifiedName = of(parent.map { it.str } + listOf(name))
        fun of(parent: R_QualifiedName, name: R_Name): C_StringQualifiedName = of(parent.parts, name.str)

        fun ofRNames(parts: List<R_Name>): C_StringQualifiedName = of(parts.map { it.str })
    }
}

class C_RQualifiedName private constructor(parts: List<R_Name>): C_GenericQualifiedName<R_Name, C_RQualifiedName>(parts) {
    override fun create(names: List<R_Name>) = C_RQualifiedName(names)

    override fun checkName(name: R_Name) {
        // Nothing to check.
    }

    companion object {
        fun of(parts: List<R_Name>): C_RQualifiedName = ofNames0(parts) { C_RQualifiedName(it) }
        fun of(name: R_Name): C_RQualifiedName = ofName0(name) { C_RQualifiedName(it) }
    }
}

class C_RNamePath private constructor(parts: List<R_Name>) {
    val parts = parts.toImmList()

    fun child(name: R_Name) = C_RNamePath(parts + name)
    fun child(names: List<R_Name>) = of(parts + names)
    fun fullName(name: R_Name): R_QualifiedName = R_QualifiedName(parts + name)

    override fun equals(other: Any?) = other is C_RNamePath && parts == other.parts
    override fun hashCode() = parts.hashCode()
    override fun toString() = parts.joinToString(".")

    companion object {
        val EMPTY = C_RNamePath(immListOf())
        fun of(parts: List<R_Name>): C_RNamePath = if (parts.isEmpty()) EMPTY else C_RNamePath(parts)
    }
}