package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_String
import net.postchain.rell.model.R_MountName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_TextType
import net.postchain.rell.runtime.Rt_Value
import org.apache.commons.lang3.StringUtils

class C_ModifierContext(val msgCtx: C_MessageContext, val outerMountName: R_MountName)

object C_Modifier {
    const val EXTERNAL = "external"
    const val LOG = "log"
    const val MOUNT = "mount"
    const val SORT = "sort"
    const val SORT_DESC = "sort_desc"
    const val OMIT = "omit"

    fun compileAnnotation(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        when (name.str) {
            EXTERNAL -> C_Annotation_External.compile(ctx, name.pos, args, target)
            LOG -> C_Annotation_Log.compile(ctx, name.pos, args, target)
            MOUNT -> C_Annotation_Mount.compile(ctx, name.pos, args, target)
            OMIT -> C_Annotation_Omit.compile(ctx, name, args, target)
            SORT -> C_Annotation_Sort.compile(ctx, name, args, target, true)
            SORT_DESC -> C_Annotation_Sort.compile(ctx, name, args, target, false)
            else -> ctx.msgCtx.error(name.pos, "ann:invalid:${name.str}", "Invalid annotation: '${name.str}'")
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

private object C_Annotation_Mount {
    fun compile(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>, target: C_ModifierTarget) {
        val mountPath = processArgs(ctx, pos, args)
        if (mountPath == null) {
            return
        }

        val mountName = applyMountPath(ctx.msgCtx, pos, ctx.outerMountName, target, mountPath)
        if (mountName == null) {
            return
        }

        if (target.mount != null && target.mountAllowed && !target.emptyMountAllowed && mountName.isEmpty()) {
            ctx.msgCtx.error(pos, "ann:mount:empty:${target.type}",
                    "Cannot use empty mount name for ${target.type.description}")
        } else {
            C_AnnUtils.processAnnotation(ctx, pos, target, C_Modifier.MOUNT, target.mount, target.mountAllowed, mountName)
        }
    }

    private fun processArgs(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): C_MountPath? {
        val str = C_AnnUtils.processArgsString(ctx, pos, args)
        if (str == null) {
            return null
        }

        val res = parsePath(str)
        if (res == null) {
            ctx.msgCtx.error(pos, "ann:mount:invalid:$str", "Invalid mount name: '$str'")
        }
        return res
    }

    private fun parsePath(s: String): C_MountPath? {
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

    private fun applyMountPath(
            msgCtx: C_MessageContext,
            pos: S_Pos,
            mountName: R_MountName,
            target: C_ModifierTarget,
            mountPath: C_MountPath
    ): R_MountName? {
        var base = listOf<R_Name>()
        if (mountPath.up != null) {
            if (mountPath.up > mountName.parts.size) {
                msgCtx.error(pos, "ann:mount:up:${mountName.parts.size}:${mountPath.up}",
                        "Cannot go up by ${mountPath.up} on current mount path '$mountName'")
                return null
            }
            base = mountName.parts.subList(0, mountName.parts.size - mountPath.up)
        }

        if (!target.emptyMountAllowed && mountPath.up != null && mountPath.path.isEmpty() && !mountPath.tail) {
            msgCtx.error(pos, "ann:mount:invalid:${mountPath.str}:${target.type}",
                    "Mount path '${mountPath.str}' is invalid for ${target.type.description}")
            return null
        }

        val combined = base + mountPath.path
        if (!mountPath.tail) {
            return R_MountName(combined)
        }

        if (target.name == null) {
            msgCtx.error(pos, "ann:mount:tail:no_name:${mountPath.str}:${target.type}",
                    "Mount path '${mountPath.str}' is invalid for ${target.type.description}")
            return null
        }

        val full = combined + target.name.rName
        return R_MountName(full)
    }

    private class C_MountPath(val str: String, val up: Int?, val path: List<R_Name>, val tail: Boolean)
}

private object C_Annotation_Log {
    fun compile(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, pos, C_Modifier.LOG, args)) {
            C_AnnUtils.processAnnotation(ctx, pos, target, C_Modifier.LOG, target.log, target.logAllowed, true)
        }
    }
}

private object C_Annotation_External {
    fun compile(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>, target: C_ModifierTarget) {
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
        val name = "${C_Modifier.EXTERNAL} with argument"
        C_AnnUtils.processAnnotation(ctx, pos, target, name, target.externalChain, true, value, nameCode = code)
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

private object C_Annotation_Omit {
    fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.omit, true, true)
        }
    }
}

private object C_Annotation_Sort {
    fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget, asc: Boolean) {
        if (C_AnnUtils.checkNoArgs(ctx, name.pos, name.str, args)) {
            C_AnnUtils.processAnnotation(ctx, name.pos, target, name.str, target.sort, true, asc, generalName = "Sorting")
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
    EXPRESSION("expression")
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
        val name: S_Name?,
        abstract: Boolean = false,
        externalChain: Boolean = false,
        externalModule: Boolean = false,
        log: Boolean = false,
        val logAllowed: Boolean = log,
        mount: Boolean = false,
        val mountAllowed: Boolean = mount,
        val emptyMountAllowed: Boolean = false,
        omit: Boolean = false,
        override: Boolean = false,
        sort: Boolean = false
) {
    val abstract = C_ModifierValue.opt<Boolean>(abstract)
    val externalChain = C_ModifierValue.opt<C_ExternalAnnotation>(externalChain)
    val externalModule = C_ModifierValue.opt<Boolean>(externalModule)
    val log = C_ModifierValue.opt<Boolean>(log)
    val mount = C_ModifierValue.opt<R_MountName>(mount)
    val override = C_ModifierValue.opt<Boolean>(override)
    val omit = C_ModifierValue.opt<Boolean>(omit)
    val sort = C_ModifierValue.opt<Boolean>(sort)

    fun externalChain(mntCtx: C_MountContext): C_ExternalChain? {
        val ann = externalChain?.get()
        return if (ann == null) mntCtx.extChain else mntCtx.appCtx.addExternalChain(ann.chain)
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
