package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.toImmMap
import org.apache.commons.lang3.StringUtils

class C_Deprecated(
        private val useInstead: String,
        val error: Boolean = false
) {
    fun detailsCode(): String {
        return ":$useInstead"
    }

    fun detailsMessage(): String {
        return ", use '$useInstead' instead"
    }
}

enum class C_DeclarationType(val description: String) {
    MODULE("module"),
    NAMESPACE("namespace"),
    TYPE("type"),
    ENTITY("entity"),
    STRUCT("struct"),
    ENUM("enum"),
    OBJECT("object"),
    FUNCTION("function"),
    OPERATION("operation"),
    QUERY("query"),
    IMPORT("import")
}

abstract class C_Def<T>(private val type: C_DeclarationType, private val deprecated: C_Deprecated?) {
    protected abstract fun def(): T

    fun useDef(modCtx: C_ModuleContext, name: List<S_Name>): T {
        if (deprecated != null) {
            val simpleName = name.last()
            deprecatedMessage(modCtx, type, simpleName.pos, simpleName.str, deprecated)
        }
        val res = def()
        return res
    }

    companion object {
        fun deprecatedMessage(
                modCtx: C_ModuleContext,
                type: C_DeclarationType,
                pos: S_Pos,
                name: String,
                deprecated: C_Deprecated
        ) {
            val typeStr = StringUtils.capitalize(type.description)
            val depCode = deprecated.detailsCode()
            val depStr = deprecated.detailsMessage()
            val code = "deprecated:$type:$name$depCode"
            val msg = "$typeStr '$name' is deprecated$depStr"

            val globalCtx = modCtx.globalCtx
            val error = deprecated.error || globalCtx.compilerOptions.deprecatedError
            val msgType = if (error) C_MessageType.ERROR else C_MessageType.WARNING

            globalCtx.message(msgType, pos, code, msg)
        }
    }
}

class C_TypeDef(private val type: R_Type, deprecated: C_Deprecated? = null)
    : C_Def<R_Type>(C_DeclarationType.TYPE, deprecated)
{
    override fun def() = type
}

abstract class C_NamespaceDef(deprecated: C_Deprecated?): C_Def<C_Namespace>(C_DeclarationType.NAMESPACE, deprecated)

class C_RegularNamespaceDef(private val namespace: C_Namespace, deprecated: C_Deprecated? = null)
    : C_NamespaceDef(deprecated)
{
    override fun def() = namespace
}

class C_ImportNamespaceDef(private val module: C_Module): C_NamespaceDef(null) {
    override fun def() = module.contents().namespace
}

class C_Namespace(
        namespaces: Map<String, C_NamespaceDef>,
        types: Map<String, C_TypeDef>,
        values: Map<String, C_NamespaceValue>,
        functions: Map<String, C_GlobalFunction>
) {
    val namespaces = namespaces.toImmMap()
    val types = types.toImmMap()
    val values = values.toImmMap()
    val functions = functions.toImmMap()

    companion object {
        val EMPTY = C_Namespace(namespaces = mapOf(), types = mapOf(), values = mapOf(), functions = mapOf())
    }
}

class C_NamespaceBuilder {
    private val namespaces = mutableMapOf<String, C_NamespaceDef>()
    private val types = mutableMapOf<String, C_TypeDef>()
    private val values = mutableMapOf<String, C_NamespaceValue>()
    private val functions = mutableMapOf<String, C_GlobalFunction>()

    fun addNamespace(name: String, ns: C_NamespaceDef) {
        check(name !in namespaces)
        namespaces[name] = ns
    }

    fun addType(name: String, type: C_TypeDef) {
        check(name !in types)
        types[name] = type
    }

    fun addValue(name: String, value: C_NamespaceValue) {
        check(name !in values)
        values[name] = value
    }

    fun addFunction(name: String, fn: C_GlobalFunction) {
        check(name !in functions)
        functions[name] = fn
    }

    fun build(): C_Namespace {
        return C_Namespace(namespaces.toMap(), types.toMap(), values.toMap(), functions.toMap())
    }
}

abstract class C_NamespaceValue {
    abstract fun get(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr
}

abstract class C_NamespaceValue_RExpr: C_NamespaceValue() {
    abstract fun get0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr

    override final fun get(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        val rExpr = get0(defCtx, name)
        return C_RValue.makeExpr(name[0].pos, rExpr)
    }
}

class C_NamespaceValue_Value(private val value: Rt_Value): C_NamespaceValue_RExpr() {
    override fun get0(defCtx: C_DefinitionContext, name: List<S_Name>) = R_ConstantExpr(value)
}

class C_NamespaceValue_SysFunction(
        private val resultType: R_Type,
        private val fn: R_SysFunction
): C_NamespaceValue_RExpr() {
    override fun get0(defCtx: C_DefinitionContext, name: List<S_Name>) = R_SysCallExpr(resultType, fn, listOf())
}

class C_NamespaceValue_Entity(private val typeDef: C_TypeDef): C_NamespaceValue() {
    override fun get(defCtx: C_DefinitionContext, name: List<S_Name>) = C_TypeNameExpr(name.last().pos, name, typeDef)
}

class C_NamespaceValue_Enum(private val rEnum: R_Enum): C_NamespaceValue() {
    override fun get(defCtx: C_DefinitionContext, name: List<S_Name>) = C_EnumExpr(name, rEnum)
}

class C_NamespaceValue_Namespace(private val nsDef: C_NamespaceDef): C_NamespaceValue() {
    override fun get(defCtx: C_DefinitionContext, name: List<S_Name>) = C_NamespaceExpr(name, nsDef)
}

class C_NamespaceValue_Object(private val rObject: R_Object): C_NamespaceValue() {
    override fun get(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        return C_ObjectExpr(name, rObject)
    }
}

class C_NamespaceValue_Struct(private val struct: R_Struct): C_NamespaceValue() {
    override fun get(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        val nsDef = C_LibFunctions.makeStructNamespace(struct)
        return C_StructExpr(name, struct, nsDef)
    }
}
