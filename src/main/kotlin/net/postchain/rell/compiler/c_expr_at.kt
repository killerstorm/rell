package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.vexpr.V_AtAttrExpr
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.Nullable
import net.postchain.rell.utils.chainToIterable
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList

class C_AtContext(
        val parent: C_AtContext?,
        val atExprId: R_AtExprId,
        val dbAt: Boolean
)

class C_AtEntity(
        val pos: S_Pos,
        val rEntity: R_EntityDefinition,
        val alias: String,
        val explicitAlias: Boolean,
        val atEntityId: R_AtEntityId
) {
    val atExprId = atEntityId.exprId

    private val rAtEntity = R_DbAtEntity(rEntity, atEntityId)

    fun toRAtEntity(): R_DbAtEntity {
        return rAtEntity
    }

    fun toDbExpr(): Db_TableExpr {
        return Db_EntityExpr(rAtEntity)
    }

    fun toRAtEntityValidated(ctx: C_ExprContext, pos: S_Pos): R_DbAtEntity {
        if (!isValidAccess(ctx)) {
            ctx.msgCtx.error(pos, "at:entity:outer:$alias",
                    "Cannot access entity '${rEntity.moduleLevelName}' as it belongs to an unrelated at-expression")
        }
        return rAtEntity
    }

    private fun isValidAccess(ctx: C_ExprContext): Boolean {
        return chainToIterable(ctx.atCtx) { it.parent }.any { it.atExprId == atExprId }
    }
}

class C_AtFromContext(val pos: S_Pos, val atExprId: R_AtExprId, val parentAtCtx: C_AtContext?)

sealed class C_AtFrom(
        protected val outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext
) {
    val atExprId = fromCtx.atExprId

    protected val parentAtCtx = fromCtx.parentAtCtx
    protected val innerBlkCtx = outerExprCtx.blkCtx.createSubContext("@", atFrom = this)

    val innerAtCtx = C_AtContext(fromCtx.parentAtCtx, atExprId, this is C_AtFrom_Entities)

    abstract fun innerExprCtx(): C_ExprContext
    abstract fun makeDefaultWhat(): C_AtWhat
    abstract fun findAttributesByName(name: String): List<C_AtFromContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_AtFromContextAttr>

    abstract fun compile(details: C_AtDetails): V_Expr
}

