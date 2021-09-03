/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.utils

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.def.C_MntEntry
import net.postchain.rell.compiler.base.expr.C_ExprContextAttr
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.model.R_Definition
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.model.R_Type

object C_Errors {
    fun errTypeMismatch(pos: S_Pos, srcType: R_Type, dstType: R_Type, errCode: String, errMsg: String): C_Error {
        return C_Error.stop(pos, "$errCode:[${dstType.toStrictString()}]:[${srcType.toStrictString()}]",
                "$errMsg: ${srcType.toStrictString()} instead of ${dstType.toStrictString()}")
    }

    fun errTypeMismatch(msgCtx: C_MessageContext, pos: S_Pos, srcType: R_Type, dstType: R_Type, errCode: String, errMsg: String) {
        if (srcType.isNotError() && dstType.isNotError()) {
            val srcTypeStr = srcType.toStrictString()
            val dstTypeStr = dstType.toStrictString()
            msgCtx.error(pos, "$errCode:[$dstTypeStr]:[$srcTypeStr]", "$errMsg: $srcTypeStr instead of $dstTypeStr")
        }
    }

    fun errMultipleAttrs(pos: S_Pos, attrs: List<C_ExprContextAttr>, errCode: String, errMsg: String): C_Error {
        val attrNames = attrs.map { it.attrNameMsg(true) }
        val attrsCode = attrNames.joinToString(",") { it.code }
        val attrsText = attrNames.joinToString(", ") { it.msg }
        return C_Error.stop(pos, "$errCode:$attrsCode", "$errMsg: $attrsText")
    }

    fun errUnknownName(baseType: R_Type, name: S_Name): C_Error {
        val baseName = baseType.name
        return C_Error.stop(name.pos, "unknown_name:$baseName.${name.str}", "Unknown name: '$baseName.${name.str}'")
    }

    fun errUnknownName(baseName: List<S_Name>, name: S_Name): C_Error {
        val fullName = baseName + listOf(name)
        val nameStr = C_Utils.nameStr(fullName)
        return errUnknownName(name.pos, nameStr)
    }

    fun errUnknownName(name: S_Name): C_Error {
        return errUnknownName(name.pos, name.str)
    }

    fun errUnknownName(pos: S_Pos, str: String): C_Error {
        return C_Error.stop(pos, "unknown_name:$str", "Unknown name: '$str'")
    }

    fun errUnknownAttr(name: S_Name): C_Error {
        val nameStr = name.str
        return C_Error.stop(name.pos, "expr_attr_unknown:$nameStr", "Unknown attribute: '$nameStr'")
    }

    fun errUnknownFunction(name: S_Name): C_Error {
        return C_Error.stop(name.pos, "unknown_fn:${name.str}", "Unknown function: '${name.str}'")
    }

    fun errUnknownMember(type: R_Type, name: S_Name): C_Error {
        return C_Error.stop(name.pos, "unknown_member:[${type.toStrictString()}]:${name.str}",
                "Type ${type.toStrictString()} has no member '${name.str}'")
    }

    fun errUnknownMember(msgCtx: C_MessageContext, type: R_Type, name: S_Name) {
        if (type.isNotError()) {
            msgCtx.error(name.pos, "unknown_member:[${type.toStrictString()}]:${name.str}",
                    "Type ${type.toStrictString()} has no member '${name.str}'")
        }
    }

    fun errFunctionNoSql(pos: S_Pos, name: String): C_Error {
        return C_Error.stop(pos, "expr_call_nosql:$name", "Function '$name' cannot be converted to SQL")
    }

    fun errSysFunctionNamedArg(msgCtx: C_MessageContext, fnName: String, arg: S_Name) {
        val msg = msgSysFunctionNamedArg(fnName, arg)
        msgCtx.error(arg.pos, msg)
    }

    fun msgSysFunctionNamedArg(fnName: String, arg: S_Name): C_CodeMsg {
        return C_CodeMsg("expr:call:sys_global_named_arg:$arg", "Named arguments not supported for function '$fnName'")
    }

