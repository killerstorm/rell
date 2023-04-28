/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.test

import net.postchain.common.hexStringToByteArray
import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.compiler.base.core.C_DefinitionName
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_List
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_MirrorStructOperation
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.lib.type.C_Lib_Type
import net.postchain.rell.lib.type.C_Lib_Type_Struct
import net.postchain.rell.model.*
import net.postchain.rell.module.GtvRtConversion
import net.postchain.rell.module.GtvRtConversion_None
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.utils.BytesKeyPair
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet
import java.util.*

object C_Lib_Test {
    const val MODULE = "rell.test"
    val MODULE_NAME = R_ModuleName.of(MODULE)

    val NAMESPACE_NAME = C_StringQualifiedName.ofRNames(MODULE_NAME.parts)
    val NAMESPACE_DEF_NAME = C_DefinitionName(C_LibUtils.DEFAULT_MODULE_STR, NAMESPACE_NAME)
    val NAMESPACE_DEF_PATH = NAMESPACE_DEF_NAME.toPath()

    const val BLOCK_SNAME = "block"
    const val TX_SNAME = "tx"
    const val OP_SNAME = "op"

    val BLOCK_TYPE_QNAME = NAMESPACE_NAME.add(BLOCK_SNAME)
    val TX_TYPE_QNAME = NAMESPACE_NAME.add(TX_SNAME)
    val OP_TYPE_QNAME = NAMESPACE_NAME.add(OP_SNAME)

    val BLOCK_TYPE_QNAME_STR = BLOCK_TYPE_QNAME.str()
    val TX_TYPE_QNAME_STR = TX_TYPE_QNAME.str()
    val OP_TYPE_QNAME_STR = OP_TYPE_QNAME.str()

    val BLOCK_RUNNER_KEYPAIR: BytesKeyPair = let {
        val privKey = "42".repeat(32).hexStringToByteArray()
        val pubKey = secp256k1_derivePubKey(privKey)
        BytesKeyPair(privKey, pubKey)
    }

    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        C_Lib_Test_Assert.bind(nsBuilder)
        C_Lib_Test_Events.bindGlobal(nsBuilder)
    }

    fun bindRell(nsBuilder: C_SysNsProtoBuilder) {
        val nsName = "test"
        val b = C_SysNsProtoBuilder(nsBuilder.basePath.subPath(nsName))

        C_Lib_Type_Block.bind(b)
        C_Lib_Type_Tx.bind(b)
        C_Lib_Type_Op.bind(b)

        C_Lib_Test_KeyPairs.bind(b)
        C_Lib_Test_Assert.bind(b)
        C_Lib_Test_Events.bindRellTest(b)

        bindFunctions(b)

        nsBuilder.addNamespace(nsName, b.build().toNamespace())
    }

    private fun bindFunctions(b: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder(
            NAMESPACE_DEF_PATH,
            typeNames = listOf(BLOCK_SNAME, TX_SNAME, OP_SNAME).map { R_Name.of(it) }.toImmSet()
        )

        C_Lib_Nop.bindGlobal(fb)

        C_LibUtils.bindFunctions(b, fb.build())
    }

    fun typeDefName(name: C_StringQualifiedName) = C_DefinitionName(C_Lib_Test.MODULE, name)
}

private fun typeDefName(name: C_StringQualifiedName) = C_Lib_Test.typeDefName(name)

private object R_TestBlockType: R_BasicType(C_Lib_Test.BLOCK_TYPE_QNAME.str(), typeDefName(C_Lib_Test.BLOCK_TYPE_QNAME)) {
    override fun isReference() = true
    override fun isDirectMutable() = true
    override fun isDirectPure() = false
    override fun createGtvConversion() = GtvRtConversion_None
    override fun getLibType(): C_Lib_Type = C_Lib_Type_Block
}

private object R_TestTxType: R_BasicType(C_Lib_Test.TX_TYPE_QNAME.str(), typeDefName(C_Lib_Test.TX_TYPE_QNAME)) {
    override fun isReference() = true
    override fun isDirectMutable() = true
    override fun isDirectPure() = false
    override fun createGtvConversion() = GtvRtConversion_None
    override fun getLibType(): C_Lib_Type = C_Lib_Type_Tx
}

