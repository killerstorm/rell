/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.utils.C_RNamePath
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.model.R_IdeName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_QualifiedName
import net.postchain.rell.tools.api.*
import net.postchain.rell.utils.*

data class C_NameValue<T>(val name: C_Name, val value: T)

class C_Name private constructor(val pos: S_Pos, val rName: R_Name) {
    val str = rName.str

    override fun toString() = str

    companion object {
        fun make(pos: S_Pos, str: String): C_Name = C_Name(pos, R_Name.of(str))
        fun make(pos: S_Pos, rName: R_Name): C_Name = C_Name(pos, rName)
    }
}

class C_QualifiedName(parts: List<C_Name>) {
    val parts = parts.toImmList()

    init {
        check(this.parts.isNotEmpty())
    }

    val pos = this.parts.first().pos
    val last = this.parts.last()

    constructor(name: C_Name): this(immListOf(name))

    fun add(name: C_Name) = C_QualifiedName(parts + name)
    fun parentPath() = parts.dropLast(1).toImmList()
    fun toRName() = R_QualifiedName(parts.map { it.rName })
    fun toPath() = C_RNamePath.of(parts.map { it.rName })

    fun str() = parts.joinToString(".")
    override fun toString() = str()
}

class C_IdeName(val name: C_Name, val ideInfo: IdeSymbolInfo) {
    val pos = name.pos
    val str = name.str

    fun toRName(): R_IdeName = R_IdeName(name.rName, ideInfo)
    override fun toString() = name.toString()
}

class C_IdeQualifiedName(parts: List<C_IdeName>) {
    val parts = parts.toImmList()

    init {
        check(this.parts.isNotEmpty())
    }

    val last = this.parts.last()

    fun toCQualifiedName() = C_QualifiedName(parts.map { it.name })
    fun toPath() = C_RNamePath.of(parts.map { it.name.rName })

    fun str() = parts.joinToString(".") { it.name.str }
    override fun toString() = str()
}

class C_IdeSymbolDef(val defInfo: IdeSymbolInfo, val refInfo: IdeSymbolInfo) {
    companion object {
        fun make(kind: IdeSymbolKind, globalId: IdeSymbolGlobalId?): C_IdeSymbolDef {
            val defInfo = IdeSymbolInfo(kind, defId = globalId?.symId)
            val link = if (globalId == null) null else IdeGlobalSymbolLink(globalId)
            val refInfo = IdeSymbolInfo(kind, link = link)
            return C_IdeSymbolDef(defInfo, refInfo)
        }

        fun make(kind: IdeSymbolKind, pos: S_Pos, id: IdeSymbolId): C_IdeSymbolDef {
            val file = pos.idePath()
            return make(kind, file, id)
        }

        fun make(kind: IdeSymbolKind, file: IdeFilePath, id: IdeSymbolId): C_IdeSymbolDef {
            return make(kind, IdeSymbolGlobalId(file, id))
        }
    }
}

sealed class C_NameHandle(val pos: S_Pos, val rName: R_Name) {
    val str = rName.str
    val name = C_Name.make(pos, rName)

    abstract fun setIdeInfo(info: IdeSymbolInfo)
}

class C_QualifiedNameHandle(parts: List<C_NameHandle>) {
    val parts = parts.let {
        check(it.isNotEmpty())
        it.toImmList()
    }

    val pos = this.parts.first().pos
    val last = this.parts.last()
    val size: Int get() = this.parts.size
    val first: C_NameHandle get() = this.parts.first()
    val qName = C_QualifiedName(this.parts.map { it.name })

    constructor(name: C_NameHandle): this(immListOf(name))

    fun parentParts(): List<C_NameHandle> = parts.subList(0, parts.size - 1)

    fun setIdeInfo(infos: List<IdeSymbolInfo>) {
        checkEquals(infos.size, parts.size)
        infos.forEachIndexed { index, info ->
            parts[index].setIdeInfo(info)
        }
    }

