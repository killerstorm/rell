/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.base.expr.C_MemberAttr_StructAttr
import net.postchain.rell.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.compiler.base.expr.C_TypeValueMember_BasicAttr
import net.postchain.rell.compiler.base.namespace.C_Namespace
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.lib.test.R_TestOpType
import net.postchain.rell.lib.test.Rt_TestOpValue
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.utils.PostchainGtvUtils

object C_Lib_Type_Struct {
    fun getValueMembers(struct: R_Struct): List<C_TypeValueMember> {
        val fns = getMemberFns(struct)
        val attrMembers = struct.attributesList.map {
            val mem = C_MemberAttr_RegularStructAttr(it)
            C_TypeValueMember_BasicAttr(mem)
        }
        return C_LibUtils.makeValueMembers(struct.type, fns, attrMembers)
    }

    private fun getMemberFns(struct: R_Struct): C_MemberFuncTable {
        val type = struct.type

        val b = C_LibUtils.typeMemFuncBuilder(type)

        val mirrorStructs = struct.mirrorStructs
        if (mirrorStructs?.operation != null) {
            val name = "to_test_op"
            val cFn = C_SysFunction.validating(StructFns.ToTestOp) { ctx ->
                if (!ctx.exprCtx.modCtx.isTestLib()) {
                    ctx.exprCtx.msgCtx.error(ctx.callPos, "expr:fn:struct:$name:no_test",
                        "Function '$name' can be called only in tests or REPL")
                }
            }
            b.add(name, R_TestOpType, listOf(), cFn)
        }

        if (struct == mirrorStructs?.immutable) {
            b.add("to_mutable", mirrorStructs.mutable.type, listOf(), StructFns.ToMutable)
        } else if (struct == mirrorStructs?.mutable) {
            b.add("to_immutable", mirrorStructs.immutable.type, listOf(), StructFns.ToImmutable)
        }

        val mToBytes = StructFns.ToBytes(struct)
        C_LibUtils.addMemFnToGtv(b, "toBytes", type, R_ByteArrayType, mToBytes, depError("to_bytes"))
        C_LibUtils.addMemFnToGtv(b, "to_bytes", type, R_ByteArrayType, mToBytes)

        // Right to_gtv*() functions are added by default, and here we add deprecated functions for compatibility
        // (they used to exist only for structs, not for all types).
        C_LibUtils.addMemFnToGtv(b, "toGTXValue", type, R_GtvType, StructFns.ToGtv(struct, false), depError("to_gtv"))
        C_LibUtils.addMemFnToGtv(b, "toPrettyGTXValue", type, R_GtvType, StructFns.ToGtv(struct, true), depError("to_gtv_pretty"))

        return b.build()
    }

    fun getNamespace(struct: R_Struct): C_Namespace {
        val type = struct.type
        val mFromBytes = StructFns.FromBytes(struct)
        val mFromGtv = StructFns.FromGtv(struct, false)
        val mFromGtvPretty = StructFns.FromGtv(struct, true)

        val fb = C_GlobalFuncBuilder(struct.type.defName.toPath())
        C_LibUtils.addGlobalFnFromGtv(fb, "fromBytes", type, R_ByteArrayType, mFromBytes, depError("from_bytes"))
        C_LibUtils.addGlobalFnFromGtv(fb, "from_bytes", type, R_ByteArrayType, mFromBytes)
        C_LibUtils.addGlobalFnFromGtv(fb, "fromGTXValue", type, R_GtvType, mFromGtv, depError("from_gtv"))
        C_LibUtils.addGlobalFnFromGtv(fb, "from_gtv", type, R_GtvType, mFromGtv)
        C_LibUtils.addGlobalFnFromGtv(fb, "fromPrettyGTXValue", type, R_GtvType, mFromGtvPretty, depError("from_gtv_pretty"))
        C_LibUtils.addGlobalFnFromGtv(fb, "from_gtv_pretty", type, R_GtvType, mFromGtvPretty)

        val fns = fb.build()
        return C_LibUtils.makeNs(type.defName.toPath(), fns)
    }

    fun toTestOp(arg: Rt_Value): Rt_TestOpValue = StructFns.toTestOp(arg)

    private class C_MemberAttr_RegularStructAttr(attr: R_Attribute): C_MemberAttr_StructAttr(attr.type, attr) {
        override fun calculator() = R_MemberCalculator_StructAttr(attr)

