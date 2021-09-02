/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.ast.S_String
import net.postchain.rell.model.R_AtWhatSort
import net.postchain.rell.model.R_MountName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_TextType
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmMap
import org.apache.commons.lang3.StringUtils

class C_ModifierContext(val msgCtx: C_MessageContext, private val valExec: C_ValidationExecutor) {
    fun onValidation(code: () -> Unit) = valExec.onValidation(code)
}

object C_Modifier {
    const val EXTERNAL = "external"

    const val SORT = "sort"
    const val SORT_DESC = "sort_desc"
    const val TEST = "test"

    private val ANNOTATIONS: Map<String, C_AnnBase> = let {
        val anns = mutableMapOf(
                EXTERNAL to C_Annotation_External,
                "log" to C_Annotation_Log,
                "mount" to C_Annotation_Mount,
                "omit" to C_Annotation_Omit,
                SORT to C_Annotation_Sort(R_AtWhatSort.ASC),
                SORT_DESC to C_Annotation_Sort(R_AtWhatSort.DESC),
                TEST to C_Annotation_Test
        )

        for (s in C_AtSummarizationKind.values()) {
            check(s.annotation !in anns)
            anns[s.annotation] = C_Annotation_Summarization(s)
        }

        anns.toImmMap()
    }

    fun compileAnnotation(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        val ann = ANNOTATIONS[name.str]
        if (ann != null) {
            ann.compile(ctx, name, args, target)
        } else {
            ctx.msgCtx.error(name.pos, "ann:invalid:${name.str}", "Invalid annotation: '${name.str}'")
        }
    }

    fun <T> compileModifier(
            ctx: C_ModifierContext,
            kw: S_String,
            target: C_ModifierTarget,
            field: C_ModifierValue<T>?,
            value: T
    ) {
        val name = kw.str
        if (field == null) {
            ctx.msgCtx.error(kw.pos, "modifier:target_type:$name:${target.type}",
                    "Cannot specify '$name' for ${target.type.description}")
        } else if (!field.set(value)) {
            ctx.msgCtx.error(kw.pos, "modifier:dup:$name", "Modifier '$name' specified multiple times")
        }
    }
}

class C_MountPath private constructor(
        private val str: String,
        private val up: Int?,
        private val path: List<R_Name>,
        private val tail: Boolean
) {
    fun apply(
            msgCtx: C_MessageContext,
            target: C_MountAnnotationTarget,
            parentMountName: R_MountName
    ): R_MountName? {
        var base = listOf<R_Name>()
        if (up != null) {
            if (up > parentMountName.parts.size) {
                msgCtx.error(target.pos, "ann:mount:up:${parentMountName.parts.size}:$up",
                        "Cannot go up by $up on current mount path '$parentMountName'")
                return null
            }
            base = parentMountName.parts.subList(0, parentMountName.parts.size - up)
        }

        if (!target.emptyMountAllowed && up != null && path.isEmpty() && !tail) {
            msgCtx.error(target.pos, "ann:mount:invalid:$str:${target.type}",
                    "Mount path '$str' is invalid for ${target.type.description}")
            return null
        }

        val combined = base + path
        if (!tail) {
            return R_MountName(combined)
        }

        if (target.name == null) {
            msgCtx.error(target.pos, "ann:mount:tail:no_name:$str:${target.type}",
                    "Mount path '$str' is invalid for ${target.type.description}")
            return null
        }

        val full = combined + target.name
        return R_MountName(full)
    }

    override fun toString() = str

    companion object {
        fun parse(s: String): C_MountPath? {
            var parts = StringUtils.splitPreserveAllTokens(s, '.').toList()
            if (parts.isEmpty()) return C_MountPath(s, null, listOf(), false)

            var up: Int? = null
            if (parts[0] == "") {
                up = 0
                parts = parts.subList(1, parts.size)
            } else if (parts[0].matches(Regex("\\^+"))) {
                up = parts[0].length
                parts = parts.subList(1, parts.size)
            }

            var tail = false
            if (parts.isNotEmpty() && parts[parts.size - 1] == "") {
                tail = true
                parts = parts.subList(0, parts.size - 1)
            }

            val path = mutableListOf<R_Name>()
            for (part in parts) {
                val rName = R_Name.ofOpt(part)
                if (rName == null) return null
                path.add(rName)
            }

            if (up != null && up == 0 && path.isEmpty()) {
                return null
            }

            return C_MountPath(s, up, path, tail)
        }
    }
}

