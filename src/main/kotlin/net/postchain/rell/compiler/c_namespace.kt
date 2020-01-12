package net.postchain.rell.compiler

import net.postchain.rell.Getter
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.toImmList
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

enum class C_DeclarationType(val msg: String) {
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
    ;

    val msgCap = StringUtils.capitalize(msg)
}

sealed class C_DefProxy<T> {
    abstract fun getDef(globalCtx: C_GlobalContext, name: List<S_Name>): T

    companion object {
        fun <T> create(def: T): C_DefProxy<T> = C_SimpleDefProxy(def)
        fun <T> createGetter(getter: Getter<T>): C_DefProxy<T> = C_GetterDefProxy(getter)

        fun create(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<R_Type> {
            return create(C_DeclarationType.TYPE, deprecated, type)
        }

        fun create(namespace: C_Namespace, deprecated: C_Deprecated? = null): C_DefProxy<C_Namespace> {
            return create(C_DeclarationType.NAMESPACE, deprecated, namespace)
        }

        fun <T> create(type: C_DeclarationType, deprecated: C_Deprecated?, def: T): C_DefProxy<T> {
            return create0(C_SimpleDefProxy(def), type, deprecated)
        }

        fun <T> create(type: C_DeclarationType, deprecated: C_Deprecated?, getter: Getter<T>): C_DefProxy<T> {
            return create0(C_GetterDefProxy(getter), type, deprecated)
        }

        private fun <T> create0(proxy: C_DefProxy<T>, type: C_DeclarationType, deprecated: C_Deprecated?): C_DefProxy<T> {
            return if (deprecated == null) proxy else C_DeprecatedDefProxy(proxy, type, deprecated)
        }
    }
}

private sealed class C_BasicDefProxy<T>: C_DefProxy<T>() {
    protected abstract fun def(): T
    final override fun getDef(globalCtx: C_GlobalContext, name: List<S_Name>) = def()
}

private class C_SimpleDefProxy<T>(private val def: T): C_BasicDefProxy<T>() {
    override fun def() = def
}

private class C_GetterDefProxy<T>(private val getter: Getter<T>): C_BasicDefProxy<T>() {
    override fun def() = getter()
}

class C_DeprecatedDefProxy<T>(
        private val proxy: C_DefProxy<T>,
        private val type: C_DeclarationType,
        private val deprecated: C_Deprecated
): C_DefProxy<T>() {
    override fun getDef(globalCtx: C_GlobalContext, name: List<S_Name>): T {
        val simpleName = name.last()
        deprecatedMessage(globalCtx, type, simpleName.pos, simpleName.str, deprecated)
        val res = proxy.getDef(globalCtx, name)
        return res
    }

    companion object {
        fun deprecatedMessage(
                globalCtx: C_GlobalContext,
                type: C_DeclarationType,
                pos: S_Pos,
                name: String,
                deprecated: C_Deprecated
        ) {
            val typeStr = StringUtils.capitalize(type.msg)
            val depCode = deprecated.detailsCode()
            val depStr = deprecated.detailsMessage()
            val code = "deprecated:$type:$name$depCode"
            val msg = "$typeStr '$name' is deprecated$depStr"

            val error = deprecated.error || globalCtx.compilerOptions.deprecatedError
            val msgType = if (error) C_MessageType.ERROR else C_MessageType.WARNING

            globalCtx.message(msgType, pos, code, msg)
        }
    }
}

class C_AmbiguousDefProxy<T>(private val proxy: C_DefProxy<T>): C_DefProxy<T>() {
    override fun getDef(globalCtx: C_GlobalContext, name: List<S_Name>): T {
        val lastName = name.last()
        val qName = C_Utils.nameStr(name)
        globalCtx.error(lastName.pos, "name:ambig:$qName", "Name '$qName' is ambiguous")

        val res = proxy.getDef(globalCtx, name)
        return res
    }
}

class C_DefRef<T>(
        val globalCtx: C_GlobalContext,
        name: List<S_Name>,
        val proxy: C_DefProxy<T>
) {
    val name = name.toImmList()

    init {
        check(this.name.isNotEmpty())
    }

    fun getDef(): T {
        val res = proxy.getDef(globalCtx, name)
        return res
    }

    fun <R> sub(subName: S_Name, subProxy: C_DefProxy<R>) = C_DefRef(globalCtx, name + subName, subProxy)
}

class C_NamespaceRef(
        private val globalCtx: C_GlobalContext,
        private val path: List<S_Name>,
        private val ns: C_Namespace
) {
    fun namespace(name: S_Name) = get(name, C_Namespace::namespace)
    fun type(name: S_Name) = get(name, C_Namespace::type)
    fun value(name: S_Name) = get(name, C_Namespace::value)
    fun function(name: S_Name) = get(name, C_Namespace::function)

    private fun <T> get(name: S_Name, getter: (C_Namespace, String) -> C_DefProxy<T>?): C_DefRef<T>? {
        val proxy = getter(ns, name.str)
        return wrap(name, proxy)
    }

    private fun <T> wrap(name: S_Name, proxy: C_DefProxy<T>?): C_DefRef<T>? {
        return if (proxy == null) null else C_DefRef(globalCtx, path + name, proxy)
    }

    companion object {
        fun create(globalCtx: C_GlobalContext, path: List<S_Name>, proxy: C_DefProxy<C_Namespace>): C_NamespaceRef {
            return C_NamespaceRef(globalCtx, path, proxy.getDef(globalCtx, path))
        }

        fun create(ref: C_DefRef<C_Namespace>): C_NamespaceRef {
            val ns = ref.getDef()
            return C_NamespaceRef(ref.globalCtx, ref.name, ns)
        }
    }
}

class C_Namespace(
        namespaces: Map<String, C_DefProxy<C_Namespace>>,
        types: Map<String, C_DefProxy<R_Type>>,
        values: Map<String, C_DefProxy<C_NamespaceValue>>,
        functions: Map<String, C_DefProxy<C_GlobalFunction>>
) {
    private val namespaces = namespaces.toImmMap()
    private val types = types.toImmMap()
    private val values = values.toImmMap()
    private val functions = functions.toImmMap()

    fun namespace(name: String) = namespaces[name]
    fun type(name: String) = types[name]
    fun value(name: String) = values[name]
    fun function(name: String) = functions[name]

    companion object {
        val EMPTY = C_Namespace(namespaces = mapOf(), types = mapOf(), values = mapOf(), functions = mapOf())
    }
}

class C_NamespaceElement(
        val namespace: C_DefProxy<C_Namespace>? = null,
        val type: C_DefProxy<R_Type>? = null,
        val value: C_DefProxy<C_NamespaceValue>? = null,
        val function: C_DefProxy<C_GlobalFunction>? = null
) {
    companion object {
        fun create(
                namespace: C_DefProxy<C_Namespace>? = null,
                type: C_DefProxy<R_Type>? = null,
                value: C_NamespaceValue? = null,
                function: C_GlobalFunction? = null
        ): C_NamespaceElement {
            return C_NamespaceElement(
                    namespace = namespace,
                    type = type,
                    value = if (value == null) null else C_DefProxy.create(value),
                    function = if (function == null) null else C_DefProxy.create(function)
            )
        }
    }
}

class C_NamespaceBuilder {
    private val namespaces = mutableMapOf<String, C_DefProxy<C_Namespace>>()
    private val types = mutableMapOf<String, C_DefProxy<R_Type>>()
    private val values = mutableMapOf<String, C_DefProxy<C_NamespaceValue>>()
    private val functions = mutableMapOf<String, C_DefProxy<C_GlobalFunction>>()