class C_AtFrom_Entities(
        outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext,
        entities: List<C_AtEntity>
): C_AtFrom(outerExprCtx, fromCtx) {
    val entities = entities.toImmList()

    private val msgCtx = outerExprCtx.msgCtx

    private val innerExprCtx: C_ExprContext

    init {
        check(entities.isNotEmpty())

        val atExprIds = entities.map { it.atExprId }.toSet()
        checkEquals(setOf(atExprId), atExprIds)

        innerExprCtx = outerExprCtx.update(blkCtx = innerBlkCtx, atCtx = Nullable(innerAtCtx))

        val ph = entities.any { !it.explicitAlias }
        for (entity in entities) {
            val entry = C_BlockEntry_AtEntity(entity)
            innerBlkCtx.addEntry(entity.pos, entity.alias, entity.explicitAlias, entry)
            if (ph) {
                innerBlkCtx.addAtPlaceholder(entry)
            }
        }
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val fields = entities.map {
            val name = if (entities.size == 1) null else it.alias
            val dbExpr = it.toDbExpr()
            val vExpr = V_DbExpr.create(innerExprCtx, it.pos, dbExpr)
            C_AtWhatField(name, vExpr.type(), vExpr, C_AtWhatFieldFlags.DEFAULT, null)
        }
        return C_AtWhat(fields)
    }

    override fun findAttributesByName(name: String): List<C_AtFromContextAttr> {
        return findContextAttrs { rEntity ->
            val attrRef = C_EntityAttrRef.resolveByName(rEntity, name)
            if (attrRef == null) listOf() else listOf(attrRef)
        }
    }

    override fun findAttributesByType(type: R_Type): List<C_AtFromContextAttr> {
        return findContextAttrs { rEntity ->
            C_EntityAttrRef.resolveByType(rEntity, type)
        }
    }

    private fun findContextAttrs(resolver: (R_EntityDefinition) -> List<C_EntityAttrRef>): List<C_AtFromContextAttr> {
        val attrs = mutableListOf<C_AtFromContextAttr>()

        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (entity in entities) {
            val entityAttrs = resolver(entity.rEntity)
            val ctxAttrs = entityAttrs.map { C_AtFromContextAttr_DbAtEntity(entity, it) }
            attrs.addAll(ctxAttrs)
        }

        return attrs.toImmList()
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val dbBase = compileBase(details)
        val extras = compileExtras(details)

        if (parentAtCtx?.dbAt != true) {
            return compileTop(details, dbBase, extras)
        }

        val dependent = isOuterDependent(dbBase)
        return if (dependent || details.cardinality == R_AtCardinality.ZERO_MANY) {
            compileNested(details, dbBase, extras)
        } else {
            compileTop(details, dbBase, extras)
        }
    }

    private fun isOuterDependent(dbBase: Db_AtExprBase): Boolean {
        val dbExprs = dbBase.directSubExprs()
        val exprIds = dbExprs.flatMap { it.referencedAtExprIds() }.toSet()
        return chainToIterable(parentAtCtx) { it.parent }.any { exprIds.contains(it.atExprId) }
    }

    private fun compileTop(details: C_AtDetails, dbBase: Db_AtExprBase, extras: R_AtExprExtras): V_Expr {
        val cBlock = innerBlkCtx.buildBlock()
        val rowDecoder = details.res.rowDecoder
        val internals = R_DbAtExprInternals(cBlock.rBlock, rowDecoder)
        val rExpr = R_DbAtExpr(details.res.resultType, dbBase, details.cardinality, extras, internals)
        return V_RExpr(outerExprCtx, details.startPos, rExpr, details.exprFacts)
    }

    private fun compileNested(details: C_AtDetails, dbBase: Db_AtExprBase, extras: R_AtExprExtras): V_Expr {
        var resultType = details.res.resultType
        if (details.cardinality != R_AtCardinality.ZERO_MANY) {
            msgCtx.error(details.cardinalityPos, "at_expr:nested:cardinality:${details.cardinality}",
                    "Only '@*' can be used in a nested at-expression")
            // Fix result type to prevent exists() also firing a "wrong argument type" CTE.
            resultType = C_AtExprResult.calcResultType(details.res.recordType, R_AtCardinality.ZERO_MANY)
        }

        val extras = compileExtras(details)
        val cBlock = innerBlkCtx.buildBlock()

        val dbExpr = Db_NestedAtExpr(resultType, dbBase, extras, cBlock.rBlock)
        return V_DbExpr.create(outerExprCtx, details.startPos, dbExpr, details.exprFacts)
    }

    private fun compileBase(details: C_AtDetails): Db_AtExprBase {
        val rFrom = entities.map { it.toRAtEntity() }

        val dbWhat = details.base.what.materialFields.map {
            val whatValue = it.expr.toDbExprWhat(it)
            val rFlags = it.flags.compile()
            Db_AtWhatField(rFlags, whatValue)
        }

        val dbWhere = details.base.where?.toDbExpr()
        return Db_AtExprBase(rFrom, dbWhat, dbWhere)
    }

    private fun compileExtras(details: C_AtDetails): R_AtExprExtras {
        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()
        return R_AtExprExtras(rLimit, rOffset)
    }

    fun compileUpdate(): R_FrameBlock {
        val cBlock = innerBlkCtx.buildBlock()
        return cBlock.rBlock
    }
}