    fun setIdeInfo(info: IdeSymbolInfo) {
        parts.forEach {
            it.setIdeInfo(info)
        }
    }

    fun str(): String = qName.str()

    override fun toString() = str()
}

// Cannot extend a sealed class directly by a nested class, so an intermediate abstract class is needed.
private abstract class C_NameHandleAbstract(pos: S_Pos, rName: R_Name): C_NameHandle(pos, rName)

sealed class C_SymbolContext {
    abstract fun addName(sName: S_Name, rName: R_Name): C_NameHandle
    abstract fun addSymbol(pos: S_Pos, ideInfo: IdeSymbolInfo)
    abstract fun setDefId(pos: S_Pos, defId: IdeSymbolId)
    abstract fun setLink(pos: S_Pos, link: IdeSymbolLink)
}

object C_NopSymbolContext: C_SymbolContext() {
    override fun addName(sName: S_Name, rName: R_Name): C_NameHandle {
        return C_NopNameHandle(sName.pos, rName)
    }

    override fun addSymbol(pos: S_Pos, ideInfo: IdeSymbolInfo) {
        // Do nothing.
    }

    override fun setDefId(pos: S_Pos, defId: IdeSymbolId) {
        // Do nothing.
    }

    override fun setLink(pos: S_Pos, link: IdeSymbolLink) {
        // Do nothing.
    }

    private class C_NopNameHandle(pos: S_Pos, rName: R_Name): C_NameHandleAbstract(pos, rName) {
        override fun setIdeInfo(info: IdeSymbolInfo) {
            // Do nothing.
        }
    }
}

private class C_DefaultSymbolContext(private val checkDefIdConflicts: Boolean): C_SymbolContext() {
    private val nameMap = mutableMapOf<S_Pos, C_NameHandleImpl>()
    private val symbolMap = mutableMapOf<S_Pos, IdeSymbolInfo>()
    private val extraMap = mutableMapOf<S_Pos, ExtraInfo>()

    override fun addName(sName: S_Name, rName: R_Name): C_NameHandle {
        val pos = sName.pos
        if (CommonUtils.IS_UNIT_TEST) {
            check(pos !in symbolMap)
        }

        val oldHand = nameMap[pos]
        if (oldHand != null) {
            oldHand.redefinition()
            return oldHand
        }

        val hand = C_NameHandleImpl(pos, rName)
        nameMap[pos] = hand
        return hand
    }

    override fun addSymbol(pos: S_Pos, ideInfo: IdeSymbolInfo) {
        if (CommonUtils.IS_UNIT_TEST) {
            check(pos !in symbolMap)
            check(pos !in nameMap)
        }
        symbolMap[pos] = ideInfo
    }

    override fun setDefId(pos: S_Pos, defId: IdeSymbolId) {
        val extra = extraMap.computeIfAbsent(pos) { ExtraInfo() }
        check(extra.defId == null || !CommonUtils.IS_UNIT_TEST) { "name not found: $pos" }
        extra.defId = defId
    }

    override fun setLink(pos: S_Pos, link: IdeSymbolLink) {
        val extra = extraMap.computeIfAbsent(pos) { ExtraInfo() }
        check(extra.link == null || !CommonUtils.IS_UNIT_TEST) { "name not found: $pos" }
        extra.link = link
    }

    private fun finish0(): Map<S_Pos, IdeSymbolInfo> {
        finishExtra()

        val res = mutableMapOf<S_Pos, IdeSymbolInfo>()

        res.putAll(symbolMap)

        for (hand in nameMap.values) {
            val ideInfo = hand.ideInfo()
            if (ideInfo != null) {
                if (CommonUtils.IS_UNIT_TEST) {
                    check(hand.pos !in res)
                }
                res[hand.pos] = ideInfo
            }
        }

        val immRes = res.toImmMap()

        if (CommonUtils.IS_UNIT_TEST && checkDefIdConflicts) {
            checkDefIdConflicts(immRes)
        }

        return immRes
    }

