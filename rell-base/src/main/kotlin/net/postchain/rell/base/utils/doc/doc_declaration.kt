/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils.doc

import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.utils.C_DocUtils
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.lmodel.dsl.Ld_DocSymbols
import net.postchain.rell.base.model.*
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList

abstract class DocDeclaration {
    val code: DocCode by lazy { genCode() }

    protected abstract fun genCode(): DocCode

    final override fun toString() = code.strCode()

    companion object {
        val NONE: DocDeclaration = DocDeclaration_None
    }
}

private object DocDeclaration_None: DocDeclaration() {
    override fun genCode() = DocCode.EMPTY
}

abstract class DocDeclaration_Annotated(private val modifiers: DocModifiers): DocDeclaration() {
    protected abstract fun genCode0(b: DocCode.Builder)

    final override fun genCode(): DocCode {
        val b = DocCode.builder()
        modifiers.appendTo(b)
        genCode0(b)
        return b.build()
    }
}

class DocDeclaration_Module(
    modifiers: DocModifiers,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("module")
    }
}

class DocDeclaration_ImportModule(
    modifiers: DocModifiers,
    private val moduleName: R_ModuleName,
    private val alias: R_Name?,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("import")
        b.raw(" ")
        if (alias != null) {
            b.raw(alias.str)
            b.sep(": ")
        }
        DocDecUtils.appendModuleName(b, moduleName)
    }
}

class DocDeclaration_ImportWildcard(
    modifiers: DocModifiers,
    private val moduleName: R_ModuleName,
    private val alias: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("import")
        b.raw(" ")
        b.raw(alias.str)
        b.sep(": ")
        DocDecUtils.appendModuleName(b, moduleName)
        b.raw(".*")
    }
}

class DocDeclaration_ImportExactModule(
    modifiers: DocModifiers,
    private val moduleName: R_ModuleName,
    private val alias: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("import")
        b.raw(" ")
        b.raw(alias.str)
        b.sep(": ")
        DocDecUtils.appendModuleName(b, moduleName)
        b.raw(".")
        b.raw("{...}")
    }
}

class DocDeclaration_ImportExactAlias(
    modifiers: DocModifiers,
    private val moduleName: R_ModuleName,
    private val qualifiedName: R_QualifiedName,
    private val namespaceAlias: R_Name?,
    private val exactAlias: R_Name,
    private val wildcard: Boolean,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("import")
        b.raw(" ")

        if (namespaceAlias != null) {
            b.raw(namespaceAlias.str)
            b.sep(": ")
        }

        DocDecUtils.appendModuleName(b, moduleName)
        b.raw(".")
        b.raw("{")
        b.raw(exactAlias.str)
        b.sep(": ")
        b.link(qualifiedName.str())

        if (wildcard) {
            b.raw(".*")
        }

        b.raw("}")
    }
}

class DocDeclaration_Namespace(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("namespace")
        b.raw(" ")
        b.raw(simpleName.str)
    }
}

class DocDeclaration_Constant(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
    private val mType: M_Type,
    private val rValue: Rt_Value?,
) : DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("val")
        b.raw(" ")
        b.raw(simpleName.str)
        b.sep(": ")
        L_TypeUtils.docCode(b, mType)

        val valueCode = if (rValue == null) null else C_DocUtils.valueToDoc(rValue)
        if (valueCode != null) {
            b.sep(" = ")
            b.append(valueCode)
        }
    }
}

class DocDeclaration_Property(
    private val simpleName: R_Name,
    private val mType: M_Type,
    private val pure: Boolean,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()

        if (pure) {
            b.keyword("pure")
            b.raw(" ")
        }

        b.raw(simpleName.str)
        b.sep(": ")
        L_TypeUtils.docCode(b, mType)

        return b.build()
    }
}

class DocDeclaration_SpecialProperty(private val simpleName: R_Name): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()
        b.raw(simpleName.str)
        return b.build()
    }
}

class DocDeclaration_Enum(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("enum")
        b.raw(" ")
        b.raw(simpleName.str)
    }
}