        override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
            if (!attr.mutable) {
                throw C_Errors.errAttrNotMutable(pos, attr.name)
            }
            return R_StructMemberExpr(base, attr)
        }
    }

    private class R_MemberCalculator_StructAttr(val attr: R_Attribute): R_MemberCalculator(attr.type) {
        override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
            val structValue = baseValue.asStruct()
            return structValue.get(attr.index)
        }
    }
}

object C_Lib_Type_VirtualStruct {
    fun getValueMembers(type: R_VirtualStructType): List<C_TypeValueMember> {
        val fns = C_LibUtils.typeMemFuncBuilder(type)
            .add("to_full", type.innerType, listOf(), C_Lib_Type_Virtual.ToFull)
            .build()

        val attrMembers = type.innerType.struct.attributesList.map { attr ->
            val virtualType = S_VirtualType.virtualMemberType(attr.type)
            val mem = C_MemberAttr_VirtualStructAttr(virtualType, attr)
            C_TypeValueMember_BasicAttr(mem)
        }

        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    private class C_MemberAttr_VirtualStructAttr(type: R_Type, attr: R_Attribute): C_MemberAttr_StructAttr(type, attr) {
        override fun calculator() = R_MemberCalculator_VirtualStructAttr(type, attr)
        override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errAttrNotMutable(pos, attr.name)
    }
}

private object StructFns {
    fun ToBytes(struct: R_Struct) = C_SysFunction.simple1(pure = true) { a ->
        val gtv = struct.type.rtToGtv(a, false)
        val bytes = PostchainGtvUtils.gtvToBytes(gtv)
        Rt_ByteArrayValue(bytes)
    }

    //TODO Reuse C_Lib_Type_Any.ToGtv
    fun ToGtv(struct: R_Struct, pretty: Boolean) = C_SysFunction.simple1(pure = true) { a ->
        val gtv = struct.type.rtToGtv(a, pretty)
        Rt_GtvValue(gtv)
    }

    fun FromBytes(struct: R_Struct) = C_SysFunction.context1(
        pure = struct.type.completeFlags().pure
    ) { ctx, a ->
        val bytes = a.asByteArray()
        Rt_Utils.wrapErr("fn:struct:from_bytes") {
            val gtv = PostchainGtvUtils.bytesToGtv(bytes)
            val convCtx = GtvToRtContext.make(false)
            val res = struct.type.gtvToRt(convCtx, gtv)
            convCtx.finish(ctx.exeCtx)
            res
        }
    }

    //TODO Reuse C_Lib_Type_Any.FromGtv
    fun FromGtv(struct: R_Struct, pretty: Boolean) = C_SysFunction.context1(
        pure = struct.type.completeFlags().pure
    ) { ctx, a ->
        val gtv = a.asGtv()
        Rt_Utils.wrapErr({ "fn:struct:from_gtv:$pretty" }) {
            val convCtx = GtvToRtContext.make(pretty)
            val res = struct.type.gtvToRt(convCtx, gtv)
            convCtx.finish(ctx.exeCtx)
            res
        }
    }

    val ToImmutable = C_SysFunction.simple1(pure = true) { a ->
        toMutableOrImmutable(a, false, "to_immutable")
    }

    val ToMutable = C_SysFunction.simple1(pure = true) { a ->
        toMutableOrImmutable(a, true, "to_mutable")
    }

    private fun toMutableOrImmutable(arg: Rt_Value, returnMutable: Boolean, name: String): Rt_Value {
        val v = arg.asStruct()

        val structType = v.type()
        val mirrorStructs = Rt_Utils.checkNotNull(structType.struct.mirrorStructs) {
            // Must not happen, checking for extra safety.
            "$name:bad_type:${v.type()}" toCodeMsg "Wrong struct type: ${v.type()}"
        }

        val resultType = mirrorStructs.getStruct(returnMutable).type
        if (structType == resultType) {
            return arg
        }

        val values = structType.struct.attributesList.map { v.get(it.index) }.toMutableList()
        return Rt_StructValue(resultType, values)
    }

    val ToTestOp = C_SysFunction.simple1 { a ->
        toTestOp(a)
    }

    fun toTestOp(a: Rt_Value): Rt_TestOpValue {
        val v = a.asStruct()

        val structType = v.type()
        val op = Rt_Utils.checkNotNull(structType.struct.mirrorStructs?.operation) {
            // Must not happen, checking for extra safety.
            "to_test_op:bad_type:${v.type()}" toCodeMsg "Wrong struct type: ${v.type()}"
        }

        val rtArgs = structType.struct.attributesList.map { v.get(it.index) }
        val gtvArgs = rtArgs.map { it.type().rtToGtv(it, false) }

        return Rt_TestOpValue(op, gtvArgs)
    }
}
