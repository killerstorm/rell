/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.ast

import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_StmtContext
import net.postchain.rell.base.compiler.base.modifier.C_ModifierContext
import net.postchain.rell.base.compiler.base.utils.C_ParserFilePath
import net.postchain.rell.base.compiler.base.utils.C_SourcePath
import net.postchain.rell.base.compiler.parser.RellTokenMatch
import net.postchain.rell.base.model.R_FilePos
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.utils.ThreadLocalContext
import net.postchain.rell.base.utils.ide.IdeFilePath
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList
import java.util.*
import java.util.function.Supplier

abstract class S_Pos {
    abstract fun path(): C_SourcePath
    abstract fun idePath(): IdeFilePath
    abstract fun line(): Int
    abstract fun column(): Int

    fun str() = "${path()}(${line()}:${column()})"
    fun strLine() = "${path()}:${line()}"

    fun toFilePos() = R_FilePos(path().str(), line())
    final override fun toString() = str()
}

class S_BasicPos(
    private val file: C_ParserFilePath,
    private val row: Int,
    private val col: Int,
): S_Pos() {
    override fun path() = file.sourcePath
    override fun idePath() = file.idePath
    override fun line() = row
    override fun column() = col

    override fun equals(other: Any?): Boolean {
        return other is S_BasicPos && row == other.row && col == other.col && file == other.file
    }

    override fun hashCode(): Int {
        return Objects.hash(row, col, file)
    }
}

abstract class S_Node {
    val attachment: Any? = getAttachment()

    companion object {
        private val ATTACHMENT_PROVIDER_LOCAL = ThreadLocalContext<Supplier<Any?>>(Supplier { null })

        @JvmStatic
        fun runWithAttachmentProvider(provider: Supplier<Any?>, code: Runnable) {
            ATTACHMENT_PROVIDER_LOCAL.set(provider) {
                code.run()
            }
        }

        private fun getAttachment(): Any? {
            val provider = ATTACHMENT_PROVIDER_LOCAL.get()
            val res = provider.get()
            return res
        }
    }
}

data class S_PosValue<T>(val pos: S_Pos, val value: T) {
    constructor(t: RellTokenMatch, value: T): this(t.pos, value)

    override fun toString() = value.toString()
}

data class S_NameOptValue<T>(val name: S_Name?, val value: T)

class S_Name(val pos: S_Pos, private val rName: R_Name): S_Node() {
    private val str = rName.str

    fun compile(ctx: C_SymbolContext): C_NameHandle {
        return ctx.addName(this, rName)
    }

    fun compile(ctx: C_ModifierContext) = compile(ctx.symCtx)
    fun compile(ctx: C_MountContext) = compile(ctx.symCtx)
    fun compile(ctx: C_NamespaceContext) = compile(ctx.symCtx)
    fun compile(ctx: C_DefinitionContext) = compile(ctx.symCtx)
    fun compile(ctx: C_ExprContext) = compile(ctx.symCtx)

    fun compile(ctx: C_SymbolContext, ideInfo: IdeSymbolInfo): C_Name {
        val hand = ctx.addName(this, rName)
        hand.setIdeInfo(ideInfo)
        return hand.name
    }

    fun compile(ctx: C_NamespaceContext, ideInfo: IdeSymbolInfo) = compile(ctx.symCtx, ideInfo)
    fun compile(ctx: C_MountContext, ideInfo: IdeSymbolInfo) = compile(ctx.nsCtx.symCtx, ideInfo)
    fun compile(ctx: C_DefinitionContext, ideInfo: IdeSymbolInfo) = compile(ctx.symCtx, ideInfo)
    fun compile(ctx: C_StmtContext, ideInfo: IdeSymbolInfo) = compile(ctx.symCtx, ideInfo)
    fun compile(ctx: C_ExprContext, ideInfo: IdeSymbolInfo) = compile(ctx.symCtx, ideInfo)

    fun getRNameSpecial(): R_Name {
        // This method shall be called only in special cases. Whenever possible, one of compile(...) methods must be
        // used in order to add the name to the context and attach IDE meta-information to the name.
        return rName
    }

    override fun toString() = str
}

class S_QualifiedName(parts: List<S_Name>): S_Node() {
    val parts = parts.toImmList()

    init {
        check(this.parts.isNotEmpty())
    }

    val pos = this.parts.first().pos
    val last = this.parts.last()

    constructor(name: S_Name): this(immListOf(name))

    fun add(name: S_Name) = S_QualifiedName(parts + name)

    fun str() = parts.joinToString(".")
    override fun toString() = str()

    fun compile(ctx: C_SymbolContext) = C_QualifiedNameHandle(parts.map { it.compile(ctx) })
    fun compile(ctx: C_ExprContext) = compile(ctx.symCtx)
    fun compile(ctx: C_MountContext) = compile(ctx.symCtx)
    fun compile(ctx: C_NamespaceContext) = compile(ctx.symCtx)
    fun compile(ctx: C_DefinitionContext) = compile(ctx.symCtx)

    fun compile(ctx: C_SymbolContext, ideInfo: IdeSymbolInfo) = C_QualifiedName(parts.map { it.compile(ctx, ideInfo) })
}
