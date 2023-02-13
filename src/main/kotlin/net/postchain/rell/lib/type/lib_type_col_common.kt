/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_ForIterator
import net.postchain.rell.compiler.base.core.C_NamespaceContext
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_CallTypeHints
import net.postchain.rell.compiler.base.expr.C_CallTypeHints_None
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.vexpr.V_CopyCollectionConstructorExpr
import net.postchain.rell.compiler.vexpr.V_EmptyCollectionConstructorExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_CollectionKind
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.LazyString
import net.postchain.rell.lib.type.C_Lib_Type_Any as AnyFns

private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)

object C_Lib_Type_Collection {
    fun bindMemberFns(b: C_MemberFuncBuilder, listType: R_ListType) {
        val elemType = listType.elementType

        b.add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)

        b.add("empty", R_BooleanType, listOf(), ColFns.Empty)
        b.add("size", R_IntegerType, listOf(), ColFns.Size)
        b.add("len", R_IntegerType, listOf(), ColFns.Size, depError("size"))

        b.add("contains", R_BooleanType, listOf(elemType), ColFns.Contains)
        b.addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.ContainsAll, depError("contains_all"))
        b.addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.ContainsAll)

        b.add("add", R_BooleanType, listOf(elemType), ColFns.Add)
        b.addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.AddAll, depError("add_all"))
        b.addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.AddAll)

        b.add("remove", R_BooleanType, listOf(elemType), ColFns.Remove)
        b.addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.RemoveAll, depError("remove_all"))
        b.addEx("remove_all", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.RemoveAll)

        b.add("clear", R_UnitType, listOf(), ColFns.Clear)

        val comparator = elemType.comparator()
        if (comparator != null) {
            b.add("sorted", listType, listOf(), ColFns.Sorted(listType, comparator))
        }
    }

    fun checkElementType(pos: S_Pos, declaredType: R_Type?, argumentType: R_Type, errCode: String, errMsg: String): R_Type {
        if (declaredType == null) {
            return argumentType
        }

        C_Errors.check(declaredType.isAssignableFrom(argumentType), pos) {
            "$errCode:${declaredType.strCode()}:${argumentType.strCode()}" toCodeMsg
                    "$errMsg: ${argumentType.strCode()} instead of ${declaredType.strCode()}"
        }

        return declaredType
    }
}

abstract class C_CollectionKindAdapter(val rawTypeName: String) {
    abstract fun elementTypeFromTypeHint(typeHint: C_TypeHint): R_Type?
    abstract fun makeKind(rElementType: R_Type): R_CollectionKind

    protected abstract fun checkElementType0(ctx: C_NamespaceContext, pos: S_Pos, elemTypePos: S_Pos, rElemType: R_Type)

    fun checkElementType(ctx: C_NamespaceContext, pos: S_Pos, elemTypePos: S_Pos, rElemType: R_Type) {
        val checkedType = C_Types.checkNotUnit(ctx.msgCtx, elemTypePos, rElemType, null) {
            "$rawTypeName:elem" toCodeMsg "$rawTypeName element"
        }
        if (checkedType.isNotError()) {
            checkElementType0(ctx, pos, elemTypePos, rElemType)
        }
    }
}

