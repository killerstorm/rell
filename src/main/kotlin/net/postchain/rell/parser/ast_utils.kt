package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.model.*
import java.util.*

object C_Utils {
    fun parse(sourceCode: String): S_ModuleDefinition {
        val tokenSeq = S_Grammar.tokenizer.tokenize(sourceCode)

        // The syntax error position returned by the parser library is misleading: if there is an error in the middle
        // of an operation, it returns the position of the beginning of the operation.
        // Following workaround handles this by tracking the position of the farthest reached token (seems to work fine).

        var maxPos = 0
        var maxRowCol = S_Pos(1, 1)
        val tokenSeq2 = tokenSeq.map {
            if (!it.type.ignored && it.position > maxPos) {
                maxPos = it.position
                maxRowCol = S_Pos(it)
            }
            it
        }

        try {
            val ast = S_Grammar.parseToEnd(tokenSeq2)
            return ast
        } catch (e: ParseException) {
            throw C_Error(maxRowCol, "syntax", "Syntax error")
        }
    }

    fun checkUnitType(pos: S_Pos, type: RType, errCode: String, errMsg: String) {
        if (type == RUnitType) {
            throw C_Error(pos, errCode, errMsg)
        }
    }

    fun checkMapKeyType(pos: S_Pos, type: RType) {
        checkMapKeyType0(pos, type, "expr_map_keytype", "map key")
    }

    fun checkSetElementType(pos: S_Pos, type: RType) {
        checkMapKeyType0(pos, type, "expr_set_type", "set element")
    }

    private fun checkMapKeyType0(pos: S_Pos, type: RType, errCode: String, errMsg: String) {
        if (type.completeFlags().mutable) {
            val typeStr = type.toStrictString()
            throw C_Error(pos, "$errCode:$typeStr", "Mutable type cannot be used as $errMsg: $typeStr")
        }
    }

    fun errTypeMissmatch(pos: S_Pos, srcType: RType, dstType: RType, errCode: String, errMsg: String): C_Error {
        return C_Error(pos, "$errCode:${dstType.toStrictString()}:${srcType.toStrictString()}",
                "$errMsg: ${srcType.toStrictString()} instead of ${dstType.toStrictString()}")
    }

    fun errMutlipleAttrs(pos: S_Pos, attrs: List<DbClassAttr>, errCode: String, errMsg: String): C_Error {
        val attrsLst = attrs.map { it.cls.alias + "." + it.attr.name }
        return C_Error(pos, "$errCode:${attrsLst.joinToString(",")}", "$errMsg: ${attrsLst.joinToString()}")
    }

    fun errUnknownName(name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_name:${name.str}", "Unknown name: '${name.str}'")
    }

    fun errUnknownName(name1: S_Name, name2: S_Name): C_Error {
        return C_Error(name1.pos, "unknown_name:${name1.str}.${name2.str}", "Unknown name: '${name1.str}.${name2.str}'")
    }

    fun errUnknownAttr(name: S_Name): C_Error {
        val nameStr = name.str
        return C_Error(name.pos, "expr_attr_unknown:$nameStr", "Unknown attribute: '$nameStr'")
    }

    fun errUnknownFunction(name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_fn:${name.str}", "Unknown function: '${name.str}'")
    }

    fun errUnknownFunction(name1: S_Name, name2: S_Name): C_Error {
        return C_Error(name1.pos, "unknown_fn:${name1.str}.${name2.str}", "Unknown function: '${name1.str}.${name2.str}'")
    }

    fun errUnknownMember(type: RType, name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_member:${type.toStrictString()}:${name.str}",
                "Type ${type.toStrictString()} has no member '${name.str}'")

    }

    fun errUnknownMemberFunction(type: RType, name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_member_fn:${type.toStrictString()}:${name.str}",
                "Type ${type.toStrictString()} has no member function '${name.str}'")

    }

    fun errFunctionNoSql(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "expr_call_nosql:$name", "Function '$name' cannot be converted to SQL")
    }

    fun errBadDestination(pos: S_Pos): C_Error {
        return C_Error(pos, "expr_bad_dst", "Invalid assignment destination")
    }

    fun errAttrNotMutable(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "update_attr_not_mutable:$name", "Attribute '$name' is not mutable")
    }
}

object C_AttributeResolver {
    fun resolveCreate(
            ctx: C_ExprContext,
            attributes: Map<String, RAttrib>,
            exprs: List<S_NameExprPair>,
            pos: S_Pos
    ): List<RCreateExprAttr>
    {
        val rExprs = exprs.map { it.expr.compile(ctx) }
        val types = rExprs.map { it.type }

        val attrs = matchCreateAttrs(attributes, exprs, types)
        val attrExprs = attrs.mapIndexed { idx, attr -> RCreateExprAttr_Specified(attr, rExprs[idx]) }

        val attrExprsDef = attrExprs + matchDefaultExprs(attributes, attrExprs)
        checkMissingAttrs(attributes, attrExprsDef, pos)

        return attrExprsDef
    }

