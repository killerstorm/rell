/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmMap

class C_FrameContext private constructor(val fnCtx: C_FunctionContext, proto: C_CallFrameProto) {
    val msgCtx = fnCtx.msgCtx

    private val ownerRootBlkCtx = C_OwnerBlockContext.createRoot(this, true, proto.rootBlockScope)
    val rootBlkCtx: C_BlockContext = ownerRootBlkCtx

    private var callFrameSize = proto.size

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun makeCallFrame(hasGuardBlock: Boolean): C_CallFrame {
        val rootBlock = ownerRootBlkCtx.buildBlock()
        val rFrame = R_CallFrame(callFrameSize, rootBlock.rBlock, hasGuardBlock)
        val proto = C_CallFrameProto(rFrame.size, rootBlock.scope)
        return C_CallFrame(rFrame, proto)
    }

    companion object {
        fun create(fnCtx: C_FunctionContext, proto: C_CallFrameProto = C_CallFrameProto.EMPTY): C_FrameContext {
            return C_FrameContext(fnCtx, proto)
        }
    }
}

class C_CallFrame(val rFrame: R_CallFrame, val proto: C_CallFrameProto)

class C_CallFrameProto(val size: Int, val rootBlockScope: C_BlockScope) {
    companion object { val EMPTY = C_CallFrameProto(0, C_BlockScope.EMPTY) }
}

class C_LocalVar(
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val uid: C_VarUid,
        val ptr: R_VarPtr
) {
    fun toExpr(): R_DestinationExpr = R_VarExpr(type, ptr, name)
}

class C_BlockScope(entries: Map<String, C_BlockScopeEntry>) {
    val entries = entries.toImmMap()

    companion object { val EMPTY = C_BlockScope(mapOf()) }
}

class C_BlockScopeBuilder(
        private val fnCtx: C_FunctionContext,
        private val blockUid: R_FrameBlockUid,
        startOffset: Int,
        proto: C_BlockScope
) {
    private val entries = mutableMapOf<String, C_BlockScopeEntry>()
    private var endOffset = startOffset
    private var build = false

    init {
        for (entry in proto.entries.values) {
            check(entry.offset >= startOffset)
            endOffset = Math.max(endOffset, entry.offset + 1)
        }
        entries.putAll(proto.entries)
    }

    fun endOffset() = endOffset

    fun lookup(name: String, lookupBlockUid: R_FrameBlockUid): C_LocalVar? {
        val local = entries[name]
        return local?.toLocalVar(lookupBlockUid)
    }

    fun addVar(name: S_Name, type: R_Type, mutable: Boolean): C_LocalVar {
        check(!build)

        val nameStr = name.str
        check(nameStr !in entries) { nameStr }

        val ofs = endOffset++
        val varUid = fnCtx.nextVarUid(nameStr)

        val entry = C_BlockScopeEntry(nameStr, type, mutable, ofs, varUid)
        entries[nameStr] = entry

        val res = entry.toLocalVar(blockUid)
        return res
    }

    fun addPlaceholder(): R_VarPtr {
        check(!build)
        val ofs = endOffset++
        return R_VarPtr(C_Constants.AT_PLACEHOLDER, blockUid, ofs)
    }

    fun build(): C_BlockScope {
        check(!build)
        build = true
        return C_BlockScope(entries)
    }
}

class C_BlockScopeEntry(
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val offset: Int,
        val varUid: C_VarUid
) {
    fun toLocalVar(blockUid: R_FrameBlockUid): C_LocalVar {
        val ptr = R_VarPtr(name, blockUid, offset)
        return C_LocalVar(name, type, mutable, varUid, ptr)
    }
}

sealed class C_BlockContext(val frameCtx: C_FrameContext) {
    val fnCtx = frameCtx.fnCtx
    val defCtx = fnCtx.defCtx
    val nsCtx = defCtx.nsCtx

    val nameCtx = C_NameContext.createBlock(this)

    abstract fun isTopLevelBlock(): Boolean
    abstract fun isCollectionAtAllowed(): Boolean
    abstract fun createSubContext(location: String): C_OwnerBlockContext
    abstract fun lookupLocalVar(name: String): C_LocalVar?
    abstract fun lookupPlaceholder(): R_VarPtr?
    abstract fun addLocalVar(name: S_Name, type: R_Type, mutable: Boolean): C_LocalVar
    abstract fun addPlaceholder(type: R_Type): R_VarPtr
}

class C_FrameBlock(val rBlock: R_FrameBlock, val scope: C_BlockScope)

class C_OwnerBlockContext(
        frameCtx: C_FrameContext,
        private val parent: C_OwnerBlockContext?,
        private val blockUid: R_FrameBlockUid,
        private val atAllowed: Boolean,
        protoBlockScope: C_BlockScope
): C_BlockContext(frameCtx) {
    private val startOffset: Int = parent?.scopeBuilder?.endOffset() ?: 0
    private val scopeBuilder: C_BlockScopeBuilder = C_BlockScopeBuilder(fnCtx, blockUid, startOffset, protoBlockScope)
    private var placeholder: R_VarPtr? = null
    private var build = false

    override fun isTopLevelBlock() = parent?.parent == null
    override fun isCollectionAtAllowed() = atAllowed

    override fun createSubContext(location: String): C_OwnerBlockContext {
        check(!build)
        val blockUid = fnCtx.nextBlockUid(location)
        return C_OwnerBlockContext(frameCtx, this, blockUid, atAllowed, C_BlockScope.EMPTY)
    }

    override fun lookupLocalVar(name: String): C_LocalVar? {
        val res = findValue { it.scopeBuilder.lookup(name, blockUid) }
        return res
    }

    override fun lookupPlaceholder(): R_VarPtr? {
        val ptr = findValue { it.placeholder }
        ptr ?: return null
        return R_VarPtr(ptr.name, blockUid, ptr.offset)
    }

    override fun addLocalVar(name: S_Name, type: R_Type, mutable: Boolean): C_LocalVar {
        val nameStr = name.str
        if (lookupLocalVar(nameStr) != null) {
            throw C_Error(name.pos, "var_dupname:$nameStr", "Duplicate variable: '$nameStr'")
        }
        return scopeBuilder.addVar(name, type, mutable)
    }

    override fun addPlaceholder(type: R_Type): R_VarPtr {
        val varPtr = scopeBuilder.addPlaceholder()
        placeholder = varPtr
        return varPtr
    }

    private fun <T> findValue(getter: (C_OwnerBlockContext) -> T?): T? {
        var ctx: C_OwnerBlockContext? = this
        while (ctx != null) {
            val value = getter(ctx)
            if (value != null) {
                return value
            }
            ctx = ctx.parent
        }
        return null
    }

    fun buildBlock(): C_FrameBlock {
        check(!build)
        build = true
        val scope = scopeBuilder.build()
        val endOffset = scopeBuilder.endOffset()
        frameCtx.adjustCallFrameSize(endOffset + 1)
        val size = endOffset - startOffset
        val rBlock = R_FrameBlock(parent?.blockUid, blockUid, startOffset, size)
        return C_FrameBlock(rBlock, scope)
    }

    companion object {
        fun createRoot(frameCtx: C_FrameContext, atAllowed: Boolean, protoScope: C_BlockScope): C_OwnerBlockContext {
            val fnCtx = frameCtx.fnCtx
            val name = fnCtx.fnUid.name
            val blockUid = fnCtx.nextBlockUid("root:$name")
            return C_OwnerBlockContext(frameCtx, null, blockUid, atAllowed, protoScope)
        }
    }
}
