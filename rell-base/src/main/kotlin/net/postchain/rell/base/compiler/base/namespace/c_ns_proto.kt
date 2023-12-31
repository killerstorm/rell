/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.namespace

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.def.*
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.lib.C_LibTypeDef
import net.postchain.rell.base.compiler.base.lib.C_TypeDef
import net.postchain.rell.base.compiler.base.module.C_ModuleDefsBuilder
import net.postchain.rell.base.compiler.base.module.C_ModuleKey
import net.postchain.rell.base.compiler.base.utils.*
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.lib.type.V_ObjectExpr
import net.postchain.rell.base.model.*
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList

class C_NsEntry(val name: R_Name, val item: C_NamespaceItem) {
    fun addToNamespace(nsBuilder: C_NamespaceBuilder) {
        nsBuilder.add(name, item)
    }

    companion object {
        fun createNamespace(entries: List<C_NsEntry>): C_Namespace {
            val nsBuilder = C_NamespaceBuilder()
            for (entry in entries) {
                entry.addToNamespace(nsBuilder)
            }
            return nsBuilder.build()
        }
    }
}

class C_NamespaceMemberBase(
    val defName: C_DefinitionName,
    val ideInfo: C_IdeSymbolInfo,
    val deprecated: C_Deprecated?,
)

enum class C_NamespaceMemberTag {
    NAMESPACE,
    TYPE,
    VALUE,
    CALLABLE,
    OBJECT,
    ;

    val list = immListOf(this)
    val notList: List<C_NamespaceMemberTag> by lazy { values().filter { it != this }.toImmList() }

    companion object {
        val MIRRORABLE = immListOf(TYPE, CALLABLE, OBJECT)
    }
}

sealed class C_NamespaceMember(base: C_NamespaceMemberBase) {
    val defName = base.defName
    val ideInfo = base.ideInfo
    val deprecation = if (base.deprecated == null) null else C_DefDeprecation(defName, base.deprecated)

    abstract fun declarationType(): C_DeclarationType

    protected abstract fun hasTag(tag: C_NamespaceMemberTag): Boolean

    fun isCallable() = hasTag(C_NamespaceMemberTag.CALLABLE)

    open fun getNamespaceOpt(): C_Namespace? = null
    open fun getTypeOpt(): C_TypeDef? = null
    open fun getEntityOpt(): R_EntityDefinition? = null
    open fun getFunctionOpt(): C_GlobalFunction? = null // getFunctionOpt() is not always non-null when matching CALLBLE
    open fun getObjectOpt(): R_ObjectDefinition? = null
    open fun getOperationOpt(): R_OperationDefinition? = null

    open fun addToDefs(b: C_ModuleDefsBuilder) {
    }

    fun hasTag(tags: List<C_NamespaceMemberTag>): Boolean {
        return when {
            tags.isEmpty() -> true
            else -> tags.any { hasTag(it) }
        }
    }

    open fun getDocMember(name: String): DocDefinition? = null

    abstract fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr
}

private class C_NamespaceMember_Property(
    base: C_NamespaceMemberBase,
    private val prop: C_NamespaceProperty,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.PROPERTY
    override fun hasTag(tag: C_NamespaceMemberTag) = tag == C_NamespaceMemberTag.VALUE

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        val propCtx = C_NamespacePropertyContext(ctx)
        val vExpr = prop.toExpr(propCtx, qName)
        return C_ValueExpr(vExpr)
    }
}