    private fun matchCreateAttrs(attributes: Map<String, RAttrib>, exprs: List<S_NameExprPair>, types: List<RType>): List<RAttrib> {
        val explicitExprs = matchExplicitExprs(attributes, exprs, false)
        checkExplicitExprTypes(exprs, explicitExprs, types)
        return matchImplicitExprs(attributes, exprs, types, explicitExprs, false)
    }

    private fun matchDefaultExprs(attributes: Map<String, RAttrib>, attrExprs: List<RCreateExprAttr>): List<RCreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return attributes.values.filter { it.hasExpr && it.name !in provided }.map { RCreateExprAttr_Default(it) }
    }

    private fun checkMissingAttrs(attributes: Map<String, RAttrib>, attrs: List<RCreateExprAttr>, pos: S_Pos) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (attributes.keys - names).sorted().toList()
        if (!missing.isEmpty()) {
            throw C_Error(pos, "attr_missing:${missing.joinToString(",")}",
                    "Attributes not specified: ${missing.joinToString()}")
        }
    }

    fun resolveUpdate(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>): List<RAttrib> {
        val explicitExprs = matchExplicitExprs(cls.attributes, exprs, true)
        return matchImplicitExprs(cls.attributes, exprs, types, explicitExprs, true)
    }

    private fun matchExplicitExprs(attributes: Map<String, RAttrib>, exprs: List<S_NameExprPair>, mutableOnly: Boolean)
            : List<IndexedValue<RAttrib>>
    {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableListOf<IndexedValue<RAttrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name != null) {
                val name = pair.name
                val attr = attributes[name.str]
                if (attr == null) {
                    throw C_Error(name.pos, "attr_unknown_name:${name.str}", "Unknown attribute: '${name.str}'")
                } else if (!explicitNames.add(name.str)) {
                    throw C_Error(name.pos, "attr_dup_name:${name.str}", "Attribute already specified: '${name.str}'")
                } else if (mutableOnly && !attr.mutable) {
                    throw C_Error(name.pos, "update_attr_not_mutable:${name.str}", "Attribute is not mutable: '${name.str}'")
                }
                explicitExprs.add(IndexedValue(idx, attr))
            }
        }

        return explicitExprs.toList()
    }

    private fun checkExplicitExprTypes(exprs: List<S_NameExprPair>, explicitExprs: List<IndexedValue<RAttrib>>, types: List<RType>) {
        for ((idx, attr) in explicitExprs) {
            val pos = exprs[idx].expr.startPos
            val type = types[idx]
            typeCheck(pos, idx, attr, type)
        }
    }

    private fun matchImplicitExprs(
            attributes: Map<String, RAttrib>,
            exprs: List<S_NameExprPair>,
            types: List<RType>,
            explicitExprs: List<IndexedValue<RAttrib>>,
            mutableOnly: Boolean
    ) : List<RAttrib>
    {
        val implicitExprs = matchImplicitExprs0(attributes, exprs, types, mutableOnly)
        val result = combineMatchedExprs(exprs, explicitExprs, implicitExprs)
        return result
    }

    private fun combineMatchedExprs(
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<RAttrib>>,
            implicitExprs: List<IndexedValue<RAttrib>>
    ) : List<RAttrib>
    {
        checkImplicitExprsConflicts1(exprs, explicitExprs, implicitExprs)
        checkImplicitExprsConflicts2(exprs, implicitExprs)

        val combinedExprs = (explicitExprs + implicitExprs).sortedBy { it.index }.toList()
        combinedExprs.withIndex().forEach { check(it.index == it.value.index ) }

        val result = combinedExprs.map { (_, attr) -> attr }.toList()
        return result
    }

    private fun matchImplicitExprs0(
            attributes: Map<String, RAttrib>,
            exprs: List<S_NameExprPair>,
            types: List<RType>,
            mutableOnly: Boolean
    ): List<IndexedValue<RAttrib>>
    {
        val result = mutableListOf<IndexedValue<RAttrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name == null) {
                val type = types[idx]
                val attr = implicitMatch(attributes, idx, pair.expr, type, mutableOnly)
                result.add(IndexedValue(idx, attr))
            }
        }

        return result.toList()
    }

    private fun checkImplicitExprsConflicts1(
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<RAttrib>>,
            implicitExprs: List<IndexedValue<RAttrib>>)
    {
        val explicitNames = explicitExprs.map { (_, attr) -> attr.name }.toSet()

        for ((idx, attr) in implicitExprs) {
            val name = attr.name
            if (name in explicitNames) {
                throw C_Error(
                        exprs[idx].expr.startPos,
                        "attr_implic_explic:$idx:$name",
                        "Expression #${idx + 1} matches attribute '$name' which is already specified"
                )
            }
        }
    }

    private fun checkImplicitExprsConflicts2(exprs: List<S_NameExprPair>, implicitExprs: List<IndexedValue<RAttrib>>) {
        val implicitConflicts = mutableMapOf<String, MutableList<Int>>()
        for ((_, attr) in implicitExprs) {
            implicitConflicts[attr.name] = mutableListOf()
        }

        for ((idx, attr) in implicitExprs) {
            implicitConflicts[attr.name]!!.add(idx)
        }

        for ((name, list) in implicitConflicts) {
            if (list.size > 1) {
                val pos = exprs[list[0]].expr.startPos
                throw C_Error(pos, "attr_implic_multi:$name:${list.joinToString(",")}",
                        "Multiple expressions match attribute '$name': ${list.joinToString { "#" + (it + 1) }}")
            }
        }
    }

    private fun implicitMatch(
            attributes: Map<String, RAttrib>,
            idx: Int,
            expr: S_Expr,
            type: RType,
            mutableOnly: Boolean
    ): RAttrib
    {
        val byName = implicitMatchByName(attributes, expr)
        if (byName != null) {
            typeCheck(expr.startPos, idx, byName, type)
            if (mutableOnly && !byName.mutable) {
                throw C_Utils.errAttrNotMutable(expr.startPos, byName.name)
            }
            return byName
        }

        val byType = implicitMatchByType(attributes, type, mutableOnly)
        if (byType.size == 1) {
            return byType[0]
        } else if (byType.size > 1) {
            throw C_Error(expr.startPos, "attr_implic_multi:$idx:${byType.joinToString(","){it.name}}",
                    "Multiple attributes match expression #${idx + 1}: ${byType.joinToString(", "){it.name}}")
        }

        throw C_Error(expr.startPos, "attr_implic_unknown:$idx",
                "Cannot find attribute for expression #${idx + 1} of type ${type.toStrictString()}")
    }

    private fun implicitMatchByName(attributes: Map<String, RAttrib>, expr: S_Expr): RAttrib? {
        val name = expr.asName()
        return if (name == null) null else attributes[name.str]
    }

    private fun implicitMatchByType(attributes: Map<String, RAttrib>, type: RType, mutableOnly: Boolean): List<RAttrib> {
        return attributes.values.filter{ it.type.isAssignableFrom(type) && (!mutableOnly || it.mutable) }.toList()
    }

    private fun typeCheck(pos: S_Pos, idx: Int, attr: RAttrib, exprType: RType) {
        if (!attr.type.isAssignableFrom(exprType)) {
            throw C_Error(
                    pos,
                    "attr_bad_type:$idx:${attr.name}:${attr.type.toStrictString()}:${exprType.toStrictString()}",
                    "Attribute type missmatch for '${attr.name}': ${exprType} instead of ${attr.type}"
            )
        }
    }
}

