package net.postchain.rell.parser

import net.postchain.rell.model.*

internal class DbClassAttr(val cls: RAtClass, val name: String, val type: RType)

internal class DbCompilationContext(
        val parent: DbCompilationContext?,
        val exprCtx: ExprCompilationContext,
        val classes: List<RAtClass>
) {
    fun findAttributesByName(name: String): List<DbClassAttr> {
        return findAttrsInChain({ it.name == name })
    }

    fun findAttributesByType(type: RType): List<DbClassAttr> {
        return findAttrsInChain({ it.type == type })
    }

    private fun findAttrsInChain(matcher: (RAttrib) -> Boolean): List<DbClassAttr> {
        val attrs = mutableListOf<DbClassAttr>()
        var ctx: DbCompilationContext? = this
        while (ctx != null) {
            ctx.findAttrInternal(matcher, attrs)
            ctx = ctx.parent
        }
        return attrs.toList()
    }

    private fun findAttrInternal(matcher: (RAttrib) -> Boolean, attrs: MutableList<DbClassAttr>): DbClassAttr? {
        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (cls in classes) {
            for (attr in cls.rClass.attributes.values) {
                if (matcher(attr)) {
                    attrs.add(DbClassAttr(cls, attr.name, attr.type))
                }
            }
        }
        return null
    }

    fun findClassByAlias(alias: String): RAtClass? {
        var ctx: DbCompilationContext? = this
        while (ctx != null) {
            //TODO use a lookup table
            for (cls in ctx.classes) {
                if (cls.alias == alias) {
                    return cls
                }
            }
            ctx = ctx.parent
        }
        return null
    }
}

class S_NameExprPair(val name: String?, val expr: S_Expression)

abstract class S_Expression {
    internal abstract fun compile(ctx: ExprCompilationContext): RExpr
    internal abstract fun compileDb(ctx: DbCompilationContext): DbExpr
    internal open fun compileDbWhere(ctx: DbCompilationContext, idx: Int): DbExpr = compileDb(ctx)
    internal open fun compileCall(ctx: ExprCompilationContext, args: List<RExpr>): RExpr = TODO()
    internal open fun compileCallDb(ctx: DbCompilationContext, args: List<DbExpr>): DbExpr = TODO()
    internal open fun compileAsBoolean(ctx: ExprCompilationContext): RExpr = compile(ctx)

    internal open fun iteratePathExpr(path: MutableList<String>): Boolean = false

    internal fun delegateCompileDb(ctx: DbCompilationContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))
}

class S_StringLiteralExpr(val literal: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RStringLiteralExpr(RTextType, literal)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_ByteALiteralExpr(val bytes: ByteArray): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RByteArrayLiteralExpr(RByteArrayType, bytes)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_IntLiteralExpr(val value: Long): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RIntegerLiteralExpr(RIntegerType, value)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_BooleanLiteralExpr(val value: Boolean): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RBooleanLiteralExpr(value)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val rArgs = args.map { it.compile(ctx) }
        return base.compileCall(ctx, rArgs)
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val dbArgs = args.map { it.compileDb(ctx) }
        return base.compileCallDb(ctx, dbArgs)
    }
}

class S_LookupExpr(val base: S_Expression, val expr: S_Expression): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)

        val baseType = rBase.type
        if (!(baseType is RListType)) {
            throw CtError("expr_lookup_base:${baseType.toStrictString()}",
                    "Operator '[]' undefined for type ${baseType.toStrictString()}")
        }

        if (!RIntegerType.accepts(rExpr.type)) {
            throw CtError("expr_lookup_keytype:${baseType.toStrictString()}:${rExpr.type.toStrictString()}",
                    "Invalid lookup key type: ${rExpr.type.toStrictString()}")
        }

        return RLookupExpr(baseType.elementType, rBase, rExpr)
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class S_CreateExpr(val className: String, val exprs: List<S_NameExprPair>): S_Expression() {
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)

    override fun compile(ctx: ExprCompilationContext): RExpr {
        ctx.checkDbUpdateAllowed()

        val cls = ctx.modCtx.getClass(className)
        val rExprs = exprs.map { it.expr.compile(ctx) }

        val types = rExprs.map { it.type }
        val attrs = S_UpdateExprMatcher.matchExpressions(cls, exprs, types, false)
        val attrExprs = attrs.withIndex().map { (idx, attr) -> RCreateExprAttr(attr, rExprs[idx]) }

        val attrExprsDef = attrExprs + matchDefaultExpressions(cls, attrExprs)
        checkMissingAttrs(cls, attrExprsDef)

        val type = RInstanceRefType(cls)
        return RCreateExpr(type, cls, attrExprsDef)
    }

    private fun matchDefaultExpressions(cls: RClass, attrExprs: List<RCreateExprAttr>): List<RCreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return cls.attributes.values.filter { it.expr != null && !(it.name in provided) }.map { RCreateExprAttr(it, it.expr!!) }
    }

    private fun checkMissingAttrs(cls: RClass, attrs: List<RCreateExprAttr>) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (cls.attributes.keys - names).sorted().toList()
        if (!missing.isEmpty()) {
            throw CtError("attr_missing:${missing.joinToString(",")}",
                    "Attributes not specified: ${missing.joinToString()}")
        }
    }
}
