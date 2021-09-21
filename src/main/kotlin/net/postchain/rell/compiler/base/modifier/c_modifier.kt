/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.modifier

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.R_Name
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.*
import java.util.*

class C_ModifierContext(val msgCtx: C_MessageContext)

enum class C_ModifierTargetType {
    MODULE(C_DeclarationType.MODULE),
    NAMESPACE(C_DeclarationType.NAMESPACE),
    ENTITY(C_DeclarationType.ENTITY),
    STRUCT(C_DeclarationType.STRUCT),
    ENUM(C_DeclarationType.ENUM),
    OBJECT(C_DeclarationType.OBJECT),
    FUNCTION(C_DeclarationType.FUNCTION),
    OPERATION(C_DeclarationType.OPERATION),
    QUERY(C_DeclarationType.QUERY),
    IMPORT(C_DeclarationType.IMPORT),
    CONSTANT(C_DeclarationType.CONSTANT),
    EXPRESSION("expression"),
    ;

    val description: String

    constructor(declarationType: C_DeclarationType) {
        this.description = declarationType.msg
    }

    constructor(description: String) {
        this.description = description
    }
}

class C_ModifierTarget(
        val type: C_ModifierTargetType,
        val name: S_Name?
)

sealed class C_AnnotationArg(val pos: S_Pos) {
    abstract fun value(ctx: C_ModifierContext): Rt_Value?
    abstract fun name(ctx: C_ModifierContext): S_QualifiedName?
}

class C_AnnotationArg_Value(pos: S_Pos, private val value: Rt_Value): C_AnnotationArg(pos) {
    override fun value(ctx: C_ModifierContext) = value

    override fun name(ctx: C_ModifierContext): S_QualifiedName? {
        ctx.msgCtx.error(pos, "ann:arg:value_not_name:${value.strCode()}", "Name expected")
        return null
    }
}

class C_AnnotationArg_Name(private val name: S_QualifiedName): C_AnnotationArg(name.pos) {
    override fun value(ctx: C_ModifierContext): Rt_Value? {
        val nameStr = name.str()
        ctx.msgCtx.error(pos, "ann:arg:name_not_value:$nameStr", "Value expected")
        return null
    }

    override fun name(ctx: C_ModifierContext) = name
}

sealed class C_ModifierKey {
    abstract fun codeMsg(): C_CodeMsg
}

class C_ModifierKey_Keyword private constructor(val kind: S_KeywordModifierKind): C_ModifierKey() {
    override fun codeMsg() = "kw:${kind.kw}" toCodeMsg "modifier '${kind.kw}'"

    override fun equals(other: Any?) = this === other || (other is C_ModifierKey_Keyword && kind == other.kind)
    override fun hashCode() = Objects.hash(kind)
    override fun toString() = kind.kw

    companion object {
        fun of(kind: S_KeywordModifierKind): C_ModifierKey = C_ModifierKey_Keyword(kind)
    }
}

class C_ModifierKey_Annotation private constructor(val name: R_Name): C_ModifierKey() {
    override fun codeMsg() = "ann:$name" toCodeMsg "annotation '@$name'"

    override fun equals(other: Any?) = this === other || (other is C_ModifierKey_Annotation && name == other.name)
    override fun hashCode() = Objects.hash(name)
    override fun toString() = "@$name"

    companion object {
        fun of(name: String): C_ModifierKey = C_ModifierKey_Annotation(R_Name.of(name))
        fun of(rName: R_Name): C_ModifierKey = C_ModifierKey_Annotation(rName)
    }
}

abstract class C_ModifierEvaluator<T: Any> {
    /** null result means error, so nullable values aren't supported */
    abstract fun evaluate(ctx: C_ModifierContext, modLink: C_ModifierLink, args: List<C_AnnotationArg>): T?
}

private class C_ModifierEvaluator_Const<T: Any> private constructor(private val value: T): C_ModifierEvaluator<T>() {
    override fun evaluate(ctx: C_ModifierContext, modLink: C_ModifierLink, args: List<C_AnnotationArg>): T {
        C_AnnUtils.checkArgsNone(ctx, modLink.name, args)
        return value
    }

    companion object {
        fun <T: Any> of(value: T): C_ModifierEvaluator<T> = C_ModifierEvaluator_Const(value)
    }
}

class C_Modifier<T: Any>(val key: C_ModifierKey, val evaluator: C_ModifierEvaluator<T>)

private class C_ModifierValueEntry<T: Any>(private val mod: C_Modifier<T>, private val value: C_ModifierValue_Impl<T>) {
    fun compile(ctx: C_ModifierContext, modLink: C_ModifierLink, args: List<C_AnnotationArg>) {
        value.compile(ctx, modLink, mod, args)
    }
}

class C_ModifierValues(
        type: C_ModifierTargetType,
        name: S_Name?
) {
    private val target = C_ModifierTarget(type, name)
    private val fields = mutableSetOf<C_ModifierField<*>>()
    private val mods = mutableMapOf<C_ModifierKey, C_ModifierValueEntry<*>>()
    private var fixed = false

    fun <T: Any> field(f: C_ModifierField<T>): C_ModifierValue<T> {
        check(!fixed)
        check(f !in fields) { f }
        val v = C_ModifierValue_Impl<T>()
        for (mod in f.mods) {
            check(mod.key !in mods) { mod.key }
            mods[mod.key] = C_ModifierValueEntry(mod, v)
        }
        fields.add(f)
        return v
    }

    fun fix(): C_FixedModifierValues {
        check(!fixed)
        fixed = true
        return C_FixedModifierValues_Impl(target, mods)
    }
}