object C_GraphUtils {
    fun <T> findCyclicVertices(graph: Map<T, Collection<T>>): List<T> {
        class VertEntry<T>(val vert: T, val enter: Boolean, val parent: VertEntry<T>?)

        val queue = LinkedList<VertEntry<T>>()
        val visiting = mutableSetOf<T>()
        val visited = mutableSetOf<T>()
        val cycleVerts = mutableSetOf<T>()

        for (vert in graph.keys) {
            queue.add(VertEntry(vert, true, null))
        }

        while (!queue.isEmpty()) {
            val entry = queue.remove()

            if (!entry.enter) {
                check(visiting.remove(entry.vert))
                check(visited.add(entry.vert))
                continue
            } else if (entry.vert in visited) {
                check(entry.vert !in visiting)
                continue
            } else if (entry.vert in visiting) {
                var cycleEntry = entry
                while (true) {
                    cycleVerts.add(cycleEntry.vert)
                    cycleEntry = cycleEntry.parent
                    check(cycleEntry != null)
                    if (cycleEntry.vert == entry.vert) break
                }
                continue
            }

            queue.addFirst(VertEntry(entry.vert, false, entry.parent))
            visiting.add(entry.vert)

            for (adjVert in graph.getValue(entry.vert)) {
                queue.addFirst(VertEntry(adjVert, true, entry))
            }
        }

        return cycleVerts.toList()
    }

    fun <T> transpose(graph: Map<T, Collection<T>>): Map<T, Collection<T>> {
        val mut = mutableMapOf<T, MutableCollection<T>>()

        for (vert in graph.keys) {
            mut[vert] = mutableSetOf()
        }

        for (vert in graph.keys) {
            for (adjVert in graph.getValue(vert)) {
                mut.getValue(adjVert).add(vert)
            }
        }

        return mut.mapValues { (key, value) -> value.toList() }.toMap()
    }

    fun <T> closure(graph: Map<T, Collection<T>>, vertices: Collection<T>): Collection<T> {
        val queue = LinkedList(vertices)
        val visited = mutableSetOf<T>()

        while (!queue.isEmpty()) {
            val vert = queue.remove()
            if (visited.add(vert)) {
                for (adjVert in graph.getValue(vert)) {
                    queue.add(adjVert)
                }
            }
        }

        return visited.toList()
    }
}