class DocDeclaration_EnumValue(private val simpleName: R_Name): DocDeclaration() {
    override fun genCode(): DocCode {
        return DocCode.raw(simpleName.str)
    }
}

class DocDeclaration_Entity(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("entity")
        b.sep(" ")
        b.raw(simpleName.str)
    }
}

class DocDeclaration_EntityAttribute(
    private val simpleName: R_Name,
    private val mType: M_Type,
    private val isMutable: Boolean,
    private val keyIndexKind: R_KeyIndexKind?,
    private val vExpr: V_Expr? = null,
    private val keys: Collection<R_Key> = immListOf(),
    private val indices: Collection<R_Index> = immListOf(),
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()

        if (isMutable) {
            b.keyword("mutable")
            b.raw(" ")
        }

        if (keyIndexKind != null) {
            b.keyword(keyIndexKind.code)
            b.raw(" ")
        }

        b.raw(simpleName.str)

        b.sep(": ")
        L_TypeUtils.docCode(b, mType)

        if (vExpr != null) {
            b.sep(" = ")
            C_DocUtils.exprToDoc(b, vExpr)
        }

        if (keys.isNotEmpty() || indices.isNotEmpty()) {
            b.newline()
            appendKeysIndices(b, keys, "key")
            appendKeysIndices(b, indices, "index")
        }

        return b.build()
    }

    private fun appendKeysIndices(b: DocCode.Builder, col: Collection<R_KeyIndex>, kw: String) {
        for (ki in col) {
            b.newline()
            b.keyword(kw)
            b.raw(" ")
            for ((i, name) in ki.attribs.withIndex()) {
                if (i > 0) b.sep(", ")
                b.link(name.str)
            }
        }
    }
}

class DocDeclaration_Object(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("object")
        b.raw(" ")
        b.raw(simpleName.str)
    }
}

class DocDeclaration_Struct(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("struct")
        b.raw(" ")
        b.raw(simpleName.str)
    }
}

class DocDeclaration_StructAttribute(
    private val simpleName: R_Name,
    private val mType: M_Type,
    private val isMutable: Boolean,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()

        if (isMutable) {
            b.keyword("mutable")
            b.raw(" ")
        }

        b.raw(simpleName.str)
        b.sep(": ")
        L_TypeUtils.docCode(b, mType)

        return b.build()
    }
}

class DocDeclaration_Parameter(
    private val mParam: M_FunctionParam,
    private val isLazy: Boolean,
    private val implies: L_ParamImplication?,
    private val expr: V_Expr?,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()

        if (implies != null) {
            b.raw("@implies(")
            b.raw(implies.name)
            b.raw(") ")
        }

        if (mParam.exact) b.keyword("exact").raw(" ")
        if (mParam.nullable) b.keyword("nullable").raw(" ")
        if (isLazy) b.keyword("lazy").raw(" ")

        val arity = when (mParam.arity) {
            M_ParamArity.ONE -> null
            M_ParamArity.ZERO_ONE -> "zero_one"
            M_ParamArity.ZERO_MANY -> "zero_many"
            M_ParamArity.ONE_MANY -> "one_many"
        }
        if (arity != null) b.keyword(arity).raw(" ")

        if (mParam.name != null) {
            b.raw(mParam.name)
            b.sep(": ")
        }

        L_TypeUtils.docCode(b, mParam.type)

        if (expr != null) {
            b.sep(" = ")
            C_DocUtils.exprToDoc(b, expr)
        }

        return b.build()
    }
}

class DocDeclaration_Function(
    docModifiers: DocModifiers,
    private val simpleName: R_Name,
    private val mHeader: M_FunctionHeader,
    private val params: List<Lazy<DocDeclaration>>,
    private val hasBody: Boolean? = null,
): DocDeclaration_Annotated(docModifiers) {
    init {
        checkEquals(params.size, mHeader.params.size) { simpleName.str }
    }

    override fun genCode0(b: DocCode.Builder) {
        b.keyword("function")
        b.raw(" ")

        if (mHeader.typeParams.isNotEmpty()) {
            DocDecUtils.appendTypeParams(b, mHeader.typeParams)
            b.raw(" ")
        }

        b.raw(simpleName.str)

        DocDecUtils.appendFunctionParams(b, params)

        b.sep(": ")
        L_TypeUtils.docCode(b, mHeader.resultType)

        if (hasBody != null) {
            if (hasBody) {
                b.newline()
                b.raw("{...}")
            } else {
                b.raw(";")
            }
        }
    }
}

