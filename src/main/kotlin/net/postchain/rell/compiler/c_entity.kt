package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_AttributeDefinition
import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

class C_SysAttribute(
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val expr: R_Expr? = null,
        val sqlMapping: String = name,
        val canSetInCreate: Boolean = true
) {
    fun compile(index: Int): R_Attribute {
        val exprGetter = if (expr == null) null else C_LateInit(C_CompilerPass.EXPRESSIONS, expr).getter
        return R_Attribute(
                index,
                name,
                type,
                mutable,
                canSetInCreate = canSetInCreate,
                exprGetter = exprGetter,
                sqlMapping = sqlMapping
        )
    }
}

class C_AttributeDefinition(
        val name: S_Name,
        val mutablePos: S_Pos?,
        val explicitType: R_Type?,
        val implicitType: R_Type?,
        val exprPos: S_Pos?,
        val exprGetter: C_LateGetter<R_Expr>?
) {
    fun compileType(nsCtx: C_NamespaceContext): R_Type {
        val type = explicitType ?: implicitType
        if (type == null) {
            C_Errors.errAttributeTypeUnknown(nsCtx.msgCtx, name)
        }
        return type ?: R_CtErrorType
    }
}

class C_AttributeClause(private val defCtx: C_DefinitionContext) {
    private val msgCtx = defCtx.msgCtx

    private val defs = mutableListOf<C_AttributeDefinition>()
    private var primaryDef: C_AttributeDefinition? = null

    fun addDefinition(def: C_AttributeDefinition, primary: Boolean) {
        if (primary) {
            if (primaryDef == null) {
                primaryDef = def
            } else {
                C_Errors.errDuplicateAttribute(msgCtx, def.name)
                return
            }
        }

        defs.add(def)
    }

    fun compile(index: Int): R_Attribute {
        val priDef = primaryDef ?: defs.first()

        val rType = priDef.compileType(defCtx.nsCtx)
        checkAttrType(priDef, rType)

        for (def in defs.filter { it !== priDef }) {
            checkSecondaryAttr(def, rType)
        }

        val rAttr = R_Attribute(
                index,
                priDef.name.str,
                rType,
                priDef.mutablePos != null,
                exprGetter = priDef.exprGetter
        )

        return rAttr
    }

    private fun checkSecondaryAttr(def: C_AttributeDefinition, rPrimaryType: R_Type) {
        if (def.explicitType != null && def.explicitType != rPrimaryType) {
            C_Errors.errTypeMismatch(msgCtx, def.name.pos, def.explicitType, rPrimaryType, "entity:attr:type_diff",
                    "Type of attribute '${def.name}' differs from the primary definition")
        }

        checkAttrType(def, def.explicitType)

        if (def.mutablePos != null) {
            msgCtx.error(def.mutablePos, "entity:attr:mutable_not_primary:${def.name}",
                    "Mutability can be specified only in the primary definition of the attribute '${def.name}'")
        }

        if (def.exprPos != null) {
            msgCtx.error(def.exprPos, "entity:attr:expr_not_primary:${def.name}",
                    "Default value can be specified only in the primary definition of the attribute '${def.name}'")
        }
    }

    private fun checkAttrType(attr: C_AttributeDefinition, type: R_Type?) {
        if (defCtx.definitionType.isEntityLike() && type != null && !type.sqlAdapter.isSqlCompatible()) {
            val name = attr.name
            val typeStr = type.toStrictString()
            msgCtx.error(name.pos, "entity_attr_type:$name:$typeStr", "Attribute '$name' has unallowed type: $typeStr")
        }
    }
}