    fun errNamedArgsNotSupported(msgCtx: C_MessageContext, fn: String?, arg: S_Name) {
        val fnCode = fn ?: ""
        val fnMsg = if (fn == null) "this function" else "function '$fn'"
        msgCtx.error(arg.pos, "expr:call:named_args_not_allowed:$fnCode:$arg", "Named arguments not supported for $fnMsg")
    }

    fun errBadDestination(pos: S_Pos): C_Error {
        return C_Error.stop(pos, "expr_bad_dst", "Invalid assignment destination")
    }

    fun errBadDestination(name: S_Name): C_Error {
        return errBadDestination(name.pos, name.str)
    }

    fun errBadDestination(pos: S_Pos, name: String): C_Error {
        return C_Error.stop(pos, "expr_bad_dst:$name", "Cannot modify '$name'")
    }

    fun errAttrNotMutable(pos: S_Pos, name: String): C_Error {
        return C_Error.stop(pos, msgAttrNotMutable(name))
    }

    fun msgAttrNotMutable(name: String): C_CodeMsg {
        return C_CodeMsg("update_attr_not_mutable:$name", "Attribute '$name' is not mutable")
    }

    fun errExprNoDb(pos: S_Pos, type: R_Type): C_Error {
        val typeStr = type.toStrictString()
        return C_Error.stop(pos, "expr_nosql:$typeStr", "Value of type $typeStr cannot be converted to SQL")
    }

    fun errExprNoDb(msgCtx: C_MessageContext, pos: S_Pos, type: R_Type) {
        val typeStr = type.toStrictString()
        msgCtx.error(pos, "expr_nosql:$typeStr", "Value of type $typeStr cannot be converted to SQL")
    }

    fun errExprDbNotAllowed(pos: S_Pos): C_Error {
        return C_Error.stop(pos, "expr_sqlnotallowed", "Expression cannot be converted to SQL")
    }

    fun errCannotUpdate(msgCtx: C_MessageContext, pos: S_Pos, name: String) {
        msgCtx.error(pos, "stmt_update_cant:$name", "Not allowed to update objects of entity '$name'")
    }

    fun errCannotDelete(pos: S_Pos, name: String): C_Error {
        return C_Error.stop(pos, "stmt_delete_cant:$name", "Not allowed to delete objects of entity '$name'")
    }

    fun errNameConflictAliasLocal(name: S_Name): C_Error {
        val nameStr = name.str
        throw C_Error.stop(name.pos, "expr_name_entity_local:$nameStr",
                "Name '$nameStr' is ambiguous: can be entity alias or local variable")
    }

    fun errNameConflict(name: S_Name, otherType: C_DeclarationType, otherPos: S_Pos?): C_PosCodeMsg {
        val baseCode = "name_conflict"
        val baseMsg = "Name conflict"
        val codeMsg = if (otherPos != null) {
            val code = "$baseCode:user:${name.str}:$otherType:$otherPos"
            val msg = "$baseMsg: ${otherType.msg} '${name.str}' defined at ${otherPos.strLine()}"
            C_CodeMsg(code, msg)
        } else {
            C_CodeMsg("$baseCode:sys:${name.str}:$otherType", "$baseMsg: system ${otherType.msg} '${name.str}'")
        }
        return C_PosCodeMsg(name.pos, codeMsg)
    }

    fun errMountConflict(
            chain: String?,
            mountName: R_MountName,
            def: R_Definition,
            pos: S_Pos,
            otherEntry: C_MntEntry
    ): C_Error {
        val baseCode = "mnt_conflict"
        val commonCode = "[${def.appLevelName}]:$mountName:${otherEntry.type}:[${otherEntry.def.appLevelName}]"
        val baseMsg = "Mount name conflict" + if (chain == null) "" else "(external chain '$chain')"
        val otherNameMsg = otherEntry.def.simpleName

        val code: String
        val msg: String

        if (otherEntry.pos != null) {
            code = "$baseCode:user:$commonCode:${otherEntry.pos}"
            msg = "$baseMsg: ${otherEntry.type.msg} '$otherNameMsg' has mount name '$mountName' " +
                    "(defined at ${otherEntry.pos.strLine()})"
        } else {
            code = "$baseCode:sys:$commonCode"
            msg = "$baseMsg: system ${otherEntry.type.msg} '$otherNameMsg' has mount name '$mountName'"
        }

        return C_Error.stop(pos, code, msg)
    }