class DocDeclaration_SpecialFunction(private val simpleName: R_Name): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()
        b.keyword("function")
        b.raw(" ")
        b.raw(simpleName.str)
        b.raw("(...)")
        return b.build()
    }
}

class DocDeclaration_Operation(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
    private val params: List<Lazy<DocDeclaration>>,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("operation")
        b.raw(" ")
        b.raw(simpleName.str)
        DocDecUtils.appendFunctionParams(b, params)
    }
}

class DocDeclaration_Query(
    modifiers: DocModifiers,
    private val simpleName: R_Name,
    private val resultType: M_Type,
    private val params: List<Lazy<DocDeclaration>>,
): DocDeclaration_Annotated(modifiers) {
    override fun genCode0(b: DocCode.Builder) {
        b.keyword("query")
        b.raw(" ")
        b.raw(simpleName.str)
        DocDecUtils.appendFunctionParams(b, params)
        b.sep(": ")
        L_TypeUtils.docCode(b, resultType)
    }
}

class DocDeclaration_Type(
    private val simpleName: R_Name,
    private val mTypeParams: List<M_TypeParam>,
    private val lParent: L_TypeDefParent?,
    private val flags: L_TypeDefFlags,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()

        if (flags.abstract) b.keyword("abstract").raw(" ")
        if (flags.extension) b.keyword("extension").raw(" ")
        if (flags.hidden) b.keyword("internal").raw(" ")

        b.keyword("type")
        b.raw(" ")
        b.raw(simpleName.str)

        DocDecUtils.appendTypeParams(b, mTypeParams)

        if (lParent != null) {
            b.sep(": ")
            docCodeParent(b, lParent)
        }

        return b.build()
    }

    private fun docCodeParent(b: DocCode.Builder, lParent: L_TypeDefParent) {
        b.link(lParent.typeDef.simpleName.str)

        if (lParent.args.isNotEmpty()) {
            b.raw("<")
            for ((i, arg) in lParent.args.withIndex()) {
                if (i > 0) b.sep(", ")
                L_TypeUtils.docCode(b, arg)
            }
            b.raw(">")
        }
    }
}

class DocDeclaration_TypeConstructor(private val lConstructor: L_Constructor): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()

        DocDecUtils.appendDeprecated(b, lConstructor.deprecated)

        if (lConstructor.pure) {
            b.keyword("pure")
            b.raw(" ")
        }

        b.keyword("constructor")
        DocDecUtils.appendTypeParams(b, lConstructor.header.typeParams)
        Ld_DocSymbols.docCodeParams(b, lConstructor.header.params)

        return b.build()
    }
}

class DocDeclaration_TupleAttribute(
    private val simpleName: R_Name,
    private val mType: M_Type,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()
        b.raw(simpleName.str)
        b.sep(": ")
        L_TypeUtils.docCode(b, mType)
        return b.build()
    }
}

class DocDeclaration_AtVariable(
    private val simpleName: String,
    private val mType: M_Type,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()
        b.raw(simpleName)
        b.sep(": ")
        L_TypeUtils.docCode(b, mType)
        return b.build()
    }
}

class DocDeclaration_Variable(
    private val simpleName: R_Name,
    private val mType: M_Type,
    private val isMutable: Boolean,
): DocDeclaration() {
    override fun genCode(): DocCode {
        val b = DocCode.builder()
        b.keyword(if (isMutable) "var" else "val")
        b.raw(" ")
        b.raw(simpleName.str)
        b.sep(": ")
        L_TypeUtils.docCode(b, mType)
        return b.build()
    }
}

sealed class DocModifier {
    abstract fun genCode(b: DocCode.Builder)

