/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.test

import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.common.BlockchainRid
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_List
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_MirrorStructOperation
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.*
import net.postchain.rell.model.lib.R_SysFn_Struct
import net.postchain.rell.module.GtvRtConversion
import net.postchain.rell.module.GtvRtConversion_None
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.runtime.utils.toGtv
import net.postchain.rell.utils.BytesKeyPair
import net.postchain.rell.utils.toImmList
import java.util.*

object C_Lib_Rell_Test {
    const val MODULE = "rell.test"

    const val BLOCK_SNAME = "block"
    const val TX_SNAME = "tx"
    const val OP_SNAME = "op"

    const val BLOCK_TYPE_QNAME = "$MODULE.$BLOCK_SNAME"
    const val TX_TYPE_QNAME = "$MODULE.$TX_SNAME"
    const val OP_TYPE_QNAME = "$MODULE.$OP_SNAME"

    private val NAMESPACE_FNS: C_GlobalFuncTable = let {
        val b = C_GlobalFuncBuilder("rell.test")
        R_Lib_Block.bindGlobal(b)
        R_Lib_Tx.bindGlobal(b)
        R_Lib_Op.bindGlobal(b)
        R_Lib_Nop.bindGlobal(b)
        C_Lib_Rell_Test_Assert.FUNCTIONS.addTo(b)
        b.build()
    }

    val NAMESPACE = C_LibUtils.makeNsEx(
            functions = NAMESPACE_FNS,
            types = mapOf(
                    BLOCK_SNAME to R_TestBlockType,
                    TX_SNAME to R_TestTxType,
                    OP_SNAME to R_TestOpType
            ),
            parts = listOf(C_Lib_Rell_Test_KeyPairs.NAMESPACE)
    )
}

private object R_TestBlockType: R_LibType(C_Lib_Rell_Test.BLOCK_TYPE_QNAME) {
    override fun isReference() = true
    override fun isDirectMutable() = true
    override fun isDirectPure() = false
    override fun createGtvConversion() = GtvRtConversion_None
    override fun strCode(): String = name
    override fun toMetaGtv() = name.toGtv()

    override fun getMemberFunctions(): C_MemberFuncTable {
        val b = C_LibUtils.typeMemFuncBuilder(this)
        R_Lib_Block.bindMember(b)
        return b.build()
    }
}

private object R_TestTxType: R_LibType(C_Lib_Rell_Test.TX_TYPE_QNAME) {
    override fun isReference() = true
    override fun isDirectMutable() = true
    override fun isDirectPure() = false
    override fun createGtvConversion() = GtvRtConversion_None
    override fun strCode(): String = name
    override fun toMetaGtv() = name.toGtv()

    override fun getMemberFunctions(): C_MemberFuncTable {
        val b = C_LibUtils.typeMemFuncBuilder(this)
        R_Lib_Tx.bindMember(b)
        return b.build()
    }
}

object R_TestOpType: R_LibType(C_Lib_Rell_Test.OP_TYPE_QNAME) {
    override fun isReference() = true
    override fun isDirectPure() = false
    override fun createGtvConversion(): GtvRtConversion = GtvRtConversion_None
    override fun strCode(): String = name
    override fun toMetaGtv() = name.toGtv()

    override fun getMemberFunctions(): C_MemberFuncTable {
        val b = C_LibUtils.typeMemFuncBuilder(this)
        R_Lib_Op.bindMember(b)
        return b.build()
    }
}