class C_MountAnnotationTarget(
        val pos: S_Pos,
        val type: C_ModifierTargetType,
        val name: R_Name?,
        val emptyMountAllowed: Boolean
)

class C_MountAnnotation(
        private val target: C_MountAnnotationTarget,
        private val path: C_MountPath
) {
    fun calculateMountName(msgCtx: C_MessageContext, parentMountName: R_MountName): R_MountName {
        val mountName = path.apply(msgCtx, target, parentMountName)
        mountName ?: return parentMountName

        if (!target.emptyMountAllowed && mountName.isEmpty()) {
            msgCtx.error(target.pos, "ann:mount:empty:${target.type}",
                    "Cannot use empty mount name for ${target.type.description}")
            return parentMountName
        }

        return mountName
    }
}

private object C_Annotation_Mount: C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        val pos = name.pos
        val mountPath = processArgs(ctx, pos, args)
        if (mountPath == null) {
            return
        }

        val mountTarget = C_MountAnnotationTarget(name.pos, target.type, target.name?.rName, target.emptyMountAllowed)
        val mountAnn = C_MountAnnotation(mountTarget, mountPath)
        C_AnnUtils.processAnnotation(ctx, pos, target, name.str, target.mount, target.mountAllowed, mountAnn)

        if (target.type == C_ModifierTargetType.MODULE) {
            ctx.onValidation {
                if (target.test?.get() == true) {
                    ctx.msgCtx.error(pos, "ann:mount:test_module", "Mount name not allowed for a test module")
                }
            }
        }
    }

    private fun processArgs(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): C_MountPath? {
        val str = C_AnnUtils.processArgsString(ctx, pos, args)
        if (str == null) {
            return null
        }

        val res = C_MountPath.parse(str)
        if (res == null) {
            ctx.msgCtx.error(pos, "ann:mount:invalid:$str", "Invalid mount name: '$str'")
        }
        return res
    }
}

private sealed class C_AnnBase {
    abstract fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget)
}

private object C_Annotation_Log: C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.log, target.logAllowed, true)
        }
    }
}

private object C_Annotation_External: C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        val pos = name.pos

        if (args.size == 0) {
            compileNoArgs(ctx, pos, target)
            return
        }

        val chain = processArgs(ctx, pos, args)
        if (chain == null) {
            return
        }

        val value = C_ExternalAnnotation(pos, chain)
        val code = "external:unary"
        val msg = "${C_Modifier.EXTERNAL} with argument"
        C_AnnUtils.processAnnotation(ctx, pos, target, msg, target.externalChain, true, value, nameCode = code)
    }

    private fun compileNoArgs(ctx: C_ModifierContext, pos: S_Pos, target: C_ModifierTarget) {
        val code = "external:nullary"
        val name = "${C_Modifier.EXTERNAL} without argument"
        C_AnnUtils.processAnnotation(ctx, pos, target, name, target.externalModule, true, true, nameCode = code)
    }

    private fun processArgs(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): String? {
        val str = C_AnnUtils.processArgsString(ctx, pos, args)
        if (str != null && str.isEmpty()) {
            ctx.msgCtx.error(pos, "ann:external:invalid:$str", "Invalid external chain name: '$str'")
            return null
        }
        return str
    }
}

private object C_Annotation_Omit: C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.omit, allowed = true, value = true)
        }
    }
}

private class C_Annotation_Sort(private val sort: R_AtWhatSort): C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            val posValue = S_PosValue(name.pos, sort)
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.sort, allowed = true, value = posValue, generalName = "Sorting")
        }
    }
}

private object C_Annotation_Test: C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.test, allowed = true, value = true)
            target.checkAbstractTest(ctx.msgCtx, name.pos, target.abstract)
        }
    }
}

private class C_Annotation_Summarization(val value: C_AtSummarizationKind): C_AnnBase() {
    override fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            val posValue = S_PosValue(name.pos, value)
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.summarization, allowed = true, value = posValue)
        }
    }
}

private object C_AnnUtils {
    fun checkNoArgs(ctx: C_ModifierContext, pos: S_Pos, name: String, args: List<Rt_Value>): Boolean {
        if (args.isNotEmpty()) {
            ctx.msgCtx.error(pos, "ann:$name:args:${args.size}", "Annotation @$name takes no arguments")
            return false
        }
        return true
    }

