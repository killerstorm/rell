/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.parser.RellTokenMatch
import net.postchain.rell.model.R_FilePos
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.model.R_Name
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.ThreadLocalContext
import java.util.*
import java.util.function.Supplier

abstract class S_Pos {
    abstract fun path(): C_SourcePath
    abstract fun line(): Int
    abstract fun column(): Int
    abstract fun pos(): Long
    abstract fun str(): String
    abstract fun strLine(): String
    override fun toString() = str()

    fun toFilePos() = R_FilePos(path().str(), line())
}

class S_BasicPos(private val file: C_SourcePath, private val row: Int, private val col: Int): S_Pos() {
    override fun path() = file
    override fun line() = row
    override fun column() = col
    override fun pos() = Math.min(row, 1_000_000_000) * 1_000_000_000L + Math.min(col, 1_000_000_000)
    override fun str() = "$file($row:$col)"
    override fun strLine() = "$file:$row"

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

data class S_NameValue<T>(val name: S_Name, val value: T)
data class S_NameOptValue<T>(val name: S_Name?, val value: T)

class S_Name(val pos: S_Pos, val str: String): S_Node() {
    val rName = R_Name.of(str)
    override fun toString() = str
}

class S_String(val pos: S_Pos, val str: String): S_Node() {
    constructor(name: S_Name): this(name.pos, name.str)
    constructor(t: RellTokenMatch): this(t.pos, t.text)
    override fun toString() = str
}

sealed class S_Modifier(val pos: S_Pos) {
    abstract fun compile(ctx: C_ModifierContext, target: C_ModifierTarget)
    open fun ideIsTestFile(): Boolean = false
}

sealed class S_KeywordModifier(protected val kw: S_String): S_Modifier(kw.pos)

class S_KeywordModifier_Abstract(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.abstract, true)
        target.checkAbstractTest(ctx.msgCtx, kw.pos, target.test)
    }
}

class S_KeywordModifier_Override(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.override, true)
    }
}

class S_Annotation(val name: S_Name, val args: List<S_LiteralExpr>): S_Modifier(name.pos) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        val argValues = args.map { it.value() }
        C_Modifier.compileAnnotation(ctx, name, argValues, target)
    }

    override fun ideIsTestFile() = name.str == C_Modifier.TEST
}

class S_Modifiers(val modifiers: List<S_Modifier>) {
    val pos = modifiers.firstOrNull()?.pos

    fun compile(modifierCtx: C_ModifierContext, target: C_ModifierTarget) {
        for (modifier in modifiers) {
            modifier.compile(modifierCtx, target)
        }
    }

    fun compile(ctx: C_MountContext, target: C_ModifierTarget) {
        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.appCtx.valExec)
        compile(modifierCtx, target)
    }
}

class S_ModuleHeader(val modifiers: S_Modifiers, val pos: S_Pos) {
    fun compile(ctx: C_ModifierContext): C_MidModuleHeader {
        val modTarget = C_ModifierTarget(
                C_ModifierTargetType.MODULE,
                null,
                abstract = true,
                externalModule = true,
                mount = true,
                emptyMountAllowed = true,
                test = true
        )

        modifiers.compile(ctx, modTarget)

        val abstract = modTarget.abstract?.get() ?: false
        val abstractPos = if (abstract) pos else null

        val external = modTarget.externalModule?.get() ?: false
        val test = modTarget.test?.get() ?: false

        return C_MidModuleHeader(pos, modTarget.mount?.get(), abstractPos, external, test)
    }

    fun ideIsTestFile(): Boolean {
        return modifiers.modifiers.any { it.ideIsTestFile() }
    }
}

class S_RellFile(val header: S_ModuleHeader?, val definitions: List<S_Definition>): S_Node() {
    val startPos = header?.pos ?: definitions.firstOrNull()?.startPos

    fun compileHeader(modifierCtx: C_ModifierContext): C_MidModuleHeader? {
        return header?.compile(modifierCtx)
    }

    fun compile(ctx: C_ModuleSourceContext, path: C_SourcePath): C_MidModuleFile {
        val members = definitions.mapNotNull { it.compile(ctx) }
        return C_MidModuleFile(path, members, startPos)
    }

    fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.ideGetImportedModules(moduleName, res)
        }
    }

    fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (def in definitions) {
            def.ideBuildOutlineTree(b)
        }
    }

    fun ideIsTestFile(): Boolean {
        return header != null && header.ideIsTestFile()
    }

    companion object {
        fun createMountContext(fileCtx: C_FileContext, mountName: R_MountName, nsAssembler: C_NsAsm_ComponentAssembler): C_MountContext {
            val modCtx = fileCtx.modCtx
            val nsBuilder = C_UserNsProtoBuilder(nsAssembler)
            val fileScopeBuilder = modCtx.scopeBuilder.nested(nsAssembler.futureNs())
            val nsCtx = C_NamespaceContext(modCtx, null, fileScopeBuilder)
            return C_MountContext(fileCtx, nsCtx, modCtx.extChain, nsBuilder, mountName)
        }
    }
}
