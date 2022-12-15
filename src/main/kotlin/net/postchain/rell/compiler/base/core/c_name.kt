/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.utils.C_RNamePath
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_QualifiedName
import net.postchain.rell.tools.api.IdeSymbolInfo
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
}

object C_NopSymbolContext: C_SymbolContext() {
    override fun addName(sName: S_Name, rName: R_Name): C_NameHandle {
        return C_NopNameHandle(sName.pos, rName)
    }

    override fun addSymbol(pos: S_Pos, ideInfo: IdeSymbolInfo) {
        // Do nothing.
    }

    private class C_NopNameHandle(pos: S_Pos, rName: R_Name): C_NameHandleAbstract(pos, rName) {
        override fun setIdeInfo(info: IdeSymbolInfo) {
            // Do nothing.
        }
    }
}

private class C_DefaultSymbolContext: C_SymbolContext() {
    private val nameMap = mutableMapOf<S_Name, C_NameHandleImpl>()
    private val symbolMap = mutableMapOf<S_Pos, IdeSymbolInfo>()

    override fun addName(sName: S_Name, rName: R_Name): C_NameHandle {
        val oldHand = nameMap[sName]
        if (oldHand != null) {
            oldHand.redefinition()
            return oldHand
        }

        val hand = C_NameHandleImpl(sName.pos, rName)
        nameMap[sName] = hand
        return hand
    }

    override fun addSymbol(pos: S_Pos, ideInfo: IdeSymbolInfo) {
        if (CommonUtils.IS_UNIT_TEST) {
            check(pos !in symbolMap)
        }
        symbolMap[pos] = ideInfo
    }

    protected fun finish0(): Map<S_Pos, IdeSymbolInfo> {
        val res = mutableMapOf<S_Pos, IdeSymbolInfo>()

        res.putAll(symbolMap)

        for (hand in nameMap.values) {
            val ideInfo = hand.ideInfo()
            if (ideInfo != null) {
                res[hand.pos] = ideInfo
            }
        }

        return res.toImmMap()
    }

    private class C_NameHandleImpl(pos: S_Pos, rName: R_Name): C_NameHandleAbstract(pos, rName) {
        val initStack = Exception("Stack")

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
    }

    fun finish(): Map<S_Pos, IdeSymbolInfo> {
        return finish0()
    }
}

interface C_SymbolContextProvider {
    fun getSymbolContext(path: C_SourcePath): C_SymbolContext
}

class C_SymbolContextManager(private val mainFile: C_SourcePath?) {
    val provider: C_SymbolContextProvider = C_SymbolContextProviderImpl()

    private val mainSymCtx = C_DefaultSymbolContext()

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