    private fun finishExtra() {
        for ((pos, extra) in extraMap) {
            val nameHand = nameMap[pos]
            check(nameHand != null || !CommonUtils.IS_UNIT_TEST) { "name not found: $pos" }
            val defId = extra.defId
            val link = extra.link
            if (defId != null) {
                nameHand?.setDefId(defId)
            }
            if (link != null) {
                nameHand?.setLink(link)
            }
        }
    }

    private fun checkDefIdConflicts(map: Map<S_Pos, IdeSymbolInfo>) {
        val fileMap = mutableMapOf<C_SourcePath, MutableMap<IdeSymbolId, S_Pos>>()
        for ((pos, info) in map) {
            if (info.defId != null) {
                val path = pos.path()
                val subMap = fileMap.computeIfAbsent(path) { mutableMapOf() }
                val oldPos = subMap.put(info.defId, pos)
                check(oldPos == null) { "$oldPos $pos ${info.defId}" }
            }
        }
    }

    private class ExtraInfo {
        var defId: IdeSymbolId? = null
        var link: IdeSymbolLink? = null
    }

    private class C_NameHandleImpl(pos: S_Pos, rName: R_Name): C_NameHandleAbstract(pos, rName) {
        private val initStack = Exception("Stack")

        private var mIdeInfo: IdeSymbolInfo? = null
        private var ideInfoStack: Throwable? = null

        fun redefinition() {
            if (CommonUtils.IS_UNIT_TEST) {
                throw RuntimeException("Name already compiled: $rName (Stack 2)", initStack)
            }
        }

        fun ideInfo(): IdeSymbolInfo? {
            if (mIdeInfo == null && CommonUtils.IS_UNIT_TEST) {
                throw IllegalStateException("No IDE info: $rName", initStack)
            }
            return mIdeInfo
        }

        override fun setIdeInfo(info: IdeSymbolInfo) {
            if (CommonUtils.IS_UNIT_TEST) {
                if (mIdeInfo != null) {
                    throw RuntimeException("Ide info already set: $rName (old ${mIdeInfo!!.kind} new ${info.kind})", ideInfoStack)
                }
                ideInfoStack = Exception("Stack 1")
            }
            if (mIdeInfo == null) {
                mIdeInfo = info
            }
        }

        fun setDefId(defId: IdeSymbolId) {
            val ideInfo = mIdeInfo
            when {
                ideInfo == null -> check(!CommonUtils.IS_UNIT_TEST)
                ideInfo.defId != null -> check(!CommonUtils.IS_UNIT_TEST)
                else -> mIdeInfo = ideInfo.update(defId = defId)
            }
        }

        fun setLink(link: IdeSymbolLink) {
            val ideInfo = mIdeInfo
            when {
                ideInfo == null -> check(!CommonUtils.IS_UNIT_TEST)
                ideInfo.link != null -> check(!CommonUtils.IS_UNIT_TEST)
                else -> mIdeInfo = ideInfo.update(link = link)
            }
        }
    }

    fun finish(): Map<S_Pos, IdeSymbolInfo> {
        return finish0()
    }
}

interface C_SymbolContextProvider {
    fun getSymbolContext(path: C_SourcePath): C_SymbolContext
}

class C_SymbolContextManager(private val opts: C_CompilerOptions) {
    private val mainFile = opts.symbolInfoFile

    val provider: C_SymbolContextProvider = C_SymbolContextProviderImpl()

    private val mainSymCtx = C_DefaultSymbolContext(opts.ideDefIdConflictError)

    fun finish(): Map<S_Pos, IdeSymbolInfo> {
        val res = mainSymCtx.finish()
        return res
    }

    private inner class C_SymbolContextProviderImpl: C_SymbolContextProvider {
        override fun getSymbolContext(path: C_SourcePath): C_SymbolContext {
            return if (path == mainFile) mainSymCtx else C_NopSymbolContext
        }
    }
}