object R_TestOpType: R_BasicType(C_Lib_Test.OP_TYPE_QNAME.str(), typeDefName(C_Lib_Test.OP_TYPE_QNAME)) {
    override fun isReference() = true
    override fun isDirectPure() = false
    override fun createGtvConversion(): GtvRtConversion = GtvRtConversion_None
    override fun getLibType(): C_Lib_Type = C_Lib_Type_Op
}

private class BlockCommonFunctions(
        typeQName: String,
        private val blockGetter: (self: Rt_Value) -> Rt_TestBlockValue
) {
    private val runSimpleName = "run"
    private val runMustFailSimpleName = "run_must_fail"

    private val runFullName = "$typeQName.$runSimpleName"
    private val runMustFailFullName = "$typeQName.$runMustFailSimpleName"

    fun bind(b: C_MemberFuncBuilder) {
        val failType = C_Lib_Test_Assert.FAILURE_TYPE
        b.add(runSimpleName, R_UnitType, listOf(), Run())
        b.add(runMustFailSimpleName, failType, listOf(), RunMustFail0())
        b.add(runMustFailSimpleName, failType, listOf(R_TextType), RunMustFail1())
    }

    private fun getRunBlock(ctx: Rt_CallContext, arg: Rt_Value, fnName: String): Rt_TestBlockValue {
        val block = blockGetter(arg)
        if (!ctx.appCtx.repl && !ctx.appCtx.test) {
            throw Rt_Exception.common("fn:$fnName:no_repl_test", "Block can be executed only in REPL or test")
        }
        return block
    }

    private fun runMustFail(ctx: Rt_CallContext, block: Rt_TestBlockValue, expected: String?): Rt_Value {
        try {
            ctx.appCtx.blockRunner.runBlock(ctx, block)
        } catch (e: Throwable) {
            val actual = e.message ?: ""
            if (expected != null) {
                if (actual != expected) {
                    val code = "$runMustFailSimpleName:mismatch:[$expected]:[$actual]"
                    val msg = "wrong error: expected <$expected> but was <$actual>"
                    throw Rt_AssertError.exception(code, msg)
                }
            }
            return C_Lib_Test_Assert.failureValue(actual)
        }
        throw Rt_Exception.common("fn:$runMustFailFullName:nofail", "Transaction did not fail")
    }

    private inner class Run: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val block = getRunBlock(ctx, arg, runFullName)
            try {
                ctx.appCtx.blockRunner.runBlock(ctx, block)
            } catch (e: Rt_Exception) {
                throw e
            } catch (e: Throwable) {
                throw Rt_Exception.common("fn:$runFullName:fail:${e.javaClass.canonicalName}", "Block execution failed: $e")
            }
            return Rt_UnitValue
        }
    }

    private inner class RunMustFail0: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val block = getRunBlock(ctx, arg, runMustFailFullName)
            return runMustFail(ctx, block, null)
        }
    }

    private inner class RunMustFail1: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val block = getRunBlock(ctx, arg1, runMustFailFullName)
            val expected = arg2.asString()
            return runMustFail(ctx, block, expected)
        }
    }
}

private class TxCommonFunctions(private val txGetter: (self: Rt_Value) -> Rt_TestTxValue) {
    fun bind(b: C_MemberFuncBuilder) {
        b.add("sign", R_TestTxType, listOf(R_ListType(C_Lib_Test_KeyPairs.KEYPAIR_TYPE)), sign_listOfKeyPair)
        b.add("sign", R_TestTxType, listOf(R_ListType(R_ByteArrayType)), sign_listOfByteArray)
        b.addOneMany("sign", R_TestTxType, listOf(), R_ByteArrayType, sign_byteArrays)
        b.addOneMany("sign", R_TestTxType, listOf(), C_Lib_Test_KeyPairs.KEYPAIR_TYPE, sign_keyPairs)
    }