class C_NamespaceMember_Namespace(
    base: C_NamespaceMemberBase,
    val ns: C_Namespace,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.NAMESPACE

    override fun hasTag(tag: C_NamespaceMemberTag): Boolean {
        return tag == C_NamespaceMemberTag.NAMESPACE || tag == C_NamespaceMemberTag.VALUE
    }

    override fun getNamespaceOpt() = ns

    override fun toExpr(
        ctx: C_ExprContext,
        qName: C_QualifiedName,
        ideInfoPtr: C_UniqueDefaultIdeInfoPtr,
    ): C_Expr {
        return C_NamespaceExpr(qName, ns, defName)
    }

    override fun getDocMember(name: String): DocDefinition? {
        val elem = ns.getElement(R_Name.of(name))
        return elem?.item
    }

    private class C_NamespaceExpr(
        private val qName: C_QualifiedName,
        private val ns: C_Namespace,
        private val defName: C_DefinitionName,
    ): C_NoValueExpr() {
        override fun startPos() = qName.pos

        override fun member(ctx: C_ExprContext, memberNameHand: C_NameHandle, exprHint: C_ExprHint): C_Expr {
            val memberName = memberNameHand.name
            val entry = ns.getEntry(memberName.rName)

            if (entry == null) {
                val errCode = "[${defName.appLevelName}]:$memberName"
                C_Errors.errUnknownName(ctx.msgCtx, qName.pos, errCode, qName.add(memberName).str())
                memberNameHand.setIdeInfo(C_IdeSymbolInfo.UNKNOWN)
                return C_ExprUtils.errorExpr(ctx, memberName.pos)
            }

            val tags = exprHint.memberTags()
            val elem = entry.element(tags)

            val qFullName = qName.add(memberName)
            return elem.toExprMember(ctx, qFullName, memberNameHand)
        }

        override fun errKindName() = "namespace" to defName.qualifiedName.str()
    }
}

private class C_NamespaceMember_Type(
    base: C_NamespaceMemberBase,
    private val typeDef: C_LibTypeDef,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.TYPE

    override fun hasTag(tag: C_NamespaceMemberTag): Boolean {
        return tag == C_NamespaceMemberTag.TYPE
                || tag == C_NamespaceMemberTag.VALUE
                || tag == C_NamespaceMemberTag.CALLABLE && typeDef.hasConstructor()
    }

    override fun getTypeOpt() = typeDef

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        return typeDef.compileExprLibType(ctx.msgCtx, qName.pos, ideInfoPtr.move())
    }
}

private sealed class C_NamespaceMember_Entity(
    base: C_NamespaceMemberBase,
    protected val entity: R_EntityDefinition,
): C_NamespaceMember(base) {
    private val typeDef: C_TypeDef = C_TypeDef.makeRType(entity.type)

    final override fun declarationType() = C_DeclarationType.ENTITY

    final override fun hasTag(tag: C_NamespaceMemberTag): Boolean {
        return tag == C_NamespaceMemberTag.TYPE || tag == C_NamespaceMemberTag.VALUE
    }

    final override fun getTypeOpt() = typeDef
    final override fun getEntityOpt() = entity

    final override fun toExpr(
        ctx: C_ExprContext,
        qName: C_QualifiedName,
        ideInfoPtr: C_UniqueDefaultIdeInfoPtr,
    ): C_Expr {
        return typeDef.compileExpr(ctx.msgCtx, qName.pos)
    }

    final override fun getDocMember(name: String) = entity.getDocMember(name)
}

private class C_NamespaceMember_SysEntity(
    base: C_NamespaceMemberBase,
    entity: R_EntityDefinition,
): C_NamespaceMember_Entity(base, entity)

private class C_NamespaceMember_UserEntity(
    base: C_NamespaceMemberBase,
    entity: R_EntityDefinition,
    private val addToModule: Boolean,
): C_NamespaceMember_Entity(base, entity) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        if (addToModule) {
            b.entities.add(entity.moduleLevelName, entity)
        }
    }
}

private class C_NamespaceMember_Object(
    base: C_NamespaceMemberBase,
    private val obj: R_ObjectDefinition,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.OBJECT

    override fun hasTag(tag: C_NamespaceMemberTag): Boolean {
        return tag == C_NamespaceMemberTag.VALUE || tag == C_NamespaceMemberTag.OBJECT
    }

    override fun getObjectOpt() = obj

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        val vExpr: V_Expr = V_ObjectExpr(ctx, qName, obj)
        return C_ValueExpr(vExpr)
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.objects.add(obj.moduleLevelName, obj)
    }

    override fun getDocMember(name: String) = obj.getDocMember(name)
}