class C_EntityContext(
        val defCtx: C_DefinitionContext,
        private val entityName: String,
        private val logAnnotation: Boolean,
        sysAttributes: List<C_SysAttribute>
) {
    private val sysAttributes = sysAttributes.toImmList()

    private val msgCtx = defCtx.msgCtx

    private val sysAttributeNames = sysAttributes.map { it.name }.toImmSet()
    private val userAttributes = mutableMapOf<String, C_AttributeClause>()

    private val keys = mutableListOf<R_Key>()
    private val indices = mutableListOf<R_Index>()
    private val uniqueKeys = mutableSetOf<Set<String>>()
    private val uniqueIndices = mutableSetOf<Set<String>>()

    fun addAttribute(attrDef: S_AttributeDefinition, primary: Boolean) {
        val name = attrDef.header.name
        val nameStr = name.str

        val explicitType = attrDef.header.type?.compile(defCtx.nsCtx)
        val implicitType = if (explicitType != null) null else defCtx.nsCtx.getTypeOpt(listOf(name))

        val exprPos = attrDef.expr?.startPos
        val exprGetter = processAttrExpr(name, attrDef.expr, explicitType ?: implicitType)

        val cAttrDef = C_AttributeDefinition(name, attrDef.mutablePos, explicitType, implicitType, exprPos, exprGetter)

        val defType = defCtx.definitionType
        if (defType.isEntityLike() && !C_EntityAttrRef.isAllowedRegularAttrName(nameStr)) {
            msgCtx.error(name.pos, "unallowed_attr_name:$nameStr", "Unallowed attribute name: '$nameStr'")
        }

        if (attrDef.mutablePos != null && logAnnotation) {
            val ann = C_Constants.LOG_ANNOTATION
            msgCtx.error(name.pos, "entity_attr_mutable_log:$entityName:$nameStr",
                    "Entity '$entityName' cannot have mutable attributes because of the '$ann' annotation")
        }

        if (nameStr in sysAttributeNames) {
            C_Errors.errDuplicateAttribute(msgCtx, attrDef.header.name)
        } else {
            val cAttrClause = userAttributes.computeIfAbsent(nameStr) { C_AttributeClause(defCtx) }
            cAttrClause.addDefinition(cAttrDef, primary)
        }

        if (defType == C_DefinitionType.OBJECT && attrDef.expr == null) {
            msgCtx.error(name.pos, "object_attr_novalue:$entityName:$nameStr",
                    "Object attribute '$entityName.$nameStr' must have a default value")
        }
    }

    private fun processAttrExpr(name: S_Name, expr: S_Expr?, type: R_Type?): C_LateGetter<R_Expr>? {
        if (expr == null) {
            return null
        }

        val exprType = type ?: R_CtErrorType
        val late = C_LateInit(C_CompilerPass.EXPRESSIONS, C_Utils.errorRExpr(exprType))

        defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val rExpr0 = expr.compile(defCtx.defExprCtx, C_TypeHint.ofType(exprType)).value().toRExpr()
            val adapter = C_Types.adaptSafe(msgCtx, exprType, rExpr0.type, name.pos, "attr_type:$name",
                    "Default value type mismatch for '$name'")
            val rExpr = adapter.adaptExpr(rExpr0)
            late.set(rExpr)
        }

        return late.getter
    }

    fun addKey(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueKeys, names, "entity_key_dup", "Duplicate key")
        keys.add(R_Key(names))
    }

    fun addIndex(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueIndices, names, "entity_index_dup", "Duplicate index")
        indices.add(R_Index(names))
    }

    fun createEntityBody(): R_EntityBody {
        val rAttributes = compileAttributes()
        return R_EntityBody(keys.toList(), indices.toList(), rAttributes)
    }

    fun createStructBody(): Map<String, R_Attribute> {
        return compileAttributes()
    }

    private fun compileAttributes(): Map<String, R_Attribute> {
        val rAttrs = mutableListOf<R_Attribute>()

        for (attr in sysAttributes) {
            rAttrs.add(attr.compile(rAttrs.size))
        }

        for (attr in userAttributes.values) {
            rAttrs.add(attr.compile(rAttrs.size))
        }

        return rAttrs.map { it.name to it }.toMap()
    }

    private fun addUniqueKeyIndex(pos: S_Pos, set: MutableSet<Set<String>>, names: List<String>, errCode: String, errMsg: String) {
        if (defCtx.definitionType == C_DefinitionType.OBJECT) {
            throw C_Error.stop(pos, "object_keyindex:${entityName}", "Object cannot have key or index")
        }

        val nameSet = names.toSet()
        if (!set.add(nameSet)) {
            val nameLst = names.sorted()
            throw C_Error.stop(pos, "$errCode:${nameLst.joinToString(",")}", "$errMsg: ${nameLst.joinToString()}")
        }
    }
}