    private val sign_listOfKeyPair = object: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return signByKeyPairs(arg1, arg2.asList())
        }
    }

    private val sign_listOfByteArray = object: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return signByByteArrays(arg1, arg2.asList())
        }
    }

    private val sign_keyPairs = object: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return signByKeyPairs(args[0], args.subList(1, args.size))
        }
    }

    private val sign_byteArrays = object: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size >= 1)
            return signByByteArrays(args[0], args.subList(1, args.size))
        }
    }

    private fun signByKeyPairs(self: Rt_Value, keyPairs: List<Rt_Value>): Rt_Value {
        val tx = txGetter(self)
        for (keyPairValue in keyPairs) {
            val keyPair = C_Lib_Test_KeyPairs.structToKeyPair(keyPairValue)
            tx.sign(keyPair)
        }
        return tx
    }

    private fun signByByteArrays(self: Rt_Value, byteArrays: List<Rt_Value>): Rt_Value {
        val tx = txGetter(self)
        for (v in byteArrays) {
            val bs = v.asByteArray()
            val keyPair = privKeyToKeyPair(bs)
            tx.sign(keyPair)
        }
        return tx
    }

    private fun privKeyToKeyPair(priv: ByteArray): BytesKeyPair {
        val privSize = 32
        Rt_Utils.check(priv.size == privSize) { "tx.sign:priv_key_size:$privSize:${priv.size}" toCodeMsg
                "Wrong size of private key: ${priv.size} instead of $privSize"
        }

        val pub = secp256k1_derivePubKey(priv)
        val pubSize = 33
        Rt_Utils.check(pub.size == pubSize) { "tx.sign:pub_key_size:$pubSize:${pub.size}" toCodeMsg
                "Wrong size of calculated public key: ${pub.size} instead of $pubSize"
        }

        return BytesKeyPair(priv, pub)
    }
}

private object C_Lib_Type_Block: C_Lib_Type(C_Lib_Test.BLOCK_SNAME, R_TestBlockType, defaultMemberFns = false) {
    private val common = BlockCommonFunctions(C_Lib_Test.BLOCK_TYPE_QNAME_STR) { asTestBlock(it) }

    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        val name = typeName.str
        b.add(name, R_TestBlockType, listOf(R_ListType(R_TestTxType)), New_ListOfTxs)
        b.addZeroMany(name, R_TestBlockType, listOf(), R_TestTxType, New_Txs)
        b.add(name, R_TestBlockType, listOf(R_ListType(R_TestOpType)), New_ListOfOps)
        b.addOneMany(name, R_TestBlockType, listOf(), R_TestOpType, New_Ops)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        common.bind(b)
        b.add("copy", R_TestBlockType, listOf(), Copy)

        b.add("tx", R_TestBlockType, listOf(R_ListType(R_TestTxType)), Tx_ListOfTxs)
        b.addOneMany("tx", R_TestBlockType, listOf(), R_TestTxType, Tx_Txs)
        b.add("tx", R_TestBlockType, listOf(R_ListType(R_TestOpType)), Tx_ListOfOps)
        b.addOneMany("tx", R_TestBlockType, listOf(), R_TestOpType, Tx_Ops)
    }

    private object New_ListOfTxs: R_SysFunction_1() {
        override fun call(arg: Rt_Value) = Rt_TestBlockValue(arg.asList().map { asTestTx(it).toRaw() })
    }

    private object New_Txs: R_SysFunction_N() {
        override fun call(args: List<Rt_Value>) = Rt_TestBlockValue(args.map { asTestTx(it).toRaw() })
    }

    private object New_ListOfOps: R_SysFunction_1() {
        override fun call(arg: Rt_Value) = newOps(arg.asList())
    }

    private object New_Ops: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>) = newOps(args)
    }

    private fun newOps(ops: List<Rt_Value>): Rt_Value {
        val rawOps = ops.map { asTestOp(it).toRaw() }
        val tx = RawTestTxValue(rawOps, listOf())
        return Rt_TestBlockValue(listOf(tx))
    }

    private object Tx_ListOfTxs: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return addTxs(arg1, arg2.asList())
        }
    }

    private object Tx_Txs: R_SysFunction_N() {
        override fun call(args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return addTxs(args[0], args.subList(1, args.size))
        }
    }

    private fun addTxs(self: Rt_Value, txs: List<Rt_Value>): Rt_Value {
        val block = asTestBlock(self)
        txs.forEach { block.addTx(asTestTx(it).toRaw()) }
        return self
    }

    private object Tx_ListOfOps: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return addOps(arg1, arg2.asList())
        }
    }

    private object Tx_Ops: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return addOps(args[0], args.subList(1, args.size))
        }
    }

    private fun addOps(self: Rt_Value, ops: List<Rt_Value>): Rt_Value {
        val block = asTestBlock(self)
        val rawOps = ops.map { asTestOp(it).toRaw() }
        val tx = RawTestTxValue(rawOps, listOf())
        block.addTx(tx)
        return self
    }

    private object Copy: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val block = asTestBlock(arg)
            return Rt_TestBlockValue(block.txs())
        }
    }
}