private class BlockCommonFunctions(
        private val typeQName: String,
        private val blockGetter: (ctx: Rt_CallContext, self: Rt_Value) -> Rt_TestBlockValue
) {
    fun bind(b: C_MemberFuncBuilder) {
        val run = "run"
        val runFail = "run_must_fail"
        b.add(run, R_UnitType, listOf(), Run(true, run))
        b.add(runFail, R_UnitType, listOf(), Run(false, runFail))
    }

    private inner class Run(val positive: Boolean, val name: String): R_SysFunctionEx_1() {
        private val fnName = "$typeQName.$name"

        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val block = blockGetter(ctx, arg)

            if (!ctx.appCtx.repl && !ctx.appCtx.test) {
                throw Rt_Error("fn:$fnName:no_repl_test", "Block can be executed only in REPL or test")
            }

            try {
                try {
                    UnitTestBlockRunner.runBlock(ctx, block)
                } catch (e: Rt_BaseError) {
                    throw e
                } catch (e: Throwable) {
                    throw Rt_Error("fn:$fnName:fail:${e.javaClass.canonicalName}", "Block execution failed: $e")
                }
            } catch (e: Throwable) {
                if (positive) throw e else return Rt_UnitValue
            }

            if (!positive) {
                throw Rt_Error("fn:$fnName:nofail", "Transaction did not fail")
            }

            return Rt_UnitValue
        }
    }
}

private class TxCommonFunctions(private val txGetter: (ctx: Rt_CallContext, self: Rt_Value) -> Rt_TestTxValue) {
    fun bind(b: C_MemberFuncBuilder) {
        b.add("sign", R_TestTxType, listOf(R_ListType(C_Lib_Rell_Test_KeyPairs.KEYPAIR_TYPE)), sign_listOfKeyPair)
        b.add("sign", R_TestTxType, listOf(R_ListType(R_ByteArrayType)), sign_listOfByteArray)
        b.addOneMany("sign", R_TestTxType, listOf(), R_ByteArrayType, sign_byteArrays)
        b.addOneMany("sign", R_TestTxType, listOf(), C_Lib_Rell_Test_KeyPairs.KEYPAIR_TYPE, sign_keyPairs)
    }

    private val sign_listOfKeyPair = object: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return signByKeyPairs(ctx, arg1, arg2.asList())
        }
    }

    private val sign_listOfByteArray = object: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return signByByteArrays(ctx, arg1, arg2.asList())
        }
    }

    private val sign_keyPairs = object: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return signByKeyPairs(ctx, args[0], args.subList(1, args.size))
        }
    }

    private val sign_byteArrays = object: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size >= 1)
            return signByByteArrays(ctx, args[0], args.subList(1, args.size))
        }
    }

    private fun signByKeyPairs(ctx: Rt_CallContext, self: Rt_Value, keyPairs: List<Rt_Value>): Rt_Value {
        val tx = txGetter(ctx, self)
        for (keyPairValue in keyPairs) {
            val keyPair = C_Lib_Rell_Test_KeyPairs.structToKeyPair(keyPairValue)
            tx.sign(keyPair)
        }
        return tx
    }

    private fun signByByteArrays(ctx: Rt_CallContext, self: Rt_Value, byteArrays: List<Rt_Value>): Rt_Value {
        val tx = txGetter(ctx, self)
        for (v in byteArrays) {
            val bs = v.asByteArray()
            val keyPair = privKeyToKeyPair(bs)
            tx.sign(keyPair)
        }
        return tx
    }

    private fun privKeyToKeyPair(priv: ByteArray): BytesKeyPair {
        val privSize = 32
        Rt_Utils.check(priv.size == privSize) { "tx.sign:priv_key_size:$privSize:${priv.size}" to
                "Wrong size of private key: ${priv.size} instead of $privSize"
        }

        val pub = secp256k1_derivePubKey(priv)
        val pubSize = 33
        Rt_Utils.check(pub.size == pubSize) { "tx.sign:pub_key_size:$pubSize:${pub.size}" to
                "Wrong size of calculated public key: ${pub.size} instead of $pubSize"
        }

        return BytesKeyPair(priv, pub)
    }
}

private object R_Lib_Block {
    private val common = BlockCommonFunctions(C_Lib_Rell_Test.BLOCK_TYPE_QNAME) { _, v -> asTestBlock(v) }