    fun errMountConflictSystem(mountName: R_MountName, def: R_Definition, pos: S_Pos): C_Error {
        val code = "mnt_conflict:sys:[${def.appLevelName}]:$mountName"
        val msg = "Mount name conflict: '$mountName' is a system mount name"
        return C_Error.stop(pos, code, msg)
    }

    fun errDuplicateAttribute(msgCtx: C_MessageContext, name: S_Name) {
        msgCtx.error(name.pos, "dup_attr:$name", "Duplicate attribute: '$name'")
    }

    fun errAttributeTypeUnknown(msgCtx: C_MessageContext, name: S_Name) {
        msgCtx.error(name.pos, "unknown_name_type:${name.str}",
                "Cannot infer type for '${name.str}'; specify type explicitly")
    }

    fun errAtPlaceholderNotDefined(pos: S_Pos): C_Error {
        return C_Error.stop(pos, "expr:placeholder:none", "Placeholder not defined")
    }

    fun errOverrideMissing(msgCtx: C_MessageContext, pos: S_Pos, name: String, defPos: S_Pos) {
        var code = "override:missing:[$name]"
        var msg = "No override for abstract function '$name'"
        if (defPos != pos) {
            code += ":[${defPos.strLine()}]"
            msg += " (defined at ${defPos.strLine()})"
        }
        msgCtx.error(pos, code, msg)
    }

    fun msgPartialCallNotAllowed(fnName: String?): C_CodeMsg {
        val fnCode = fnName ?: "?"
        val fnMsg = if (fnName == null) "this function" else "function '$fnName'"
        return C_CodeMsg("expr:call:partial_not_supported:$fnCode", "Partial application not supported for $fnMsg")
    }

    fun msgPartialCallAmbiguous(fnName: String?): C_CodeMsg {
        val fnCode = fnName ?: "?"
        val fnMsg = if (fnName == null) "the function" else "function '$fnName'"
        return C_CodeMsg("expr:call:partial_ambiguous:$fnCode", "Cannot determine which variant of $fnMsg to use")
    }

    fun msgModuleNotFound(name: R_ModuleName): C_CodeMsg {
        return C_CodeMsg("import:not_found:$name", "Module '$name' not found")
    }

    fun check(b: Boolean, pos: S_Pos, code: String, msg: String) {
        if (!b) {
            throw C_Error.stop(pos, code, msg)
        }
    }

    fun check(b: Boolean, pos: S_Pos, codeMsgSupplier: () -> Pair<String, String>) {
        if (!b) {
            val (code, msg) = codeMsgSupplier()
            throw C_Error.stop(pos, code, msg)
        }
    }

    fun check(ctx: C_MessageContext, b: Boolean, pos: S_Pos, code: String, msg: String) {
        if (!b) {
            ctx.error(pos, code, msg)
        }
    }

    fun check(ctx: C_MessageContext, b: Boolean, pos: S_Pos, codeMsgSupplier: () -> Pair<String, String>) {
        if (!b) {
            val (code, msg) = codeMsgSupplier()
            ctx.error(pos, code, msg)
        }
    }

    fun <T> checkNotNull(value: T?, pos: S_Pos, code: String, msg: String): T {
        if (value == null) {
            throw C_Error.stop(pos, code, msg)
        }
        return value
    }

    fun <T> checkNotNull(value: T?, pos: S_Pos, codeMsgSupplier: () -> Pair<String, String>): T {
        if (value == null) {
            val (code, msg) = codeMsgSupplier()
            throw C_Error.stop(pos, code, msg)
        }
        return value
    }
}