    companion object {
        val PURE: DocModifier = DocModifier_Keyword("pure")
        val STATIC: DocModifier = DocModifier_Keyword("static")

        private val DEPRECATED: DocModifier = DocModifier_Annotation(R_Name.of("deprecated"), immListOf())

        private val DEPRECATED_ERROR: DocModifier = DocModifier_Annotation(
            R_Name.of("deprecated"),
            immListOf(DocAnnotationArg.makeRaw("ERROR")),
        )

        fun deprecated(error: Boolean): DocModifier = if (error) DEPRECATED_ERROR else DEPRECATED
    }
}

class DocModifier_Keyword(private val kw: String): DocModifier() {
    override fun genCode(b: DocCode.Builder) {
        b.keyword(kw)
        b.raw(" ")
    }
}

sealed class DocAnnotationArg {
    abstract fun genCode(b: DocCode.Builder)

    companion object {
        fun makeRaw(s: String): DocAnnotationArg = DocAnnotationArg_Raw(s)
        fun makeName(qualifiedName: R_QualifiedName): DocAnnotationArg = DocAnnotationArg_Name(qualifiedName)
        fun makeValue(value: Rt_Value): DocAnnotationArg = DocAnnotationArg_Value(value)
    }
}

private class DocAnnotationArg_Raw(private val s: String): DocAnnotationArg() {
    override fun genCode(b: DocCode.Builder) {
        b.raw(s)
    }
}

private class DocAnnotationArg_Name(private val qualifiedName: R_QualifiedName): DocAnnotationArg() {
    override fun genCode(b: DocCode.Builder) {
        b.link(qualifiedName.str())
    }
}

private class DocAnnotationArg_Value(private val value: Rt_Value): DocAnnotationArg() {
    override fun genCode(b: DocCode.Builder) {
        C_DocUtils.valueToDoc(b, value)
    }
}

class DocModifier_Annotation(
    private val simpleName: R_Name,
    private val args: List<DocAnnotationArg>,
): DocModifier() {
    override fun genCode(b: DocCode.Builder) {
        b.raw("@")
        b.raw(simpleName.str)

        if (args.isNotEmpty()) {
            b.raw("(")
            for ((i, arg) in args.withIndex()) {
                if (i > 0) b.sep(", ")
                arg.genCode(b)
            }
            b.raw(")")
        }

        b.newline()
    }
}

class DocModifiers(
    private val modifiers: List<DocModifier>,
) {
    constructor(vararg modifiers: DocModifier): this(modifiers.toImmList())

    fun appendTo(b: DocCode.Builder) {
        for (mod in modifiers) {
            mod.genCode(b)
        }
    }

    companion object {
        val NONE: DocModifiers = DocModifiers(immListOf())
    }
}

private object DocDecUtils {
    fun appendModuleName(b: DocCode.Builder, moduleName: R_ModuleName) {
        val moduleStr = if (moduleName.isEmpty()) "''" else moduleName.str()
        b.link(moduleStr)
    }

    fun appendTypeParams(b: DocCode.Builder, typeParams: List<M_TypeParam>) {
        if (typeParams.isEmpty()) {
            return
        }

        b.raw("<")

        for ((i, param) in typeParams.withIndex()) {
            if (i > 0) b.sep(", ")
            b.raw(param.name)
            if (param.bounds != M_TypeSets.ALL) {
                b.sep(": ")
                L_TypeUtils.docCodeTypeSet(b, param.bounds)
            }
        }

        b.raw(">")
    }

    fun appendDeprecated(b: DocCode.Builder, deprecated: C_Deprecated?) {
        if (deprecated != null) {
            b.raw("@deprecated")
            if (deprecated.error) {
                b.raw("(ERROR)")
            }
            b.newline()
        }
    }

    fun appendFunctionParams(b: DocCode.Builder, params: List<Lazy<DocDeclaration>>) {
        b.raw("(")

        var sep = ""
        for (param in params) {
            b.sep(sep)
            b.newline()
            b.tab()
            b.append(param.value.code)
            sep = ","
        }

        if (params.isNotEmpty()) {
            b.newline()
        }

        b.raw(")")
    }
}