private object C_Lib_Type_Tx: C_Lib_Type(C_Lib_Test.TX_SNAME, R_TestTxType, defaultMemberFns = false) {
    private val block = BlockCommonFunctions(C_Lib_Test.TX_TYPE_QNAME_STR) {
        Rt_TestBlockValue(listOf(asTestTx(it).toRaw()))
    }

    private val tx = TxCommonFunctions { asTestTx(it) }

    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        val name = typeName.str
        b.add(name, R_TestTxType, listOf(R_ListType(R_TestOpType)), New_ListOfOps)
        b.addZeroMany(name, R_TestTxType, listOf(), R_TestOpType, New_Ops)
        b.addEx(name, R_TestTxType, listOf(C_ArgTypeMatcher_List(C_ArgTypeMatcher_MirrorStructOperation)), New_ListOfStructs)
        b.addOneMany(name, R_TestTxType, listOf(), C_ArgTypeMatcher_MirrorStructOperation, New_Structs)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("op", R_TestTxType, listOf(R_ListType(R_TestOpType)), Op_ListOfOps)
        b.addOneMany("op", R_TestTxType, listOf(), R_TestOpType, Op_Ops)
        b.addEx("op", R_TestTxType, listOf(C_ArgTypeMatcher_List(C_ArgTypeMatcher_MirrorStructOperation)), Op_ListOfStructs)
        b.addOneMany("op", R_TestTxType, listOf(), C_ArgTypeMatcher_MirrorStructOperation, Op_Structs)

        b.add("nop", R_TestTxType, listOf(), Nop_NoArgs)
        b.add("nop", R_TestTxType, listOf(R_IntegerType), Nop_OneArg)
        b.add("nop", R_TestTxType, listOf(R_TextType), Nop_OneArg)
        b.add("nop", R_TestTxType, listOf(R_ByteArrayType), Nop_OneArg)

        b.add("copy", R_TestTxType, listOf(), Copy)

        block.bind(b)
        tx.bind(b)
    }

    private object New_Ops: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            val ops = args.map { asTestOp(it).toRaw() }
            return newTx(ops)
        }
    }

    private object New_ListOfOps: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val ops = arg.asList().map { asTestOp(it).toRaw() }
            return newTx(ops)
        }
    }

    private object New_Structs: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            val ops = args.map { structToOp(it) }
            return newTx(ops)
        }
    }

    private object New_ListOfStructs: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val list = arg.asList()
            val ops = list.map { structToOp(it) }
            return newTx(ops)
        }
    }

    private fun newTx(ops: List<RawTestOpValue>): Rt_Value {
        return Rt_TestTxValue(ops, listOf())
    }

    private object Op_Ops: R_SysFunction_N() {
        override fun call(args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return addOps(args[0], args.subList(1, args.size))
        }
    }

    private object Op_ListOfOps: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return addOps(arg1, arg2.asList())
        }
    }

    private fun addOps(self: Rt_Value, ops: List<Rt_Value>): Rt_Value {
        val tx = asTestTx(self)
        ops.forEach { tx.addOp(asTestOp(it).toRaw()) }
        return self
    }

    private object Op_Structs: R_SysFunction_N() {
        override fun call(args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return addOpStructs(args[0], args.subList(1, args.size))
        }
    }

    private object Op_ListOfStructs: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return addOpStructs(arg1, arg2.asList())
        }
    }

    private fun addOpStructs(self: Rt_Value, ops: List<Rt_Value>): Rt_Value {
        val tx = asTestTx(self)
        ops.forEach { tx.addOp(structToOp(it)) }
        return self
    }

    private object Nop_NoArgs: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val tx = asTestTx(arg)
            val op = C_Lib_Nop.callNoArgs(ctx)
            tx.addOp(op.toRaw())
            return tx
        }
    }

    private object Nop_OneArg: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val tx = asTestTx(arg1)
            val op = C_Lib_Nop.callOneArg(arg2)
            tx.addOp(op.toRaw())
            return tx
        }
    }

    private object Copy: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val tx = asTestTx(arg)
            return tx.copy()
        }
    }

    private fun structToOp(v: Rt_Value): RawTestOpValue = C_Lib_Type_Struct.toTestOp(v).toRaw()
}

