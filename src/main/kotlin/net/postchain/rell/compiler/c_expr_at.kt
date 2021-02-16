package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.Nullable
import net.postchain.rell.utils.toImmList
import org.apache.commons.lang3.StringUtils

class C_DbAtContext(
        val parent: C_DbAtContext?,
        val atExprId: R_AtExprId,
        val from: C_AtFrom_Entities
)

sealed class C_MaybeNestedAt
class C_MaybeNestedAt_Yes(val vExpr: V_Expr): C_MaybeNestedAt()
class C_MaybeNestedAt_No(val cExpr: C_Expr): C_MaybeNestedAt()

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
            ctx.msgCtx.error(pos, "at:entity:outer:$alias", "Cannot access an entity which belongs to an unrelated at-expression")
        }
        return rAtEntity
    }

    private fun isValidAccess(ctx: C_ExprContext): Boolean {
        var dbAtCtx = ctx.dbAtCtx
        while (dbAtCtx != null) {
            if (dbAtCtx.atExprId == atExprId) {
                return true
            }
            dbAtCtx = dbAtCtx.parent
        }
        return false
    }
}

class C_AtFromContext(val atPos: S_Pos, val atExprId: R_AtExprId, val parentAtCtx: C_DbAtContext?)

sealed class C_AtFrom(protected val outerExprCtx: C_ExprContext) {
    protected val innerBlkCtx = outerExprCtx.blkCtx.createSubContext("@")

    abstract fun innerExprCtx(): C_ExprContext
    abstract fun makeDefaultWhat(): C_AtWhat
    abstract fun findEntityByAlias(alias: S_Name): C_AtEntity?
    abstract fun findAttributesByName(name: S_Name): List<C_ExprContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_ExprContextAttr>

    abstract fun compile(details: C_AtDetails): V_Expr
    abstract fun compileNested(details: C_AtDetails): C_MaybeNestedAt
}

class C_AtFrom_Entities(
        outerExprCtx: C_ExprContext,
        entities: List<C_AtEntity>,
        private val parentAtCtx: C_DbAtContext?
): C_AtFrom(outerExprCtx) {
    val entities = entities.toImmList()

    private val msgCtx = outerExprCtx.msgCtx

    private val innerExprCtx: C_ExprContext

    init {
        check(entities.isNotEmpty())

        val atExprIds = entities.map { it.atExprId }.toSet()
        check(atExprIds.size == 1) { "Multiple AtExprIds: $atExprIds" }
        val atExprId = atExprIds.first()

        val dbAtCtx = C_DbAtContext(parentAtCtx, atExprId, this)

        innerExprCtx = outerExprCtx.update(
                blkCtx = innerBlkCtx,
                nameCtx = C_NameContext.createAt(this),
                dbAtCtx = Nullable(dbAtCtx)
        )

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

    override fun findEntityByAlias(alias: S_Name): C_AtEntity? {
        //TODO use a lookup table
        val res = entities.find { it.alias == alias.str }
        return res
    }

    override fun findAttributesByName(name: S_Name): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            val attrRef = C_EntityAttrRef.resolveByName(rEntity, name.str)
            if (attrRef == null) listOf() else listOf(attrRef)
        }
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            C_EntityAttrRef.resolveByType(rEntity, type)
        }
    }

    private fun findContextAttrs(resolver: (R_EntityDefinition) -> List<C_EntityAttrRef>): List<C_ExprContextAttr> {
        val attrs = mutableListOf<C_ExprContextAttr>()

        var from: C_AtFrom_Entities? = this
        while (from != null) {
            from.findContextAttrs0(from !== this, resolver, attrs)
            from = from.parentAtCtx?.from
        }

        return attrs.toImmList()
    }

    private fun findContextAttrs0(
            outer: Boolean,
            resolver: (R_EntityDefinition) -> List<C_EntityAttrRef>,
            attrs: MutableList<C_ExprContextAttr>
    ) {
        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (entity in entities) {
            val entityAttrs = resolver(entity.rEntity)
            val ctxAttrs = entityAttrs.map { C_ExprContextAttr_DbAtEntity(innerExprCtx, entity, it, outer) }
            attrs.addAll(ctxAttrs)
        }
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val rFrom = entities.map { it.toRAtEntity() }
        return if (parentAtCtx != null) {
            compileNested(details, rFrom)
        } else {
            compileTop(details, rFrom)
        }
    }

    override fun compileNested(details: C_AtDetails): C_MaybeNestedAt {
        val vExpr = compile(details)
        return C_MaybeNestedAt_Yes(vExpr)
    }

    private fun compileTop(details: C_AtDetails, rFrom: List<R_DbAtEntity>): V_Expr {
        val rWhat = details.base.what.materialFields.map {
            val whatValue = it.expr.toDbExprWhat(it)
            val rFlags = it.flags.compile()
            Db_AtWhatField(rFlags, whatValue)
        }

        val dbWhere = details.base.where?.toDbExpr()
        val rBase = Db_AtExprBase(rFrom, rWhat, dbWhere)

        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()
        val extras = R_AtExprExtras(rLimit, rOffset)

        val cBlock = innerBlkCtx.buildBlock()
        val rowDecoder = details.res.rowDecoder
        val internals = R_DbAtExprInternals(cBlock.rBlock, rowDecoder)

        val rExpr = R_DbAtExpr(details.res.resultType, rBase, details.cardinality, extras, internals)
        return V_RExpr(outerExprCtx, details.startPos, rExpr, details.exprFacts)
    }

    private fun compileNested(details: C_AtDetails, rFrom: List<R_DbAtEntity>): V_Expr {
        var resultType = details.res.resultType
        if (details.cardinality != R_AtCardinality.ZERO_MANY) {
            msgCtx.error(details.cardinalityPos, "at_expr:nested:cardinality:${details.cardinality}",
                    "Only '@*' can be used in a nested at-expression")
            // Fix result type to prevent exists() also firing a "wrong argument type" CTE.
            resultType = C_AtExprResult.calcResultType(details.res.recordType, R_AtCardinality.ZERO_MANY)
        }

        val dbWhat = details.base.what.materialFields.map {
            val whatValue = it.expr.toDbExprWhat(it)
            val rFlags = it.flags.compile()
            Db_AtWhatField(rFlags, whatValue)
        }

        val dbWhere = details.base.where?.toDbExpr()
        val dbBase = Db_AtExprBase(rFrom, dbWhat, dbWhere)

        checkNoLimitOffset(details.limit, "limit")
        checkNoLimitOffset(details.offset, "offset")

        val cBlock = innerBlkCtx.buildBlock()

        val dbExpr = Db_NestedAtExpr(resultType, dbBase, cBlock.rBlock)
        return V_DbExpr.create(outerExprCtx, details.startPos, dbExpr, details.exprFacts)
    }

    private fun checkNoLimitOffset(expr: V_Expr?, word: String) {
        if (expr != null) {
            val cap = StringUtils.capitalize(word)
            msgCtx.error(expr.pos, "at_expr:nested:$word", "$cap not allowed for a nested at-expression")
        }
    }

    fun compileUpdate(): R_FrameBlock {
        val cBlock = innerBlkCtx.buildBlock()
        return cBlock.rBlock
    }
}