class C_AtFrom_Iterable(
        outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext,
        alias: S_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom(outerExprCtx, fromCtx) {
    private val pos = fromCtx.pos
    private val innerExprCtx: C_ExprContext
    private val placeholderVar: C_LocalVar
    private val varPtr: R_VarPtr

    init {
        placeholderVar = innerBlkCtx.newLocalVar(alias?.str ?: C_Constants.AT_PLACEHOLDER, item.elemType, false, atExprId)
        val phEntry = C_BlockEntry_Var(placeholderVar)

        if (alias == null) {
            innerBlkCtx.addAtPlaceholder(phEntry)
        } else {
            innerBlkCtx.addEntry(alias.pos, alias.str, true, phEntry)
        }

        varPtr = placeholderVar.toRef(innerBlkCtx.blockUid).ptr

        val factsCtx = outerExprCtx.factsCtx.sub(C_VarFacts.of(inited = mapOf(placeholderVar.uid to C_VarFact.YES)))
        innerExprCtx = outerExprCtx.update(blkCtx = innerBlkCtx, factsCtx = factsCtx, atCtx = Nullable(innerAtCtx))
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val vExpr = compilePlaceholderRef(pos)
        val field = C_AtWhatField(null, vExpr.type(), vExpr, C_AtWhatFieldFlags.DEFAULT, null)
        return C_AtWhat(listOf(field))
    }

    override fun findAttributesByName(name: String): List<C_AtFromContextAttr> {
        val memValue = C_MemberResolver.findMemberValueForTypeByName(item.elemType, name)
        memValue ?: return listOf()
        return listOf(C_AtFromContextAttr_ColAtMember(placeholderVar, memValue))
    }

    override fun findAttributesByType(type: R_Type): List<C_AtFromContextAttr> {
        val memValues = C_MemberResolver.findMemberValuesForTypeByType(item.elemType, type)
        return memValues.map { C_AtFromContextAttr_ColAtMember(placeholderVar, it) }
    }

    private fun compilePlaceholderRef(pos: S_Pos): V_Expr {
        val entry: C_BlockEntry = C_BlockEntry_Var(placeholderVar)
        return entry.compile(innerExprCtx, pos)
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val rParam = R_VarParam(C_Constants.AT_PLACEHOLDER, item.elemType, varPtr)
        val rFrom = item.compile()
        val rWhere = details.base.where?.toRExpr() ?: R_ConstantExpr.makeBool(true)

        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()
        val extras = R_AtExprExtras(rLimit, rOffset)

        val what = compileWhat(details)
        val summarization = compileSummarization(details.res, what)

        val cBlock = innerBlkCtx.buildBlock()

        val rExpr = R_ColAtExpr(
                type = details.res.resultType,
                block = cBlock.rBlock,
                param = rParam,
                from = rFrom,
                what = what,
                where = rWhere,
                summarization = summarization,
                cardinality = details.cardinality,
                extras = extras
        )

        return V_RExpr(outerExprCtx, details.startPos, rExpr, details.exprFacts)
    }

    private fun compileWhat(details: C_AtDetails): R_ColAtWhat {
        val cFields = details.base.what.allFields
        val fields = cFields.map { compileField(it) }
        val sorting = compileSorting(cFields)
        return R_ColAtWhat(
                fields,
                details.res.selectedFields,
                details.res.groupFields,
                sorting,
                details.res.rowDecoder
        )
    }

    private fun compileSorting(cFields: List<C_AtWhatField>): List<IndexedValue<Comparator<Rt_Value>>> {
        val sorting = cFields
                .withIndex()
                .map { (i, f) ->
                    val sort = f.flags.sort
                    if (sort == null) null else {
                        val type = f.resultType
                        var comparator = type.comparator()
                        if (comparator != null && !sort.value.asc) comparator = comparator.reversed()
                        if (comparator != null) IndexedValue(i, comparator) else {
                            innerExprCtx.msgCtx.error(sort.pos, "at:expr:sort:type:${type}", "Type ${type} is not sortable")
                            null
                        }
                    }
                }
                .filterNotNull()
                .toImmList()
        return sorting
    }

    private fun compileField(cField: C_AtWhatField): R_ColAtWhatField {
        val rExpr = cField.expr.toRExpr()
        val summarization = if (cField.summarization == null) {
            R_ColAtFieldSummarization_None
        } else {
            cField.summarization.compileR(innerExprCtx.nsCtx)
        }
        val rFlags = cField.flags.compile()
        return R_ColAtWhatField(rExpr, rFlags, summarization)
    }

    private fun compileSummarization(cResult: C_AtExprResult, rWhat: R_ColAtWhat): R_ColAtSummarization {
        return if (cResult.groupFields.isEmpty() && !cResult.hasAggregateFields) {
            R_ColAtSummarization_None(rWhat.fields.size)
        } else if (cResult.groupFields.isEmpty()) {
            R_ColAtSummarization_All(rWhat)
        } else {
            R_ColAtSummarization_Group(rWhat)
        }
    }
}

sealed class C_AtFromItem(val pos: S_Pos)
class C_AtFromItem_Entity(pos: S_Pos, val alias: S_Name, val entity: R_EntityDefinition): C_AtFromItem(pos)

sealed class C_AtFromItem_Iterable(pos: S_Pos, val vExpr: V_Expr, val elemType: R_Type): C_AtFromItem(pos) {
    protected abstract fun compile0(rExpr: R_Expr): R_ColAtFrom

    fun compile(): R_ColAtFrom {
        val rExpr = vExpr.toRExpr()
        return compile0(rExpr)
    }
}

class C_AtFromItem_Collection(pos: S_Pos, vExpr: V_Expr, elemType: R_Type): C_AtFromItem_Iterable(pos, vExpr, elemType) {
    override fun compile0(rExpr: R_Expr): R_ColAtFrom = R_ColAtFrom_Collection(rExpr)
}

class C_AtFromItem_Map(pos: S_Pos, vExpr: V_Expr, private val tupleType: R_TupleType)
    : C_AtFromItem_Iterable(pos, vExpr, tupleType)
{
    override fun compile0(rExpr: R_Expr): R_ColAtFrom = R_ColAtFrom_Map(rExpr, tupleType)
}

class C_AtWhatFieldFlags(
        val omit: Boolean,
        val sort: S_PosValue<R_AtWhatSort>?,
        val group: S_Pos?,
        val aggregate: S_Pos?
) {
    fun compile() = R_AtWhatFieldFlags(omit = omit, sort = sort?.value, group = group != null, aggregate = aggregate != null)

    companion object {
        val DEFAULT = C_AtWhatFieldFlags(omit = false, sort = null, group = null, aggregate = null)
    }
}

class C_AtWhatField(
        val name: String?,
        val resultType: R_Type,
        val expr: V_Expr,
        val flags: C_AtWhatFieldFlags,
        val summarization: C_AtSummarization?
) {
    fun isIgnored() = flags.omit && flags.sort == null && summarization == null
}

class C_AtWhat(allFields: List<C_AtWhatField>) {
    val allFields = allFields.toImmList()
    val materialFields = allFields.filter { !it.isIgnored() }
}

class C_AtExprBase(
        val what: C_AtWhat,
        val where: V_Expr?
)

class C_AtExprResult(
        val recordType: R_Type,
        val resultType: R_Type,
        val rowDecoder: R_AtExprRowDecoder,
        selectedFields: List<Int>,
        groupFields: List<Int>,
        val hasAggregateFields: Boolean
) {
    val selectedFields = selectedFields.toImmList()
    val groupFields = groupFields.toImmList()

    companion object {
        fun calcResultType(recordType: R_Type, cardinality: R_AtCardinality): R_Type {
            return if (cardinality.many) {
                R_ListType(recordType)
            } else if (cardinality.zero) {
                C_Types.toNullable(recordType)
            } else {
                recordType
            }
        }
    }
}

class C_AtDetails(
        val startPos: S_Pos,
        val cardinalityPos: S_Pos,
        val cardinality: R_AtCardinality,
        val base: C_AtExprBase,
        val limit: V_Expr?,
        val offset: V_Expr?,
        val res: C_AtExprResult,
        val exprFacts: C_ExprVarFacts
)

class C_AtSummarizationPos(val exprPos: S_Pos, val ann: C_AtSummarizationKind)

sealed class C_AtSummarization(protected val pos: C_AtSummarizationPos, protected val valueType: R_Type) {
    abstract fun isGroup(): Boolean
    abstract fun getResultType(hasGroup: Boolean): R_Type
    abstract fun compileR(ctx: C_NamespaceContext): R_ColAtFieldSummarization
    abstract fun compileDb(ctx: C_NamespaceContext, dbExpr: Db_Expr): Db_Expr

    companion object {
        fun typeError(msgCtx: C_MessageContext, type: R_Type, pos: C_AtSummarizationPos) {
            val code = "at:what:aggr:bad_type:${pos.ann}:${type.toStrictString()}"
            val msg = "Invalid type of @${pos.ann.annotation} expression: ${type.toStrictString()}"
            msgCtx.error(pos.exprPos, code, msg)
        }
    }
}

class C_AtSummarization_Group(pos: C_AtSummarizationPos, valueType: R_Type): C_AtSummarization(pos, valueType) {
    override fun isGroup() = true
    override fun getResultType(hasGroup: Boolean) = valueType

    override fun compileR(ctx: C_NamespaceContext): R_ColAtFieldSummarization {
        C_Utils.checkGroupValueType(ctx, pos.exprPos, valueType)
        return R_ColAtFieldSummarization_Group()
    }

    override fun compileDb(ctx: C_NamespaceContext, dbExpr: Db_Expr) = dbExpr
}

sealed class C_AtSummarization_Aggregate(
        pos: C_AtSummarizationPos,
        valueType: R_Type
): C_AtSummarization(pos, valueType) {
    protected abstract fun compileDb0(): Db_SysFunction?

    final override fun isGroup() = false

    final override fun compileDb(ctx: C_NamespaceContext, dbExpr: Db_Expr): Db_Expr {
        val dbFn = compileDb0()
        if (dbFn == null) {
            typeError(ctx.msgCtx, valueType, pos)
            return dbExpr
        }
        return Db_CallExpr(dbExpr.type, dbFn, listOf(dbExpr))
    }
}

class C_AtSummarization_Aggregate_Sum(
        pos: C_AtSummarizationPos,
        valueType: R_Type,
        private val rOp: R_BinaryOp,
        private val zeroValue: Rt_Value
): C_AtSummarization_Aggregate(pos, valueType) {
    override fun getResultType(hasGroup: Boolean) = valueType
    override fun compileR(ctx: C_NamespaceContext) = R_ColAtFieldSummarization_Aggregate_Sum(rOp, zeroValue)
    override fun compileDb0() = Db_SysFn_Aggregation_Sum
}

class C_AtSummarization_Aggregate_MinMax(
        pos: C_AtSummarizationPos,
        valueType: R_Type,
        private val rCmpOp: R_CmpOp,
        private val rCmpType: R_CmpType?,
        private val rComparator: Comparator<Rt_Value>?,
        private val dbFn: Db_SysFunction
): C_AtSummarization_Aggregate(pos, valueType) {
    override fun getResultType(hasGroup: Boolean): R_Type {
        return if (hasGroup) valueType else C_Types.toNullable(valueType)
    }

    override fun compileR(ctx: C_NamespaceContext): R_ColAtFieldSummarization {
        return if (rComparator == null) {
            typeError(ctx.msgCtx, valueType, pos)
            R_ColAtFieldSummarization_None
        } else {
            R_ColAtFieldSummarization_Aggregate_MinMax(rCmpOp, rComparator)
        }
    }

    override fun compileDb0(): Db_SysFunction? {
        // Postgres doesn't support MIN/MAX for BOOLEAN and BYTEA.
        return if (rCmpType == null || valueType == R_BooleanType || valueType == R_ByteArrayType) null else dbFn
    }
}

class C_ExprContextAttr(private val fromAttr: C_AtFromContextAttr, private val outerAtExpr: Boolean) {
    val type = fromAttr.type

    fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        if (outerAtExpr) {
            val attrName = attrNameMsg(false)
            val ownerTypeName = fromAttr.ownerTypeName()
            ctx.msgCtx.error(pos, "at_expr:attr:belongs_to_outer:${attrName.code}:$ownerTypeName",
                    "Attribute '${attrName.msg}' belongs to an outer at-expression, fully qualified name is required")
        }
        return fromAttr.compile(ctx, pos)
    }

    fun attrNameMsg(qualified: Boolean): C_CodeMsg = fromAttr.attrNameMsg(qualified)
    override fun toString() = attrNameMsg(true).code
}