private sealed class C_NamespaceMember_Struct(
    base: C_NamespaceMemberBase,
    rStruct: R_Struct,
): C_NamespaceMember(base) {
    private val typeDef: C_TypeDef = C_TypeDef.makeRType(rStruct.type)

    final override fun declarationType() = C_DeclarationType.STRUCT

    final override fun hasTag(tag: C_NamespaceMemberTag): Boolean {
        return tag == C_NamespaceMemberTag.TYPE || tag == C_NamespaceMemberTag.VALUE || tag == C_NamespaceMemberTag.CALLABLE
    }

    final override fun getTypeOpt() = typeDef

    final override fun toExpr(
        ctx: C_ExprContext,
        qName: C_QualifiedName,
        ideInfoPtr: C_UniqueDefaultIdeInfoPtr,
    ): C_Expr {
        return typeDef.compileExpr(ctx.msgCtx, qName.pos)
    }
}

private class C_NamespaceMember_SysStruct(
    base: C_NamespaceMemberBase,
    struct: R_Struct,
): C_NamespaceMember_Struct(base, struct)

private class C_NamespaceMember_UserStruct(
    base: C_NamespaceMemberBase,
    private val struct: C_Struct,
): C_NamespaceMember_Struct(base, struct.structDef.struct) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.structs.add(struct.structDef.moduleLevelName, struct)
    }

    override fun getDocMember(name: String) = struct.structDef.getDocMember(name)
}

private class C_NamespaceMember_Enum(
    base: C_NamespaceMemberBase,
    private val e: R_EnumDefinition,
): C_NamespaceMember(base) {
    private val typeDef: C_TypeDef = C_TypeDef.makeRType(e.type)

    override fun declarationType() = C_DeclarationType.ENUM

    override fun hasTag(tag: C_NamespaceMemberTag): Boolean {
        return tag == C_NamespaceMemberTag.TYPE || tag == C_NamespaceMemberTag.VALUE
    }

    override fun getTypeOpt() = typeDef

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        return typeDef.compileExpr(ctx.msgCtx, qName.pos)
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.enums.add(e.moduleLevelName, e)
    }

    override fun getDocMember(name: String) = e.getDocMember(name)
}

class C_FunctionExpr(
    private val name: LazyPosString,
    private val fn: C_GlobalFunction,
    private val ideInfoPtr: C_UniqueDefaultIdeInfoPtr,
): C_NoValueExpr() {
    override fun startPos() = name.pos
    override fun isCallable() = true

    override fun getDefMeta(): R_DefinitionMeta? {
        return fn.getDefMeta()
    }

    override fun valueOrError(): C_ValueOrError<V_Expr> {
        ideInfoPtr.setDefault()
        return super.valueOrError()
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vCall = try {
            fn.compileCall(ctx, name, args, resTypeHint)
        } catch (e: C_Error) {
            ideInfoPtr.setDefault()
            throw e
        }

        ideInfoPtr.setIdeInfoOrDefault(vCall.ideInfo)
        val vExpr = vCall.vExpr()
        return C_ValueExpr(vExpr)
    }

    override fun errKindName() = "function" to name.str
}

private sealed class C_NamespaceMember_Function(
    base: C_NamespaceMemberBase,
    private val fn: C_GlobalFunction,
): C_NamespaceMember(base) {
    final override fun declarationType() = C_DeclarationType.FUNCTION
    final override fun hasTag(tag: C_NamespaceMemberTag) = tag == C_NamespaceMemberTag.CALLABLE
    final override fun getFunctionOpt() = fn

    final override fun toExpr(
        ctx: C_ExprContext,
        qName: C_QualifiedName,
        ideInfoPtr: C_UniqueDefaultIdeInfoPtr,
    ): C_Expr {
        val lazyName = LazyPosString.of(qName.last.pos) { defName.qualifiedName.str() }
        return C_FunctionExpr(lazyName, fn, ideInfoPtr.move())
    }
}

private class C_NamespaceMember_SysFunction(
    base: C_NamespaceMemberBase,
    fn: C_GlobalFunction,
): C_NamespaceMember_Function(base, fn)

private class C_NamespaceMember_UserFunction(
    base: C_NamespaceMemberBase,
    private val userFn: C_UserGlobalFunction,
): C_NamespaceMember_Function(base, userFn) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val rFn = userFn.rFunction
        b.functions.add(rFn.moduleLevelName, rFn)
    }

    override fun getDocMember(name: String) = userFn.rFunction.getDocMember(name)
}