class C_AtFrom_Iterable(
        outerExprCtx: C_ExprContext,
        private val pos: S_Pos,
        alias: S_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom(outerExprCtx) {
    private val innerExprCtx: C_ExprContext
    private val placeholderVar: C_LocalVar
    private val varPtr: R_VarPtr

    init {
        placeholderVar = innerBlkCtx.newLocalVar(alias?.str ?: C_Constants.AT_PLACEHOLDER, item.elemType, false, true)
        val phEntry = C_BlockEntry_Var(placeholderVar)

        if (alias == null) {
            innerBlkCtx.addAtPlaceholder(phEntry)
        } else {
            innerBlkCtx.addEntry(alias.pos, alias.str, true, phEntry)
        }

        varPtr = placeholderVar.toRef(innerBlkCtx.blockUid).ptr

        val factsCtx = outerExprCtx.factsCtx.sub(C_VarFacts.of(inited = mapOf(placeholderVar.uid to C_VarFact.YES)))
        innerExprCtx = outerExprCtx.update(
                blkCtx = innerBlkCtx,
                nameCtx = C_NameContext.createAt(this),
                factsCtx = factsCtx
        )
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val vExpr = compilePlaceholderRef(pos)
        val field = C_AtWhatField(null, vExpr.type(), vExpr, C_AtWhatFieldFlags.DEFAULT, null)
        return C_AtWhat(listOf(field))
    }

    override fun findEntityByAlias(alias: S_Name): C_AtEntity? {
        // The alias is added to the inner block scope.
        return null
    }

    override fun findAttributesByName(name: S_Name): List<C_ExprContextAttr> {
        val memValue = C_MemberResolver.findMemberValueForType(item.elemType, name.str)
        memValue ?: return listOf()
        val base = compilePlaceholderRef(name.pos)
        val memRef = C_MemberRef(name.pos, base, name, false)
        return listOf(C_ExprContextAttr_ColAtMember(innerExprCtx, memValue, memRef))
    }

    private fun compilePlaceholderRef(pos: S_Pos): V_Expr {
        val entry: C_BlockEntry = C_BlockEntry_Var(placeholderVar)
        return entry.compile(innerExprCtx, pos)
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return listOf()
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

    override fun compileNested(details: C_AtDetails): C_MaybeNestedAt {
        val vExpr = compile(details)
        return C_MaybeNestedAt_No(C_VExpr(vExpr))
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
