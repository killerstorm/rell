/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_AtEntityExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_LocalVarExpr
import net.postchain.rell.model.*
import net.postchain.rell.utils.mutableMultimapOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class C_LocalVarRef(val target: C_LocalVar, val ptr: R_VarPtr) {
    fun toRExpr(): R_DestinationExpr = R_VarExpr(target.type, ptr, target.name)
    fun toDbExpr(): Db_Expr = Db_InterpretedExpr(toRExpr())

    fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        val nulled = ctx.factsCtx.nulled(target.uid)
        val smartType = if (target.type is R_NullableType && nulled == C_VarFact.NO) target.type.valueType else null
        return V_LocalVarExpr(ctx, pos, this, nulled, smartType)
    }
}

class C_LocalVar(
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val offset: Int,
        val uid: C_VarUid,
        val atExprId: R_AtExprId?
) {
    fun toRef(blockUid: R_FrameBlockUid): C_LocalVarRef {
        val ptr = R_VarPtr(name, blockUid, offset)
        return C_LocalVarRef(this, ptr)
    }
}

class C_FrameContext private constructor(val fnCtx: C_FunctionContext, proto: C_CallFrameProto) {
    val msgCtx = fnCtx.msgCtx
    val appCtx = fnCtx.appCtx

    private val ownerRootBlkCtx = C_OwnerBlockContext.createRoot(this, proto.rootBlockScope)
    val rootBlkCtx: C_BlockContext = ownerRootBlkCtx

    private var callFrameSize = proto.size

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun makeCallFrame(hasGuardBlock: Boolean): C_CallFrame {
        val rootBlock = ownerRootBlkCtx.buildBlock()
        val rFrame = R_CallFrame(fnCtx.defCtx.defId, callFrameSize, rootBlock.rBlock, hasGuardBlock)
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

class C_BlockScope(variables: Map<String, C_LocalVar>) {
    val localVars = variables.toImmMap()

    companion object { val EMPTY = C_BlockScope(mapOf()) }
}

sealed class C_BlockEntry {
    abstract fun toLocalVarOpt(): C_LocalVar?
    abstract fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr
}

class C_BlockEntry_Var(private val localVar: C_LocalVar): C_BlockEntry() {
    override fun toLocalVarOpt() = localVar

    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        val varRef = localVar.toRef(ctx.blkCtx.blockUid)
        return varRef.compile(ctx, pos)
    }
}

class C_BlockEntry_AtEntity(val atEntity: C_AtEntity): C_BlockEntry() {
    override fun toLocalVarOpt() = null

    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        return V_AtEntityExpr(ctx, pos, atEntity)
    }
}

class C_BlockScopeBuilder(
        private val fnCtx: C_FunctionContext,
        private val blockUid: R_FrameBlockUid,
        startOffset: Int,
        proto: C_BlockScope
) {
    private val explicitEntries = mutableMapOf<String, C_BlockEntry>()
    private val implicitEntries = mutableMultimapOf<String, C_BlockEntry>()
    private var endOffset = startOffset
    private var build = false

    init {
        for (entry in proto.localVars.values) {
            val offset = entry.offset
            check(offset >= startOffset)
            endOffset = Math.max(endOffset, offset + 1)
        }
        explicitEntries.putAll(proto.localVars.mapValues { C_BlockEntry_Var(it.value) })
    }

    fun endOffset() = endOffset

    fun lookupVar(name: String): C_LocalVar? {
        val entry = explicitEntries[name]
        return entry?.toLocalVarOpt()
    }

    fun lookupExplicit(name: String): C_BlockEntry? {
        return explicitEntries[name]
    }

    fun lookupImplicit(name: String): List<C_BlockEntry> {
        return implicitEntries.get(name).toImmList()
    }

    fun newVar(name: String, type: R_Type, mutable: Boolean, atExprId: R_AtExprId?): C_LocalVar {
        check(!build)
        val ofs = endOffset++
        val varUid = fnCtx.nextVarUid(name)
        return C_LocalVar(name, type, mutable, ofs, varUid, atExprId)
    }

    fun addEntry(name: String, explicit: Boolean, entry: C_BlockEntry) {
        check(!build)
        if (explicit) {
            if (name !in explicitEntries) {
                explicitEntries[name] = entry
            }
        } else {
            implicitEntries.put(name, entry)
        }
    }

    fun build(): C_BlockScope {
        check(!build)
        build = true
        val variables = explicitEntries
                .map { it.key to it.value.toLocalVarOpt() }
                .filter { it.second != null }
                .map { it.first to it.second!! }
                .toMap()
        return C_BlockScope(variables)
    }
}

sealed class C_BlockContext(val frameCtx: C_FrameContext, val blockUid: R_FrameBlockUid) {
    val appCtx = frameCtx.appCtx
    val fnCtx = frameCtx.fnCtx
    val defCtx = fnCtx.defCtx
    val nsCtx = defCtx.nsCtx