    fun processArgsString(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): String? {
        val expectedArgs = 1
        if (args.size != expectedArgs) {
            ctx.msgCtx.error(pos, "ann:mount:arg_count:${args.size}",
                    "Wrong number of arguments (expected $expectedArgs)")
            return null
        }

        val arg = args[0]
        val type = arg.type()
        if (type != R_TextType) {
            ctx.msgCtx.error(pos, "ann:mount:arg_type:${type.toStrictString()}",
                    "Wrong argument type: $type instead of $R_TextType")
            return null
        }

        val str = arg.asString()
        return str
    }

    fun <T> processAnnotation(
            ctx: C_ModifierContext,
            pos: S_Pos,
            target: C_ModifierTarget,
            name: String,
            field: C_ModifierValue<T>?,
            allowed: Boolean,
            value: T,
            nameCode: String = name,
            generalName: String? = null
    ) {
        if (field == null) {
            ctx.msgCtx.error(pos, "ann:$nameCode:target_type:${target.type}",
                    "Annotation @$name cannot be used for ${target.type.description}")
        } else if (!allowed) {
            var msg = "Annotation @$name not allowed for ${target.type.description}"
            if (target.name != null) msg += " '${target.name.str}'"
            ctx.msgCtx.error(pos, "ann:$nameCode:not_allowed:${target.type}:${target.name}", msg)
        } else if (!field.set(value)) {
            val errName = generalName ?: "Annotation @$name"
            ctx.msgCtx.error(pos, "ann:$nameCode:dup", "$errName specified multiple times")
        }
    }
}

class C_ExternalAnnotation(val pos: S_Pos, val chain: String)

enum class C_AtSummarizationKind(val annotation: String) {
    GROUP("group"),
    SUM("sum"),
    MIN("min"),
    MAX("max"),
}

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

    fun isExpression() = this == EXPRESSION
}

class C_ModifierTarget(
        val type: C_ModifierTargetType,
        val name: S_Name?,
        abstract: Boolean = false,
        externalChain: Boolean = false,
        externalModule: Boolean = false,
        log: Boolean = false,
        val logAllowed: Boolean = log,
        mount: Boolean = false,
        val mountAllowed: Boolean = mount,
        val emptyMountAllowed: Boolean = false,
        override: Boolean = false,
        test: Boolean = false
) {
    val abstract = C_ModifierValue.opt<Boolean>(abstract)
    val externalChain = C_ModifierValue.opt<C_ExternalAnnotation>(externalChain)
    val externalModule = C_ModifierValue.opt<Boolean>(externalModule)
    val log = C_ModifierValue.opt<Boolean>(log)
    val mount = C_ModifierValue.opt<C_MountAnnotation>(mount)
    val override = C_ModifierValue.opt<Boolean>(override)
    val test = C_ModifierValue.opt<Boolean>(test)

    val summarization = C_ModifierValue.opt<S_PosValue<C_AtSummarizationKind>>(type.isExpression())
    val omit = C_ModifierValue.opt<Boolean>(type.isExpression())
    val sort = C_ModifierValue.opt<S_PosValue<R_AtWhatSort>>(type.isExpression())

    fun externalChain(mntCtx: C_MountContext): C_ExternalChain? {
        val ann = externalChain?.get()
        return if (ann == null) mntCtx.extChain else mntCtx.appCtx.addExternalChain(ann.chain)
    }

    fun externalChain(ctxExtChain: C_ExtChainName?): C_ExtChainName? {
        val ann = externalChain?.get()
        return if (ann == null) ctxExtChain else C_ExtChainName(ann.chain)
    }

    fun checkAbstractTest(msgCtx: C_MessageContext, pos: S_Pos, otherValue: C_ModifierValue<Boolean>?) {
        if (otherValue?.get() ?: false) {
            msgCtx.error(pos, "modifier:module:abstract:test", "Abstract test modules are not allowed")
        }
    }
}

class C_ModifierValue<T> {
    private var value: T? = null

    fun get() = value

    fun set(value: T): Boolean {
        if (this.value != null) return false
        this.value = value
        return true
    }

    companion object {
        fun <T> opt(b: Boolean) = if (b) C_ModifierValue<T>() else null
    }
}