private class C_NamespaceMember_Operation(
    base: C_NamespaceMemberBase,
    private val cOp: C_OperationGlobalFunction,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.OPERATION
    override fun hasTag(tag: C_NamespaceMemberTag) = tag == C_NamespaceMemberTag.CALLABLE

    override fun getOperationOpt() = cOp.rOp

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        val lazyName = LazyPosString.of(qName.last.pos) { defName.appLevelName }
        return C_FunctionExpr(lazyName, cOp, C_UniqueDefaultIdeInfoPtr())
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val op = cOp.rOp
        b.operations.add(op.moduleLevelName, op)
    }

    override fun getDocMember(name: String) = cOp.rOp.getDocMember(name)
}

private class C_NamespaceMember_Query(
    base: C_NamespaceMemberBase,
    private val cQuery: C_QueryGlobalFunction,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.QUERY
    override fun hasTag(tag: C_NamespaceMemberTag) = tag == C_NamespaceMemberTag.CALLABLE

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        val lazyName = LazyPosString.of(qName.last.pos) { defName.appLevelName }
        return C_FunctionExpr(lazyName, cQuery, C_UniqueDefaultIdeInfoPtr())
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val q = cQuery.rQuery
        b.queries.add(q.moduleLevelName, q)
    }

    override fun getDocMember(name: String) = cQuery.rQuery.getDocMember(name)
}

private class C_NamespaceMember_GlobalConstant(
    base: C_NamespaceMemberBase,
    private val cDef: C_GlobalConstantDefinition,
): C_NamespaceMember(base) {
    override fun declarationType() = C_DeclarationType.CONSTANT
    override fun hasTag(tag: C_NamespaceMemberTag) = tag == C_NamespaceMemberTag.VALUE

    override fun toExpr(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoPtr: C_UniqueDefaultIdeInfoPtr): C_Expr {
        val vExpr = cDef.compileRead(ctx, qName.last)
        return C_ValueExpr(vExpr)
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val rDef = cDef.rDef
        b.constants.add(rDef.moduleLevelName, rDef)
    }
}

class C_SysNsProto(entries: List<C_NsEntry>, entities: List<C_NsEntry>) {
    val entries = entries.toImmList()
    val entities = entities.toImmList()

    fun toNamespace(): C_Namespace {
        return C_NsEntry.createNamespace(entries)
    }

    companion object {
        val EMPTY = C_SysNsProto(immListOf(), immListOf())
    }
}

class C_NsMemberFactory(private val basePath: C_RFullNamePath) {
    fun namespace(name: R_Name, ns: C_Namespace, ideInfo: C_IdeSymbolInfo): C_NamespaceMember {
        val base = makeBase(name, ideInfo)
        return C_NamespaceMember_Namespace(base, ns)
    }

    fun type(
        name: R_Name,
        typeDef: C_LibTypeDef,
        ideInfo: C_IdeSymbolInfo,
        deprecated: C_Deprecated? = null,
    ): C_NamespaceMember {
        val base = makeBase(name, ideInfo, deprecated)
        return C_NamespaceMember_Type(base, typeDef)
    }

    fun sysEntity(name: R_Name, entity: R_EntityDefinition, ideInfo: C_IdeSymbolInfo): C_NamespaceMember {
        val base = makeBase(name, ideInfo)
        return C_NamespaceMember_SysEntity(base, entity)
    }

    fun struct(name: R_Name, struct: R_Struct, ideInfo: C_IdeSymbolInfo): C_NamespaceMember {
        val base = makeBase(name, ideInfo)
        return C_NamespaceMember_SysStruct(base, struct)
    }

    fun function(name: R_Name, fn: C_GlobalFunction, ideInfo: C_IdeSymbolInfo): C_NamespaceMember {
        val base = makeBase(name, ideInfo)
        return C_NamespaceMember_SysFunction(base, fn)
    }

    fun property(name: R_Name, prop: C_NamespaceProperty, ideInfo: C_IdeSymbolInfo): C_NamespaceMember {
        val base = makeBase(name, ideInfo)
        return C_NamespaceMember_Property(base, prop)
    }