private object C_Lib_Type_Op: C_Lib_Type(C_Lib_Test.OP_SNAME, R_TestOpType, defaultMemberFns = false) {
    private val block = BlockCommonFunctions(C_Lib_Test.OP_TYPE_QNAME_STR) {
        Rt_TestBlockValue(listOf(selfToTx(it).toRaw()))
    }

    private val tx = TxCommonFunctions(this::selfToTx)

    private fun selfToTx(self: Rt_Value): Rt_TestTxValue {
        return Rt_TestTxValue(listOf(asTestOp(self).toRaw()), listOf())
    }

    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        val name = typeName.str
        b.add(name, R_TestOpType, listOf(R_TextType, R_ListType(R_GtvType)), New_Text_ListOfGtv)
        b.addZeroMany(name, R_TestOpType, listOf(R_TextType), R_GtvType, New_Text_Gtvs)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        block.bind(b)
        tx.bind(b)
        b.add("tx", R_TestTxType, listOf(), ToTx)
    }

    private object New_Text_ListOfGtv: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return newOp(arg1, arg2.asList())
        }
    }

    private object New_Text_Gtvs: R_SysFunction_N() {
        override fun call(args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            val nameArg = args[0]
            val tailArgs = args.subList(1, args.size)
            return newOp(nameArg, tailArgs)
        }
    }

    private fun newOp(nameArg: Rt_Value, tailArgs: List<Rt_Value>): Rt_Value {
        val nameStr = nameArg.asString()
        val args = tailArgs.map { it.asGtv() }

        val name = R_MountName.ofOpt(nameStr)
        Rt_Utils.check(name != null && !name.isEmpty()) {
            "${C_Lib_Test.OP_TYPE_QNAME_STR}:bad_name:$nameStr" toCodeMsg "Bad operation name: '$nameStr'"
        }
        name!!

        return Rt_TestOpValue(name, args)
    }

    private object ToTx: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val op = asTestOp(arg).toRaw()
            return Rt_TestTxValue(listOf(op), listOf())
        }
    }
}

private object C_Lib_Nop {
    private val MOUNT_NAME = R_MountName.of("nop")

    fun bindGlobal(b: C_GlobalFuncBuilder) {
        val name = "nop"
        b.add(name, R_TestOpType, listOf(), NoArgs)
        b.add(name, R_TestOpType, listOf(R_IntegerType), OneArg)
        b.add(name, R_TestOpType, listOf(R_TextType), OneArg)
        b.add(name, R_TestOpType, listOf(R_ByteArrayType), OneArg)
    }

    fun callNoArgs(ctx: Rt_CallContext): Rt_TestOpValue {
        val v = ctx.exeCtx.nextNopNonce()
        val gtv = GtvFactory.gtv(v)
        return makeValue(gtv)
    }

    fun callOneArg(arg: Rt_Value): Rt_TestOpValue {
        val gtv = arg.type().rtToGtv(arg, false)
        return makeValue(gtv)
    }

    private fun makeValue(arg: Gtv) = Rt_TestOpValue(MOUNT_NAME, listOf(arg))

    private object NoArgs: R_SysFunctionEx_0() {
        override fun call(ctx: Rt_CallContext) = callNoArgs(ctx)
    }

    private object OneArg: R_SysFunction_1() {
        override fun call(arg: Rt_Value) = callOneArg(arg)
    }
}

class Rt_TestBlockValue(txs: List<RawTestTxValue>): Rt_Value() {
    private val txs = txs.toMutableList()

    override val valueType = VALUE_TYPE

