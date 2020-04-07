/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.utils.LateGetter
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
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

    val capitalizedMsg = StringUtils.capitalize(msg)
}

sealed class C_DefProxy<T> {
    abstract fun getDef(msgCtx: C_MessageContext, name: List<S_Name>): T
    abstract fun getDefQuiet(): T?

    companion object {
        fun <T> create(def: T): C_DefProxy<T> = C_SimpleDefProxy(def)
        fun <T> createGetter(getter: LateGetter<T>): C_DefProxy<T> = C_GetterDefProxy(getter)

        fun create(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<R_Type> {
            return create(C_DeclarationType.TYPE, deprecated, type)
        }

        fun create(namespace: C_Namespace, deprecated: C_Deprecated? = null): C_DefProxy<C_Namespace> {
            return create(C_DeclarationType.NAMESPACE, deprecated, namespace)
        }

        fun <T> create(type: C_DeclarationType, deprecated: C_Deprecated?, def: T): C_DefProxy<T> {
            return create0(C_SimpleDefProxy(def), type, deprecated)
        }

        fun <T> create(type: C_DeclarationType, deprecated: C_Deprecated?, getter: LateGetter<T>): C_DefProxy<T> {
            return create0(C_GetterDefProxy(getter), type, deprecated)
        }

        private fun <T> create0(proxy: C_DefProxy<T>, type: C_DeclarationType, deprecated: C_Deprecated?): C_DefProxy<T> {
            return if (deprecated == null) proxy else C_DeprecatedDefProxy(proxy, type, deprecated)
        }
    }
}

private sealed class C_BasicDefProxy<T>: C_DefProxy<T>() {
    protected abstract fun def(): T
    final override fun getDef(msgCtx: C_MessageContext, name: List<S_Name>) = def()
}

private class C_SimpleDefProxy<T>(private val def: T): C_BasicDefProxy<T>() {
    override fun def() = def
    override fun getDefQuiet() = def
}

private class C_GetterDefProxy<T>(private val getter: LateGetter<T>): C_BasicDefProxy<T>() {
    override fun def() = getter.get()
    override fun getDefQuiet() = getter.get()
}

class C_DeprecatedDefProxy<T>(
        private val proxy: C_DefProxy<T>,
        private val type: C_DeclarationType,
        private val deprecated: C_Deprecated
): C_DefProxy<T>() {
    override fun getDef(msgCtx: C_MessageContext, name: List<S_Name>): T {
        val simpleName = name.last()
        deprecatedMessage(msgCtx, type, simpleName.pos, simpleName.str, deprecated)
        val res = proxy.getDef(msgCtx, name)
        return res
    }

    override fun getDefQuiet() = proxy.getDefQuiet()

    companion object {
        fun deprecatedMessage(
                msgCtx: C_MessageContext,
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

            val error = deprecated.error || msgCtx.globalCtx.compilerOptions.deprecatedError
            val msgType = if (error) C_MessageType.ERROR else C_MessageType.WARNING

            msgCtx.message(msgType, pos, code, msg)
        }
    }
}

class C_AmbiguousDefProxy<T>(private val proxy: C_DefProxy<T>): C_DefProxy<T>() {
    override fun getDef(msgCtx: C_MessageContext, name: List<S_Name>): T {
        val lastName = name.last()
        val qName = C_Utils.nameStr(name)
        msgCtx.error(lastName.pos, "name:ambig:$qName", "Name '$qName' is ambiguous")

        val res = proxy.getDef(msgCtx, name)
        return res
    }

    override fun getDefQuiet() = proxy.getDefQuiet()
}

class C_DefRef<T>(
        val msgCtx: C_MessageContext,
        name: List<S_Name>,
        val proxy: C_DefProxy<T>
) {
    val name = name.toImmList()

    init {
        check(this.name.isNotEmpty())
    }

    fun getDef(): T {
        val res = proxy.getDef(msgCtx, name)
        return res
    }

    fun <R> sub(subName: S_Name, subProxy: C_DefProxy<R>) = C_DefRef(msgCtx, name + subName, subProxy)
}

class C_NamespaceRef(
        private val msgCtx: C_MessageContext,
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
        return if (proxy == null) null else C_DefRef(msgCtx, path + name, proxy)
    }

    companion object {
        fun create(msgCtx: C_MessageContext, path: List<S_Name>, proxy: C_DefProxy<C_Namespace>): C_NamespaceRef {
            return C_NamespaceRef(msgCtx, path, proxy.getDef(msgCtx, path))
        }

        fun create(ref: C_DefRef<C_Namespace>): C_NamespaceRef {
            val ns = ref.getDef()
            return C_NamespaceRef(ref.msgCtx, ref.name, ns)
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
        if (elem.namespace != null) addDef(namespaces, name, elem.namespace)
        if (elem.type != null) addDef(types, name, elem.type)
        if (elem.value != null) addDef(values, name, elem.value)
        if (elem.function != null) addDef(functions, name, elem.function)
    }

    private fun <T> addDef(map: MutableMap<String, C_DefProxy<T>>, name: String, def: C_DefProxy<T>) {
        check(name !in map)
        map[name] = def
    }

    fun build(): C_Namespace {
        return C_Namespace(namespaces.toMap(), types.toMap(), values.toMap(), functions.toMap())
    }
}

class C_NamespaceValueContext(val defCtx: C_DefinitionContext) {
    val globalCtx = defCtx.globalCtx
    val msgCtx = defCtx.msgCtx
    val modCtx = defCtx.modCtx
}

abstract class C_NamespaceValue {
    abstract fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr
}

abstract class C_NamespaceValue_RExpr: C_NamespaceValue() {
    abstract fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr

    override final fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val rExpr = toExpr0(ctx, name)
        return C_RValue.makeExpr(name[0].pos, rExpr)
    }
}

class C_NamespaceValue_Value(private val value: Rt_Value): C_NamespaceValue_RExpr() {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>) = R_ConstantExpr(value)
}

class C_NamespaceValue_SysFunction(
        private val resultType: R_Type,
        private val fn: R_SysFunction
): C_NamespaceValue_RExpr() {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
        return C_Utils.createSysCallExpr(resultType, fn, listOf(), name)
    }
}

class C_NamespaceValue_Entity(private val typeProxy: C_DefProxy<R_Type>): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val typeRef = C_DefRef(ctx.msgCtx, name, typeProxy)
        return C_TypeNameExpr(name.last().pos, typeRef)
    }
}

class C_NamespaceValue_Enum(private val rEnum: R_Enum): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>) = C_EnumExpr(ctx.msgCtx, name, rEnum)
}

class C_NamespaceValue_Namespace(private val nsProxy: C_DefProxy<C_Namespace>): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val nsRef = C_NamespaceRef.create(ctx.msgCtx, name, nsProxy)
        return C_NamespaceExpr(name, nsRef)
    }
}

class C_NamespaceValue_Object(private val rObject: R_Object): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        return C_ObjectExpr(name, rObject)
    }
}

class C_NamespaceValue_Struct(private val struct: R_Struct): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val nsProxy = C_LibFunctions.makeStructNamespace(struct)
        val nsRef = C_NamespaceRef.create(ctx.msgCtx, name, nsProxy)
        return C_StructExpr(name, struct, nsRef)
    }
}