class C_CollectionConstructorFunction(
    private val kindAdapter: C_CollectionKindAdapter,
    private val rExplicitElementType: R_Type?,
): C_GlobalFunction(IdeSymbolInfo.DEF_TYPE) {
    private val colType = kindAdapter.rawTypeName

    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint
    ): V_GlobalFunctionCall {
        val target = C_FunctionCallTarget_CollectionConstructorFunction(ctx, name, resTypeHint)
        val vCall = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, target)
        return vCall ?: C_ExprUtils.errorVGlobalCall(ctx, name.pos)
    }

    private fun requireType(pos: S_Pos, rType: R_Type?): R_Type {
        return C_Errors.checkNotNull(rType, pos) {
            "expr_${colType}_notype" toCodeMsg "Element type not specified for '$colType'"
        }
    }

    private inner class C_FunctionCallTarget_CollectionConstructorFunction(
        private val ctx: C_ExprContext,
        private val name: LazyPosString,
        private val resTypeHint: C_TypeHint,
    ): C_FunctionCallTarget() {
        override fun retType() = null
        override fun typeHints(): C_CallTypeHints = C_CallTypeHints_None
        override fun hasParameter(name: R_Name) = false

        override fun compileFull(args: C_FullCallArguments): V_GlobalFunctionCall? {
            val vArgs = args.compileSimpleArgs(LazyString.of(colType))
            return if (vArgs.size == 0) {
                compileNoArgs(ctx, resTypeHint, rExplicitElementType)
            } else if (vArgs.size == 1) {
                val vArg = vArgs[0]
                compileOneArg(ctx, rExplicitElementType, vArg)
            } else {
                ctx.msgCtx.error(name.pos, "expr_${colType}_argcnt:${vArgs.size}",
                    "Wrong number of arguments for '$colType': ${vArgs.size}")
                null
            }
        }

        private fun compileNoArgs(
            ctx: C_ExprContext,
            typeHint: C_TypeHint,
            rType: R_Type?
        ): V_GlobalFunctionCall {
            val elemType = rType ?: kindAdapter.elementTypeFromTypeHint(typeHint)
            val rTypeReq = requireType(name.pos, elemType)
            val kind = kindAdapter.makeKind(rTypeReq)
            val vExpr = V_EmptyCollectionConstructorExpr(ctx, name.pos, kind)
            return V_GlobalFunctionCall(vExpr)
        }

        private fun compileOneArg(
            ctx: C_ExprContext,
            rType: R_Type?,
            vArg: V_Expr
        ): V_GlobalFunctionCall {
            val rArgType = vArg.type
            val cIterator = C_ForIterator.compile(ctx, rArgType, false)

            if (cIterator == null) {
                throw C_Error.more(name.pos, "expr_${colType}_badtype:${rArgType.strCode()}",
                    "Wrong argument type for '$colType': ${rArgType.strCode()}")
            }

            val rElementType = C_Lib_Type_Collection.checkElementType(
                name.pos,
                rType,
                cIterator.itemType,
                "expr_${colType}_typemiss",
                "Element type mismatch for '$colType'"
            )

            if (rType == null) {
                kindAdapter.checkElementType(ctx.nsCtx, name.pos, vArg.pos, cIterator.itemType)
            }

            val kind = kindAdapter.makeKind(rElementType)
            val vExpr = V_CopyCollectionConstructorExpr(ctx, name.pos, kind, vArg, cIterator)
            return V_GlobalFunctionCall(vExpr)
        }

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_GlobalFunctionCall? {
            args.errPartialNotSupported(colType)
            return null
        }
    }
}

private object ColFns {
    val Empty = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asCollection()
        Rt_BooleanValue(col.isEmpty())
    }

    val Size = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asCollection()
        Rt_IntValue(col.size.toLong())
    }

    val Contains = C_SysFunction.simple2(pure = true) { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.contains(b))
    }

    val Clear = C_SysFunction.simple1 { a ->
        val col = a.asCollection()
        col.clear()
        Rt_UnitValue
    }

    val Remove = C_SysFunction.simple2 { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.remove(b))
    }

    val Add = C_SysFunction.simple2 { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.add(b))
    }

    val AddAll = C_SysFunction.simple2 { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.addAll(b.asCollection()))
    }

    val RemoveAll = C_SysFunction.simple2 { a, b ->
        val col1 = a.asCollection()
        val col2 = b.asCollection()
        Rt_BooleanValue(col1.removeAll(col2))
    }

    val ContainsAll = C_SysFunction.simple2(pure = true) { a, b ->
        val col1 = a.asCollection()
        val col2 = b.asCollection()
        Rt_BooleanValue(col1.containsAll(col2))
    }

    fun Sorted(type: R_Type, comparator: Comparator<Rt_Value>) = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asCollection()
        val copy = ArrayList(col)
        copy.sortWith(comparator)
        Rt_ListValue(type, copy)
    }
}

object C_Lib_Type_VirtualCollection {
    fun bindMemberFns(b: C_MemberFuncBuilder, innerType: R_CollectionType) {
        b.add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("empty", R_BooleanType, listOf(), VirColFns.Empty)
        b.add("size", R_IntegerType, listOf(), VirColFns.Size)
        b.add("to_full", innerType, listOf(), C_Lib_Type_Virtual.ToFull)
    }
}

private object VirColFns {
    val Empty = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asVirtualCollection()
        Rt_BooleanValue(col.size() == 0)
    }

    val Size = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asVirtualCollection()
        Rt_IntValue(col.size().toLong())
    }
}