    override fun type(): R_Type = R_TestBlockType

    override fun strCode(showTupleFieldNames: Boolean): String {
        val txsStr = txs.joinToString(",") { Rt_TestTxValue.strCode(it.ops, it.signers) }
        return "${C_Lib_Test.BLOCK_TYPE_QNAME_STR}[$txsStr]"
    }

    override fun str() = "block(${txs.joinToString(",")})"

    override fun equals(other: Any?) = other === this || (other is Rt_TestBlockValue && txs == other.txs)
    override fun hashCode() = Objects.hash(txs)

    fun txs() = txs.toImmList()

    fun addTx(tx: RawTestTxValue) {
        txs.add(tx)
    }

    companion object {
        private val VALUE_TYPE = Rt_LibValueType.of("TEST_BLOCK")
    }
}

class Rt_TestTxValue(
        ops: List<RawTestOpValue>,
        signers: List<BytesKeyPair>
): Rt_Value() {
    private val ops = ops.toMutableList()
    private val signers = signers.toMutableList()

    override val valueType = VALUE_TYPE

    override fun type(): R_Type = R_TestTxType

    override fun str() = toString(ops)
    override fun strCode(showTupleFieldNames: Boolean) = strCode(ops, signers)

    override fun equals(other: Any?) = other === this || (other is Rt_TestTxValue && ops == other.ops && signers == other.signers)
    override fun hashCode() = Objects.hash(ops, signers)

    fun addOp(op: RawTestOpValue) {
        ops.add(op)
    }

    fun sign(keyPair: BytesKeyPair) {
        signers.add(keyPair)
    }

    fun copy() = Rt_TestTxValue(ops, signers)

    fun toRaw() = RawTestTxValue(ops, signers)

    companion object {
        private val VALUE_TYPE = Rt_LibValueType.of("TEST_TX")

        fun strCode(ops: List<RawTestOpValue>, signers: List<BytesKeyPair>): String {
            val opsList = ops.map { Rt_TestOpValue.strCode(it.name, it.args) }
            val signersList = signers.map { it.pub.toHex().substring(0, 6).toLowerCase()}
            val innerStr = (opsList + signersList).joinToString(",")
            return "${C_Lib_Test.TX_TYPE_QNAME_STR}[$innerStr]"
        }

        fun toString(ops: List<RawTestOpValue>): String {
            val opsStr = ops.joinToString(",")
            return "tx[$opsStr]"
        }
    }
}

class Rt_TestOpValue(private val name: R_MountName, args: List<Gtv>): Rt_Value() {
    private val args = args.toImmList()

    override val valueType = VALUE_TYPE

    override fun type(): R_Type = R_TestOpType

    override fun str() = toString(name, args)
    override fun strCode(showTupleFieldNames: Boolean) = strCode(name, args)

    override fun equals(other: Any?) = other === this || (other is Rt_TestOpValue && name == other.name && args == other.args)
    override fun hashCode() = Objects.hash(name, args)

    fun toRaw() = RawTestOpValue(name, args)

    companion object {
        private val VALUE_TYPE = Rt_LibValueType.of("TEST_OP")

        fun strCode(name: R_MountName, args: List<Gtv>): String {
            val argsStr = args.joinToString(",") { Rt_GtvValue.toString(it) }
            return "op[$name($argsStr)]"
        }

        fun toString(name: R_MountName, args: List<Gtv>): String {
            val argsStr = args.joinToString(",") { Rt_GtvValue.toString(it) }
            return "$name($argsStr)"
        }
    }
}

class RawTestTxValue(
        ops: List<RawTestOpValue>,
        signers: List<BytesKeyPair>
) {
    val ops = ops.toImmList()
    val signers = signers.toImmList()

    override fun toString() = Rt_TestTxValue.toString(ops)
}

class RawTestOpValue(val name: R_MountName, args: List<Gtv>) {
    val args = args.toImmList()

    override fun toString() = Rt_TestOpValue.toString(name, args)
}

private fun asTestBlock(v: Rt_Value) = v as Rt_TestBlockValue
private fun asTestTx(v: Rt_Value) = v as Rt_TestTxValue
private fun asTestOp(v: Rt_Value) = v as Rt_TestOpValue