    abstract fun isTopLevelBlock(): Boolean
    abstract fun createSubContext(location: String, atFrom: C_AtFrom? = null): C_OwnerBlockContext

    abstract fun lookupEntry(name: String): C_BlockEntryResolution?
    abstract fun lookupLocalVar(name: String): C_LocalVarRef?
    abstract fun lookupAtPlaceholder(): C_BlockEntryResolution?
    abstract fun lookupAtAttributesByName(name: String, outer: Boolean): List<C_ExprContextAttr>
    abstract fun lookupAtAttributesByType(type: R_Type): List<C_ExprContextAttr>

    abstract fun addEntry(pos: S_Pos, name: String, explicit: Boolean, entry: C_BlockEntry)
    abstract fun addLocalVar(name: S_Name, type: R_Type, mutable: Boolean, atExprId: R_AtExprId?): C_LocalVarRef
    abstract fun addAtPlaceholder(entry: C_BlockEntry)
    abstract fun newLocalVar(metaName: String, type: R_Type, mutable: Boolean, atExprId: R_AtExprId?): C_LocalVar
}

class C_FrameBlock(val rBlock: R_FrameBlock, val scope: C_BlockScope)

sealed class C_BlockEntryResolution {
    abstract fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr
}

private class C_BlockEntryResolution_Normal(private val entry: C_BlockEntry): C_BlockEntryResolution() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        return entry.compile(ctx, pos)
    }
}

private class C_BlockEntryResolution_OuterPlaceholder(private val entry: C_BlockEntry): C_BlockEntryResolution() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        ctx.msgCtx.error(pos, "at_expr:placeholder:belongs_to_outer",
                "Cannot use a placeholder to access an outer at-expression; use explicit alias")
        return entry.compile(ctx, pos)
    }
}

private class C_BlockEntryResolution_Ambiguous(private val name: String, private val entry: C_BlockEntry): C_BlockEntryResolution() {
    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        ctx.msgCtx.error(pos, "name:ambiguous:$name", "Name '$name' is ambiguous")
        return entry.compile(ctx, pos)
    }
}