    fun add(name: String, elem: C_NamespaceElement) {
        if (elem.namespace != null) addNamespace(name, elem.namespace)
        if (elem.type != null) addType(name, elem.type)
        if (elem.value != null) addValue(name, elem.value)
        if (elem.function != null) addFunction(name, elem.function)
    }

    fun addNamespace(name: String, ns: C_DefProxy<C_Namespace>) {
        check(name !in namespaces)
        namespaces[name] = ns
    }

    fun addType(name: String, type: C_DefProxy<R_Type>) {
        check(name !in types)
        types[name] = type
    }

    fun addValue(name: String, value: C_DefProxy<C_NamespaceValue>) {
        check(name !in values)
        values[name] = value
    }

    fun addFunction(name: String, fn: C_DefProxy<C_GlobalFunction>) {
        check(name !in functions)
        functions[name] = fn
    }

    fun build(): C_Namespace {
        return C_Namespace(namespaces.toMap(), types.toMap(), values.toMap(), functions.toMap())
    }
}

abstract class C_NamespaceValue {
    abstract fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr
}

abstract class C_NamespaceValue_RExpr: C_NamespaceValue() {
    abstract fun toExpr0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr

    override final fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        val rExpr = toExpr0(defCtx, name)
        return C_RValue.makeExpr(name[0].pos, rExpr)
    }
}

class C_NamespaceValue_Value(private val value: Rt_Value): C_NamespaceValue_RExpr() {
    override fun toExpr0(defCtx: C_DefinitionContext, name: List<S_Name>) = R_ConstantExpr(value)
}

class C_NamespaceValue_SysFunction(
        private val resultType: R_Type,
        private val fn: R_SysFunction
): C_NamespaceValue_RExpr() {
    override fun toExpr0(defCtx: C_DefinitionContext, name: List<S_Name>): R_Expr {
        return C_Utils.createSysCallExpr(resultType, fn, listOf(), name)
    }
}

class C_NamespaceValue_Entity(private val typeProxy: C_DefProxy<R_Type>): C_NamespaceValue() {
    override fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        val typeRef = C_DefRef(defCtx.globalCtx, name, typeProxy)
        return C_TypeNameExpr(name.last().pos, typeRef)
    }
}

class C_NamespaceValue_Enum(private val rEnum: R_Enum): C_NamespaceValue() {
    override fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>) = C_EnumExpr(defCtx.globalCtx, name, rEnum)
}

class C_NamespaceValue_Namespace(private val nsProxy: C_DefProxy<C_Namespace>): C_NamespaceValue() {
    override fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        val nsRef = C_NamespaceRef.create(defCtx.globalCtx, name, nsProxy)
        return C_NamespaceExpr(name, nsRef)
    }
}

class C_NamespaceValue_Object(private val rObject: R_Object): C_NamespaceValue() {
    override fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        return C_ObjectExpr(name, rObject)
    }
}

class C_NamespaceValue_Struct(private val struct: R_Struct): C_NamespaceValue() {
    override fun toExpr(defCtx: C_DefinitionContext, name: List<S_Name>): C_Expr {
        val nsProxy = C_LibFunctions.makeStructNamespace(struct)
        val nsRef = C_NamespaceRef.create(defCtx.globalCtx, name, nsProxy)
        return C_StructExpr(name, struct, nsRef)
    }
}