sealed class C_FixedModifierValues {
    abstract fun compileKeyword(ctx: C_ModifierContext, kw: S_Name, kind: S_KeywordModifierKind)
    abstract fun compileAnnotation(ctx: C_ModifierContext, name: S_Name, args: List<C_AnnotationArg>)
}

private class C_FixedModifierValues_Impl(
        private val target: C_ModifierTarget,
        mods: Map<C_ModifierKey, C_ModifierValueEntry<*>>
): C_FixedModifierValues() {
    private val mods = mods.toImmMap()

    override fun compileKeyword(ctx: C_ModifierContext, kw: S_Name, kind: S_KeywordModifierKind) {
        val key = C_ModifierKey_Keyword.of(kind)
        val link = C_ModifierLink(key, kw, target)
        compile0(ctx, link, immListOf())
    }

    override fun compileAnnotation(ctx: C_ModifierContext, name: S_Name, args: List<C_AnnotationArg>) {
        val key = C_ModifierKey_Annotation.of(name.rName)
        val link = C_ModifierLink(key, name, target)
        compile0(ctx, link, args)
    }

    private fun compile0(ctx: C_ModifierContext, link: C_ModifierLink, args: List<C_AnnotationArg>) {
        val entry = mods[link.key]
        if (entry == null) {
            val codeMsg = link.key.codeMsg()
            val code = "modifier:invalid:${codeMsg.code}"
            val msg = "${codeMsg.msg.capitalize()} is invalid"
            ctx.msgCtx.error(link.pos, code, msg)
        } else {
            entry.compile(ctx, link, args)
        }
    }
}

class C_ModifierField<T: Any>(mods: List<C_Modifier<T>>) {
    val mods = mods.toImmList()

    init {
        val modKeys = this.mods.map { it.key }.toImmSet()
        checkEquals(modKeys.size, this.mods.size)
    }

    companion object {
        private val VOID_EVALUATOR = C_ModifierEvaluator_Const.of(Unit)

        fun flagKeyword(kind: S_KeywordModifierKind): C_ModifierField<Unit> {
            return flag(C_ModifierKey_Keyword.of(kind))
        }

        fun flagAnnotation(name: String): C_ModifierField<Unit> {
            return flag(C_ModifierKey_Annotation.of(name))
        }

        private fun flag(modKey: C_ModifierKey): C_ModifierField<Unit> {
            val mod = C_Modifier(modKey, VOID_EVALUATOR)
            return C_ModifierField(immListOf(mod))
        }

        fun <T: Any> valueAnnotation(name: String, evaluator: C_ModifierEvaluator<T>): C_ModifierField<T> {
            val modKey = C_ModifierKey_Annotation.of(name)
            val mod = C_Modifier(modKey, evaluator)
            return C_ModifierField(immListOf(mod))
        }

        fun <T: Any> choiceAnnotations(anns: Map<String, T>): C_ModifierField<T> {
            val mods = anns.map {
                val modKey = C_ModifierKey_Annotation.of(it.key)
                val evaluator = C_ModifierEvaluator_Const.of(it.value)
                C_Modifier(modKey, evaluator)
            }
            return C_ModifierField(mods)
        }
    }
}

class C_ModifierLink(val key: C_ModifierKey, val name: S_Name, val target: C_ModifierTarget) {
    val pos = name.pos
}

sealed class C_ModifierValue<T: Any> {
    abstract fun hasValue(): Boolean
    abstract fun value(): T?
    abstract fun pos(): S_Pos?
    abstract fun posValue(): S_PosValue<T>?
    abstract fun modLink(): C_ModifierLink?
}

private class C_ModifierValue_Impl<T: Any>: C_ModifierValue<T>() {
    private var value: InnerValue? = null

    override fun hasValue(): Boolean = value != null
    override fun value(): T? = value?.value
    override fun pos(): S_Pos? = value?.link?.pos
    override fun modLink(): C_ModifierLink? = value?.link

    override fun posValue(): S_PosValue<T>? {
        val v = value
        return if (v?.value == null) null else S_PosValue(v.link.pos, v.value)
    }

    fun compile(ctx: C_ModifierContext, modLink: C_ModifierLink, mod: C_Modifier<T>, args: List<C_AnnotationArg>) {
        val v = mod.evaluator.evaluate(ctx, modLink, args)
        val v0 = value
        if (v0 != null) {
            if (v0.link.key == modLink.key) {
                val codeMsg = modLink.key.codeMsg()
                val code = "modifier:dup:${codeMsg.code}"
                val msg = "${codeMsg.msg.capitalize()} specified multiple times"
                ctx.msgCtx.error(modLink.pos, code, msg)
            } else {
                C_AnnUtils.errBadCombination(ctx.msgCtx, listOf(v0.link, modLink))
            }
        }
        value = InnerValue(modLink, v)
    }

    private inner class InnerValue(val link: C_ModifierLink, val value: T?)
}
