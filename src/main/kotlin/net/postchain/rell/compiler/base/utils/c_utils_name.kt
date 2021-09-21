/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.utils

import net.postchain.rell.compiler.ast.S_QualifiedName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_QualifiedName
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

abstract class C_GenericQualifiedName<NameT, FullNameT: C_GenericQualifiedName<NameT, FullNameT>>
protected constructor(parts: List<NameT>) {
    val parts = parts.toImmList()

    init {
        check(this.parts.isNotEmpty())
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

class C_RawQualifiedName private constructor(parts: List<String>): C_GenericQualifiedName<String, C_RawQualifiedName>(parts) {
    override fun create(names: List<String>) = C_RawQualifiedName(names)

    override fun checkName(name: String) {
        require(name.isNotBlank())
    }

    companion object {
        fun of(parts: List<String>): C_RawQualifiedName = ofNames0(parts) { C_RawQualifiedName(it) }
        fun of(name: String): C_RawQualifiedName = ofName0(name) { C_RawQualifiedName(it) }
        fun of(sName: S_QualifiedName): C_RawQualifiedName = of(sName.parts.map { it.str })
    }
}

class C_QualifiedName private constructor(parts: List<R_Name>): C_GenericQualifiedName<R_Name, C_QualifiedName>(parts) {
    fun toRName() = R_QualifiedName(parts)
    fun toCRawName() = C_RawQualifiedName.of(parts.map { it.str })

    override fun create(names: List<R_Name>) = C_QualifiedName(names)

    override fun checkName(name: R_Name) {
        // Nothing to check.
    }

    companion object {
        fun of(parts: List<R_Name>): C_QualifiedName = ofNames0(parts) { C_QualifiedName(it) }
        fun of(sName: S_QualifiedName): C_QualifiedName = of(sName.parts.map { it.rName })
        fun of(name: R_Name): C_QualifiedName = ofName0(name) { C_QualifiedName(it) }
    }
}