    fun bindGlobal(b: C_GlobalFuncBuilder) {
        val name = C_Lib_Rell_Test.BLOCK_SNAME
        b.add(name, R_TestBlockType, listOf(R_ListType(R_TestTxType)), New_ListOfTxs)
        b.addZeroMany(name, R_TestBlockType, listOf(), R_TestTxType, New_Txs)
        b.add(name, R_TestBlockType, listOf(R_ListType(R_TestOpType)), New_ListOfOps)
        b.addOneMany(name, R_TestBlockType, listOf(), R_TestOpType, New_Ops)
    }

    fun bindMember(b: C_MemberFuncBuilder) {
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

    private object New_ListOfOps: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value) = newOps(ctx, arg.asList())
    }

    private object New_Ops: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>) = newOps(ctx, args)
    }

    private fun newOps(ctx: Rt_CallContext, ops: List<Rt_Value>): Rt_Value {
        val rawOps = ops.map { asTestOp(it).toRaw() }
        val tx = RawTestTxValue(ctx.chainCtx.blockchainRid, rawOps, listOf())
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

    private object Tx_ListOfOps: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            return addOps(ctx, arg1, arg2.asList())
        }
    }

    private object Tx_Ops: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.isNotEmpty())
            return addOps(ctx, args[0], args.subList(1, args.size))
        }
    }

    private fun addOps(ctx: Rt_CallContext, self: Rt_Value, ops: List<Rt_Value>): Rt_Value {
        val block = asTestBlock(self)
        val rawOps = ops.map { asTestOp(it).toRaw() }
        val tx = RawTestTxValue(ctx.chainCtx.blockchainRid, rawOps, listOf())
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

private object R_Lib_Tx {
    private val block = BlockCommonFunctions(C_Lib_Rell_Test.TX_TYPE_QNAME) { _, v ->
        Rt_TestBlockValue(listOf(asTestTx(v).toRaw()))
    }

    private val tx = TxCommonFunctions { _, v -> asTestTx(v) }

    fun bindGlobal(b: C_GlobalFuncBuilder) {
        val name = C_Lib_Rell_Test.TX_SNAME
        b.add(name, R_TestTxType, listOf(R_ListType(R_TestOpType)), New_ListOfOps)
        b.addZeroMany(name, R_TestTxType, listOf(), R_TestOpType, New_Ops)
        b.addEx(name, R_TestTxType, listOf(C_ArgTypeMatcher_List(C_ArgTypeMatcher_MirrorStructOperation)), New_ListOfStructs)
        b.addOneMany(name, R_TestTxType, listOf(), C_ArgTypeMatcher_MirrorStructOperation, New_Structs)
    }

    fun bindMember(b: C_MemberFuncBuilder) {
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

    private object New_Ops: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            val ops = args.map { asTestOp(it).toRaw() }
            return newTx(ctx, ops)
        }
    }

    private object New_ListOfOps: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val ops = arg.asList().map { asTestOp(it).toRaw() }
            return newTx(ctx, ops)
        }
    }

    private object New_Structs: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            val ops = args.map { structToOp(it) }
            return newTx(ctx, ops)
        }
    }

    private object New_ListOfStructs: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val list = arg.asList()
            val ops = list.map { structToOp(it) }
            return newTx(ctx, ops)
        }
    }

    private fun newTx(ctx: Rt_CallContext, ops: List<RawTestOpValue>): Rt_Value {
        val blockchainRid = ctx.chainCtx.blockchainRid
        return Rt_TestTxValue(blockchainRid, ops, listOf())
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
            val op = R_Lib_Nop.callNoArgs(ctx)
            tx.addOp(op.toRaw())
            return tx
        }
    }

    private object Nop_OneArg: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val tx = asTestTx(arg1)
            val op = R_Lib_Nop.callOneArg(arg2)
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

    private fun structToOp(v: Rt_Value): RawTestOpValue = R_SysFn_Struct.ToTestOp.call(v).toRaw()
}

