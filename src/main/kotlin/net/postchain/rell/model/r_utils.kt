/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.CommonUtils
import net.postchain.rell.toImmList
import org.apache.commons.lang3.StringUtils
import java.util.*

class R_DefinitionPos(val module: String, val definition: String) {
    override fun toString() = appLevelName(module, definition)

    companion object {
        fun appLevelName(module: String, definition: String) = "$module!$definition"
    }
}

class R_FilePos(val file: String, val line: Int) {
    override fun toString() = "$file:$line"
}

class R_StackPos(val def: R_DefinitionPos, val file: R_FilePos) {
    override fun toString() = "$def($file)"
}

sealed class R_GenericQualifiedName<T: R_GenericQualifiedName<T>>(parts: List<R_Name>): Comparable<T> {
    val parts = parts.toImmList()

    private val str = parts.joinToString(".")

    fun str() = str
    fun isEmpty() = parts.isEmpty()

    fun startsWith(qName: T): Boolean {
        val prefParts = qName.parts
        if (prefParts.size > parts.size) return false
        for (i in prefParts.indices) {
            if (parts[i] != prefParts[i]) return false
        }
        return true
    }

    fun parent(): T {
        check(!parts.isEmpty()) { "Trying to get a parent name of an empty name" }
        return create(parts.subList(0, parts.size - 1))
    }

    fun child(name: R_Name): T {
        return create(parts + name)
    }

    protected abstract fun create(parts: List<R_Name>): T

    final override fun compareTo(other: T): Int {
        return CommonUtils.compareLists(parts, other.parts)
    }

    final override fun toString() = str()
    final override fun equals(other: Any?) = other is R_GenericQualifiedName<*> && javaClass == other.javaClass && parts == other.parts
    final override fun hashCode() = Objects.hash(javaClass, parts)
}

private fun <T: R_GenericQualifiedName<T>> qNameOf0(s: String, empty: T, create: (List<R_Name>) -> T): T {
    val res = qNameOfOpt0(s, empty, create)
    return requireNotNull(res) { s }
}

private fun <T: R_GenericQualifiedName<T>> qNameOfOpt0(s: String, empty: T, create: (List<R_Name>) -> T): T? {
    if (s == "") return empty
    val parts = R_Name.listOfOpt(s)
    return if (parts == null) null else create(parts)
}

class R_QualifiedName(parts: List<R_Name>): R_GenericQualifiedName<R_QualifiedName>(parts) {
    override fun create(parts: List<R_Name>) = R_QualifiedName(parts)

    companion object {
        val EMPTY = R_QualifiedName(listOf())
        fun of(s: String) = qNameOf0(s, EMPTY) { R_QualifiedName(it) }
        fun ofOpt(s: String) = qNameOfOpt0(s, EMPTY) { R_QualifiedName(it) }
    }
}

class R_ModuleName(parts: List<R_Name>): R_GenericQualifiedName<R_ModuleName>(parts) {
    override fun create(parts: List<R_Name>) = R_ModuleName(parts)

    companion object {
        val EMPTY = R_ModuleName(listOf())
        fun of(s: String) = qNameOf0(s, EMPTY) { R_ModuleName(it) }
        fun ofOpt(s: String) = qNameOfOpt0(s, EMPTY) { R_ModuleName(it) }
    }
}

class R_MountName(parts: List<R_Name>): R_GenericQualifiedName<R_MountName>(parts) {
    override fun create(parts: List<R_Name>) = R_MountName(parts)

    companion object {
        val EMPTY = R_MountName(listOf())
        fun of(s: String) = qNameOf0(s, EMPTY) { R_MountName(it) }
        fun ofOpt(s: String) = qNameOfOpt0(s, EMPTY) { R_MountName(it) }
    }
}

class R_Name private constructor(val str: String): Comparable<R_Name> {
    override fun compareTo(other: R_Name) = str.compareTo(other.str)
    override fun toString() = str
    override fun equals(other: Any?) = other is R_Name && str == other.str
    override fun hashCode() = str.hashCode()

    companion object {
        fun isNameStart(c: Char) = Character.isJavaIdentifierStart(c)
        fun isNamePart(c: Char) = Character.isJavaIdentifierPart(c)

        fun isValid(s: String): Boolean {
            if (s.isEmpty()) return false
            if (!isNameStart(s[0])) return false
            for (c in s) {
                if (!isNamePart(c)) return false
            }
            return true
        }

        fun of(s: String): R_Name {
            val res = ofOpt(s)
            return requireNotNull(res) { s }
        }

        fun ofOpt(s: String): R_Name? {
            if (!isValid(s)) {
                return null
            }
            return R_Name(s)
        }

        fun listOfOpt(s: String): List<R_Name>? {
            val parts = StringUtils.splitPreserveAllTokens(s, '.')
            val names = parts.map { ofOpt(it) }
            val names2 = names.filterNotNull()
            if (names2.size != names.size) return null
            return names2.toImmList()
        }
    }
}

object R_Utils {
    val ERROR_APP_UID = R_AppUid(-1)
    val ERROR_CONTAINER_UID = R_ContainerUid(-1, "<error>", ERROR_APP_UID)
    val ERROR_FN_UID = R_FnUid(-1, "<error>", ERROR_CONTAINER_UID)
    val ERROR_BLOCK_UID = R_FrameBlockUid(-1, "<error>", ERROR_FN_UID)
}