sealed class C_AtFromContextAttr(val type: R_Type) {
    abstract fun attrNameMsg(qualified: Boolean): C_CodeMsg
    abstract fun ownerTypeName(): String
    abstract fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr

    final override fun toString() = attrNameMsg(true).code
}

private class C_AtFromContextAttr_DbAtEntity(
        private val atEntity: C_AtEntity,
        private val attrRef: C_EntityAttrRef
): C_AtFromContextAttr(attrRef.type()) {
    override fun attrNameMsg(qualified: Boolean): C_CodeMsg {
        var name = attrRef.attrName
        if (qualified) name = "${atEntity.alias}.$name"
        return C_CodeMsg(name, name)
    }

    override fun ownerTypeName() = atEntity.rEntity.type.name
    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr = V_AtAttrExpr(ctx, pos, atEntity, attrRef)
}

private class C_AtFromContextAttr_ColAtMember(
        private val itemVar: C_LocalVar,
        private val memberValue: C_MemberValue
): C_AtFromContextAttr(memberValue.type()) {
    override fun attrNameMsg(qualified: Boolean): C_CodeMsg {
        val memberName = memberValue.memberName()
        val base = if (itemVar.name != "$") itemVar.name else itemVar.type.name
        val code = memberName(memberName, base, qualified)
        val msg = memberName(memberName, base, qualified)
        return C_CodeMsg(code, msg)
    }

    private fun memberName(memberName: C_MemberName, base: String, qualified: Boolean): String {
        return if (qualified) memberName.qualifiedName(base) else memberName.simpleName()
    }

    override fun ownerTypeName() = itemVar.type.name

    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        val blockEntry: C_BlockEntry = C_BlockEntry_Var(itemVar)
        val base = blockEntry.compile(ctx, pos)
        val memLink = C_MemberLink(base, false, pos)
        return memberValue.compile(ctx, memLink)
    }
}