    private fun makeBase(
        name: R_Name,
        ideInfo: C_IdeSymbolInfo,
        deprecated: C_Deprecated? = null,
    ): C_NamespaceMemberBase {
        val defName = basePath.toDefPath().subName(name)
        return C_NamespaceMemberBase(defName, ideInfo, deprecated)
    }
}

class C_SysNsProtoBuilder {
    private val entries = mutableListOf<C_NsEntry>()
    private val entities = mutableListOf<C_NsEntry>()

    private var completed = false
        private set(value) {
            check(!field)
            check(value)
            field = value
        }

    fun addAll(nsProto: C_SysNsProto) {
        check(!completed)
        entries.addAll(nsProto.entries)
        entities.addAll(nsProto.entities)
    }

    fun addMember(name: R_Name, member: C_NamespaceMember) {
        addMember(name, C_NamespaceItem(member))
    }

    fun addMember(name: R_Name, item: C_NamespaceItem) {
        check(!completed)
        val entry = C_NsEntry(name, item)
        entries.add(entry)
        if (item.member.getEntityOpt() != null) {
            entities.add(entry)
        }
    }

    fun build(): C_SysNsProto {
        check(!completed)
        completed = true
        return C_SysNsProto(entries, entities)
    }
}

class C_UserNsProtoBuilder(private val assembler: C_NsAsm_ComponentAssembler) {
    fun futureNs() = assembler.futureNs()

    private fun addDef(name: C_Name, def: C_NamespaceMember) {
        assembler.addDef(name, def)
    }

    fun namespacePath(): C_RNamePath = assembler.namespacePath()

    fun addNamespace(
        name: C_Name,
        merge: Boolean,
        ideInfo: C_IdeSymbolInfo,
        deprecated: C_Deprecated?,
    ): C_UserNsProtoBuilder {
        val subAssembler = assembler.addNamespace(name, merge, ideInfo, deprecated)
        return C_UserNsProtoBuilder(subAssembler)
    }

    fun addModuleImport(alias: C_Name, module: C_ModuleKey, ideInfo: C_IdeSymbolInfo) {
        assembler.addModuleImport(alias, module, ideInfo)
    }

    fun addExactImport(
        alias: C_Name,
        module: C_ModuleKey,
        qNameHand: C_QualifiedNameHandle,
        aliasHand: C_NameHandle?,
        aliasDocSymbol: DocSymbol?,
    ) {
        assembler.addExactImport(alias, module, qNameHand, aliasHand, aliasDocSymbol)
    }

    fun addWildcardImport(module: C_ModuleKey, path: List<C_NameHandle>) {
        assembler.addWildcardImport(module, path)
    }

    fun addEntity(base: C_NamespaceMemberBase, name: C_Name, entity: R_EntityDefinition, addToModule: Boolean = true) {
        addDef(name, C_NamespaceMember_UserEntity(base, entity, addToModule))
    }

    fun addObject(base: C_NamespaceMemberBase, name: C_Name, obj: R_ObjectDefinition) {
        addDef(name, C_NamespaceMember_Object(base, obj))
    }

    fun addStruct(base: C_NamespaceMemberBase, cStruct: C_Struct) {
        addDef(cStruct.name, C_NamespaceMember_UserStruct(base, cStruct))
    }

    fun addEnum(base: C_NamespaceMemberBase, name: C_Name, e: R_EnumDefinition) {
        addDef(name, C_NamespaceMember_Enum(base, e))
    }

    fun addFunction(base: C_NamespaceMemberBase, name: C_Name, fn: C_UserGlobalFunction) {
        addDef(name, C_NamespaceMember_UserFunction(base, fn))
    }

    fun addOperation(base: C_NamespaceMemberBase, name: C_Name, operation: C_OperationGlobalFunction) {
        addDef(name, C_NamespaceMember_Operation(base, operation))
    }

    fun addQuery(base: C_NamespaceMemberBase, name: C_Name, query: C_QueryGlobalFunction) {
        addDef(name, C_NamespaceMember_Query(base, query))
    }

    fun addConstant(base: C_NamespaceMemberBase, name: C_Name, c: C_GlobalConstantDefinition) {
        addDef(name, C_NamespaceMember_GlobalConstant(base, c))
    }
}
