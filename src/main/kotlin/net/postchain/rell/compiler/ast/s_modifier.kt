/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_MountContext
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.modifier.*
import net.postchain.rell.compiler.parser.S_Keywords

sealed class S_Modifier(val pos: S_Pos) {
    abstract fun compile(ctx: C_ModifierContext, modValues: C_FixedModifierValues)
    open fun ideIsTestFile(): Boolean = false
}

class S_KeywordModifier(private val kw: C_Name, private val kind: S_KeywordModifierKind): S_Modifier(kw.pos) {
    override fun compile(ctx: C_ModifierContext, modValues: C_FixedModifierValues) {
        modValues.compileKeyword(ctx, kw, kind)
    }
}

enum class S_KeywordModifierKind(val kw: String) {
    ABSTRACT(S_Keywords.ABSTRACT),
    OVERRIDE(S_Keywords.OVERRIDE),
}

sealed class S_AnnotationArg {
    abstract fun compile(): C_AnnotationArg
}

class S_AnnotationArg_Value(val expr: S_LiteralExpr): S_AnnotationArg() {
    override fun compile(): C_AnnotationArg {
        val value = expr.value()
        return C_AnnotationArg_Value(expr.startPos, value)
    }
}

class S_AnnotationArg_Name(val name: S_QualifiedName): S_AnnotationArg() {
    override fun compile(): C_AnnotationArg {
        return C_AnnotationArg_Name(name)
    }
}

class S_Annotation(val name: S_Name, val args: List<S_AnnotationArg>): S_Modifier(name.pos) {
    override fun compile(ctx: C_ModifierContext, modValues: C_FixedModifierValues) {
        val cArgs = args.map { it.compile() }
        modValues.compileAnnotation(ctx, name, cArgs)
    }

    override fun ideIsTestFile(): Boolean {
        val rName = name.getRNameSpecial()
        return rName.str == C_Annotations.TEST
    }
}

class S_Modifiers(val modifiers: List<S_Modifier>) {
    val pos = modifiers.firstOrNull()?.pos

    fun compile(modifierCtx: C_ModifierContext, modValues: C_ModifierValues) {
        val fixModValues = modValues.fix()
        for (modifier in modifiers) {
            modifier.compile(modifierCtx, fixModValues)
        }
    }

    fun compile(ctx: C_MountContext, modValues: C_ModifierValues) {
        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.nsCtx.symCtx)
        compile(modifierCtx, modValues)
    }
}
