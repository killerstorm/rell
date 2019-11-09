package net.postchain.rell.parser

import net.postchain.rell.model.R_MountName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_TextType
import net.postchain.rell.runtime.Rt_Value
import org.apache.commons.lang3.StringUtils

class C_ModifierContext(val globalCtx: C_GlobalContext, val mntCtx: C_MountContext?, val outerMountName: R_MountName)

object C_Annotation {
    const val EXTERNAL = "external"
    const val LOG = "log"
    const val MOUNT = "mount"

    fun compile(ctx: C_ModifierContext, name: S_Name, args: List<Rt_Value>, target: C_ModifierTarget) {
        when (name.str) {
            EXTERNAL -> C_Annotation_External.compile(ctx, name.pos, args, target)
            LOG -> C_Annotation_Log.compile(ctx, name.pos, args, target)
            MOUNT -> C_Annotation_Mount.compile(ctx, name.pos, args, target)
            else -> ctx.globalCtx.error(name.pos, "ann:invalid:${name.str}", "Invalid annotation: '${name.str}'")
        }
    }
}

private object C_Annotation_Mount {
    fun compile(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>, target: C_ModifierTarget) {
        val mountPath = processArgs(ctx, pos,args)
        if (mountPath == null) {
            return
        }

        val mountName = applyMountPath(ctx.globalCtx, pos, ctx.outerMountName, target, mountPath)
        if (mountName == null) {
            return
        }

        if (target.mount != null && target.mountAllowed && !target.emptyMountAllowed && mountName.isEmpty()) {
            ctx.globalCtx.error(pos, "ann:mount:empty:${target.type}",
                    "Cannot use empty mount name for ${target.type.description}")
        } else {
            C_AnnUtils.processAnnotation(ctx, pos, target, C_Annotation.MOUNT, target.mount, target.mountAllowed, mountName)
        }
    }

    private fun processArgs(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): C_MountPath? {
        val str = C_AnnUtils.processArgsString(ctx, pos, args)
        if (str == null) {
            return null
        }

        val res = parsePath(str)
        if (res == null) {
            ctx.globalCtx.error(pos, "ann:mount:invalid:$str", "Invalid mount name: '$str'")
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
            globalCtx: C_GlobalContext,
            pos: S_Pos,
            mountName: R_MountName,
            target: C_ModifierTarget,
            mountPath: C_MountPath
    ): R_MountName? {
        var base = listOf<R_Name>()
        if (mountPath.up != null) {
            if (mountPath.up > mountName.parts.size) {
                globalCtx.error(pos, "ann:mount:up:${mountName.parts.size}:${mountPath.up}",
                        "Cannot go up by ${mountPath.up} on current mount path '$mountName'")
                return null
            }
            base = mountName.parts.subList(0, mountName.parts.size - mountPath.up)
        }

        if (!target.emptyMountAllowed && mountPath.up != null && mountPath.path.isEmpty() && !mountPath.tail) {
            globalCtx.error(pos, "ann:mount:invalid:${mountPath.str}:${target.type}",
                    "Mount path '${mountPath.str}' is invalid for ${target.type.description}")
            return null
        }

        val combined = base + mountPath.path
        if (!mountPath.tail) {
            return R_MountName(combined)
        }

        if (target.name == null) {
            globalCtx.error(pos, "ann:mount:tail:no_name:${mountPath.str}:${target.type}",
                    "Mount path '${mountPath.str}' is invalid for ${target.type.description}")
            return null
        }

        val tailName = target.name.toRName()
        val full = combined + tailName
        return R_MountName(full)
    }

    private class C_MountPath(val str: String, val up: Int?, val path: List<R_Name>, val tail: Boolean)
}

private object C_Annotation_Log {
    fun compile(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>, target: C_ModifierTarget) {
        if (args.isNotEmpty()) {
            ctx.globalCtx.error(pos, "ann:log:args:${args.size}", "Annotation @${C_Annotation.LOG} takes no arguments")
            return
        }

        C_AnnUtils.processAnnotation(ctx, pos, target, C_Annotation.LOG, target.log, target.logAllowed, true)
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
        val name = "${C_Annotation.EXTERNAL} with argument"
        C_AnnUtils.processAnnotation(ctx, pos, target, name, target.externalChain, true, value, nameCode = code)
    }

    private fun compileNoArgs(ctx: C_ModifierContext, pos: S_Pos, target: C_ModifierTarget) {
        val code = "external:nullary"
        val name = "${C_Annotation.EXTERNAL} without argument"
        C_AnnUtils.processAnnotation(ctx, pos, target, name, target.externalModule, true, true, nameCode = code)
    }

    private fun processArgs(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): String? {
        val str = C_AnnUtils.processArgsString(ctx, pos, args)
        if (str != null && str.isEmpty()) {
            ctx.globalCtx.error(pos, "ann:external:invalid:$str", "Invalid external chain name: '$str'")
            return null
        }
        return str
    }
}

private object C_AnnUtils {
    fun processArgsString(ctx: C_ModifierContext, pos: S_Pos, args: List<Rt_Value>): String? {
        val expectedArgs = 1
        if (args.size != expectedArgs) {
            ctx.globalCtx.error(pos, "ann:mount:arg_count:${args.size}",
                    "Wrong number of arguments (expected $expectedArgs)")
            return null
        }

        val arg = args[0]
        val type = arg.type()
        if (type != R_TextType) {
            ctx.globalCtx.error(pos, "ann:mount:arg_type:${type.toStrictString()}",
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
            nameCode: String = name
    ) {
        if (field == null) {
            ctx.globalCtx.error(pos, "ann:$nameCode:target_type:${target.type}",
                    "Annotation @$name cannot be used for ${target.type.description}")
        } else if (!allowed) {
            var msg = "Annotation @$name not allowed for ${target.type.description}"
            if (target.name != null) msg += " '${target.name.str}'"
            ctx.globalCtx.error(pos, "ann:$nameCode:not_allowed:${target.type}:${target.name}", msg)
        } else if (!field.set(value)) {
            ctx.globalCtx.error(pos, "ann:$nameCode:dup", "Annotation @$name specified multiple times")
        }
    }
}

class C_ExternalAnnotation(val pos: S_Pos, val chain: String)

class C_ModifierTarget(
        val type: C_DeclarationType,
        val name: S_Name?,
        externalChain: Boolean = false,
        externalModule: Boolean = false,
        log: Boolean = false,
        val logAllowed: Boolean = log,
        mount: Boolean = false,
        val mountAllowed: Boolean = mount,
        val emptyMountAllowed: Boolean = false
) {
    val externalChain = C_ModifierValue.opt<C_ExternalAnnotation>(externalChain)
    val externalModule = C_ModifierValue.opt<Boolean>(externalModule)
    val log = C_ModifierValue.opt<Boolean>(log)
    val mount = C_ModifierValue.opt<R_MountName>(mount)

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