class C_OwnerBlockContext(
        frameCtx: C_FrameContext,
        blockUid: R_FrameBlockUid,
        private val parent: C_OwnerBlockContext?,
        atFrom: C_AtFrom?,
        protoBlockScope: C_BlockScope
): C_BlockContext(frameCtx, blockUid) {
    private val startOffset: Int = parent?.scopeBuilder?.endOffset() ?: 0
    private val scopeBuilder: C_BlockScopeBuilder = C_BlockScopeBuilder(fnCtx, blockUid, startOffset, protoBlockScope)
    private val atFromBlock: C_AtFromBlock? = if (atFrom == null) parent?.atFromBlock else C_AtFromBlock(parent?.atFromBlock, atFrom)
    private val atPlaceholders = mutableListOf<C_BlockEntry>()
    private var build = false

    override fun isTopLevelBlock() = parent?.parent == null

    override fun createSubContext(location: String, atFrom: C_AtFrom?): C_OwnerBlockContext {
        check(!build) { "Block has been built: $blockUid" }
        val blockUid = fnCtx.nextBlockUid(location)
        return C_OwnerBlockContext(frameCtx, blockUid, this, atFrom, C_BlockScope.EMPTY)
    }

    override fun lookupLocalVar(name: String): C_LocalVarRef? {
        val localVar = findValue { it.scopeBuilder.lookupVar(name) }
        return localVar?.toRef(blockUid)
    }

    override fun lookupEntry(name: String): C_BlockEntryResolution? {
        val explicit = findValue { it.scopeBuilder.lookupExplicit(name) }
        val implicit = findAllValues { it.scopeBuilder.lookupImplicit(name) }
        val entries = listOfNotNull(explicit) + implicit

        return if (entries.isEmpty()) null else {
            val entry = entries.first()
            return if (entries.size == 1) {
                C_BlockEntryResolution_Normal(entry)
            } else {
                C_BlockEntryResolution_Ambiguous(name, entry)
            }
        }
    }

    override fun lookupAtPlaceholder(): C_BlockEntryResolution? {
        val entries = findAllValues { blkCtx -> blkCtx.atPlaceholders.map { it to blkCtx } }
        return if (entries.isEmpty()) null else {
            val (entry, blkCtx) = entries.first()
            if (entries.size == 1 && blkCtx.atFromBlock === atFromBlock) {
                C_BlockEntryResolution_Normal(entry)
            } else if (entries.size == 1) {
                C_BlockEntryResolution_OuterPlaceholder(entry)
            } else {
                C_BlockEntryResolution_Ambiguous(C_Constants.AT_PLACEHOLDER, entry)
            }
        }
    }

    override fun lookupAtAttributesByName(name: String, outer: Boolean): List<C_ExprContextAttr> {
        var block = atFromBlock

        val attrs = mutableListOf<C_ExprContextAttr>()

        while (block != null) {
            val fromAttrs = block.from.findAttributesByName(name)
            attrs.addAll(fromAttrs.map { C_ExprContextAttr(it, block !== atFromBlock) })

            block = if (!outer) {
                null
            } else when (appCtx.globalCtx.compilerOptions.atAttrShadowing) {
                C_AtAttrShadowing.NONE -> block.parent
                C_AtAttrShadowing.FULL -> if (attrs.isNotEmpty()) null else block.parent
                C_AtAttrShadowing.PARTIAL -> if (block.from.innerAtCtx.parent == null && attrs.isNotEmpty()) null else block.parent
            }
        }

        return attrs.toImmList()
    }

    override fun lookupAtAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        // Not looking in outer contexts, because matching by type is used in where-part, only direct at-expr is considered.
        val fromAttrs = atFromBlock?.from?.findAttributesByType(type) ?: listOf()
        return fromAttrs.map { C_ExprContextAttr(it, false) }
    }

    override fun addEntry(pos: S_Pos, name: String, explicit: Boolean, entry: C_BlockEntry) {
        if (explicit && !checkNameConflict(pos, name)) {
            return
        }
        scopeBuilder.addEntry(name, explicit, entry)
    }

    override fun addLocalVar(name: S_Name, type: R_Type, mutable: Boolean, atExprId: R_AtExprId?): C_LocalVarRef {
        val localVar = scopeBuilder.newVar(name.str, type, mutable, atExprId)
        if (checkNameConflict(name.pos, name.str)) {
            scopeBuilder.addEntry(name.str, true, C_BlockEntry_Var(localVar))
        }
        return localVar.toRef(blockUid)
    }

    private fun checkNameConflict(pos: S_Pos, name: String): Boolean {
        val entry = findValue { it.scopeBuilder.lookupExplicit(name) }
        if (entry != null) {
            frameCtx.msgCtx.error(pos, "block:name_conflict:$name", "Name conflict: '$name' already exists")
        }
        return entry == null
    }

    override fun addAtPlaceholder(entry: C_BlockEntry) {
        atPlaceholders.add(entry)
    }

    override fun newLocalVar(metaName: String, type: R_Type, mutable: Boolean, atExprId: R_AtExprId?): C_LocalVar {
        return scopeBuilder.newVar(metaName, type, mutable, atExprId)
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

    private fun <T> findAllValues(getter: (C_OwnerBlockContext) -> List<T>): List<T> {
        var ctx: C_OwnerBlockContext? = this
        val res = mutableListOf<T>()
        while (ctx != null) {
            val values = getter(ctx)
            res.addAll(values)
            ctx = ctx.parent
        }
        return res.toImmList()
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

    private class C_AtFromBlock(val parent: C_AtFromBlock?, val from: C_AtFrom)

    companion object {
        fun createRoot(frameCtx: C_FrameContext, protoScope: C_BlockScope): C_OwnerBlockContext {
            val fnCtx = frameCtx.fnCtx
            val name = fnCtx.fnUid.name
            val blockUid = fnCtx.nextBlockUid("root:$name")
            return C_OwnerBlockContext(frameCtx, blockUid, null, null, protoScope)
        }
    }
}

class C_LambdaBlock(
        val rLambda: R_LambdaBlock,
        private val localVar: C_LocalVar,
        val blockUid: R_FrameBlockUid
) {
    fun compileVarRExpr(blockUid: R_FrameBlockUid = this.blockUid): R_Expr {
        val varRef = localVar.toRef(blockUid)
        return varRef.toRExpr()
    }

    fun compileVarDbExpr(blockUid: R_FrameBlockUid = this.blockUid): Db_Expr {
        val rVarExpr = compileVarRExpr(blockUid)
        return Db_InterpretedExpr(rVarExpr)
    }

    companion object {
        fun builder(ctx: C_ExprContext, varType: R_Type) = C_LambdaBlockBuilder(ctx, varType)
    }
}

class C_LambdaBlockBuilder(ctx: C_ExprContext, private val varType: R_Type) {
    val innerBlkCtx = ctx.blkCtx.createSubContext("<lambda>")
    val innerExprCtx = ctx.update(blkCtx = innerBlkCtx)
    val localVar = innerBlkCtx.newLocalVar("<lambda>", varType, false, null)

    fun build(): C_LambdaBlock {
        val cBlock = innerBlkCtx.buildBlock()
        val varRef = localVar.toRef(innerBlkCtx.blockUid)
        val rLambda = R_LambdaBlock(cBlock.rBlock, varRef.ptr, varType)
        return C_LambdaBlock(rLambda, localVar, innerBlkCtx.blockUid)
    }
}