private object R_Lib_Op {
    private val block = BlockCommonFunctions(C_Lib_Rell_Test.OP_TYPE_QNAME) { ctx, v ->
        Rt_TestBlockValue(listOf(selfToTx(ctx, v).toRaw()))
    }

    private val tx = TxCommonFunctions(this::selfToTx)

    private fun selfToTx(ctx: Rt_CallContext, self: Rt_Value): Rt_TestTxValue {
        return Rt_TestTxValue(ctx.chainCtx.blockchainRid, listOf(asTestOp(self).toRaw()), listOf())
    }

    fun bindGlobal(b: C_GlobalFuncBuilder) {
        val name = C_Lib_Rell_Test.OP_SNAME
        b.add(name, R_TestOpType, listOf(R_TextType, R_ListType(R_GtvType)), New_Text_ListOfGtv)
        b.addZeroMany(name, R_TestOpType, listOf(R_TextType), R_GtvType, New_Text_Gtvs)
    }

    fun bindMember(b: C_MemberFuncBuilder) {
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
            "${C_Lib_Rell_Test.OP_TYPE_QNAME}:bad_name:$nameStr" to "Bad operation name: '$nameStr'"
        }
        name!!

        return Rt_TestOpValue(name, args)
    }

    private object ToTx: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val op = asTestOp(arg).toRaw()
            return Rt_TestTxValue(ctx.chainCtx.blockchainRid, listOf(op), listOf())
        }
    }
}

private object R_Lib_Nop {
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
        return "${C_Lib_Rell_Test.BLOCK_TYPE_QNAME}[$txsStr]"
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
        private val blockchainRid: BlockchainRid,
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

    fun copy() = Rt_TestTxValue(blockchainRid, ops, signers)

    fun toRaw() = RawTestTxValue(blockchainRid, ops, signers)

    companion object {
        private val VALUE_TYPE = Rt_LibValueType.of("TEST_TX")

        fun strCode(ops: List<RawTestOpValue>, signers: List<BytesKeyPair>): String {
            val opsList = ops.map { Rt_TestOpValue.strCode(it.name, it.args) }
            val signersList = signers.map { it.pub.toHex().substring(0, 6).toLowerCase()}
            val innerStr = (opsList + signersList).joinToString(",")
            return "${C_Lib_Rell_Test.TX_TYPE_QNAME}[$innerStr]"
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
        val blockchainRid: BlockchainRid,
        ops: List<RawTestOpValue>,
        signers: List<BytesKeyPair>
) {
    val ops = ops.toImmList()
    val signers = signers.toImmList()

    override fun toString() = Rt_TestTxValue.toString(ops)

    fun toPostchainGtv(): Gtv {
        val bodyGtv = GtvFactory.gtv(
                blockchainRid.toGtv(),
                ops.map { it.toPostchainGtv() }.toGtv(),
                signers.map { it.pub.toGtv() }.toGtv()
        )
        //val signatures = tx.signatures.map { it.toGtv() }.toGtv()
        val signaturesGtv = listOf<Gtv>().toGtv()
        return GtvFactory.gtv(bodyGtv, signaturesGtv)
    }
}

class RawTestOpValue(val name: R_MountName, args: List<Gtv>) {
    val args = args.toImmList()

    override fun toString() = Rt_TestOpValue.toString(name, args)

    fun toPostchainGtv(): Gtv {
        val nameGtv = name.str().toGtv()
        val argsGtv = args.toGtv()
        return GtvFactory.gtv(nameGtv, argsGtv)
    }
}

private fun asTestBlock(v: Rt_Value) = v as Rt_TestBlockValue
private fun asTestTx(v: Rt_Value) = v as Rt_TestTxValue
private fun asTestOp(v: Rt_Value) = v as Rt_TestOpValue
