/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.ast

import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.def.*
import net.postchain.rell.base.compiler.base.fn.C_FormalParameters
import net.postchain.rell.base.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.base.compiler.base.modifier.C_AnnUtils
import net.postchain.rell.base.compiler.base.modifier.C_ModifierFields
import net.postchain.rell.base.compiler.base.modifier.C_ModifierTargetType
import net.postchain.rell.base.compiler.base.modifier.C_ModifierValues
import net.postchain.rell.base.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceMemberBase
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.base.utils.C_StringQualifiedName
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.R_FunctionExtension
import net.postchain.rell.base.utils.ide.*
import net.postchain.rell.base.utils.toImmList
import kotlin.math.min

class S_FunctionDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        private val qualifiedName: S_QualifiedName?,
        val params: List<S_FormalParameter>,
        val retType: S_Type?,
        val body: S_FunctionBody?
): S_BasicDefinition(pos, modifiers) {
    val fnPos = qualifiedName?.pos ?: kwPos
    val typePos = retType?.pos ?: fnPos

    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(fnPos, C_DeclarationType.FUNCTION)

        val cQualifiedNameHand = qualifiedName?.compile(ctx)
        val cQualifiedName = cQualifiedNameHand?.qName

        val simpleName = cQualifiedName?.last

        val mods = C_ModifierValues(C_ModifierTargetType.FUNCTION, simpleName)
        val modAbstract = mods.field(C_ModifierFields.ABSTRACT)
        val modOverride = mods.field(C_ModifierFields.OVERRIDE)
        val modExtendable = mods.field(C_ModifierFields.EXTENDABLE)
        val modExtend = mods.field(C_ModifierFields.EXTEND)
        val modDeprecated = mods.field(C_ModifierFields.DEPRECATED)

        modifiers.compile(ctx, mods)
        C_AnnUtils.checkModsZeroOne(ctx.msgCtx, modAbstract, modOverride, modExtendable, modExtend)

        val abstract = modAbstract.hasValue()
        val override = modOverride.hasValue()
        val extendable = modExtendable.hasValue()
        val extend = modExtend.value()
        val deprecated = modDeprecated.value()

        val cDefBase = definitionBase(ctx, cQualifiedName)
        val ideId = cDefBase.ideId(C_DefinitionType.FUNCTION)

        val base = C_FunctionCompilerBase(this, cDefBase, cQualifiedNameHand, cQualifiedName, deprecated, ideId)

        val compiler = when {
            abstract -> C_FunctionCompiler_Abstract(base)
            override -> C_FunctionCompiler_Override(base)
            extendable -> C_FunctionCompiler_Extendable(base)
            extend != null -> C_FunctionCompiler_Extend(base, extend)
            else -> C_FunctionCompiler_Regular(base)
        }

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.FUNCTION, cDefBase.defId, cDefBase.defName, ideId)
        compiler.compile(defCtx)
    }

    private fun definitionBase(ctx: C_MountContext, cQualifiedName: C_QualifiedName?): C_DefinitionBase {
        val cName = if (cQualifiedName != null) {
            C_StringQualifiedName.of(cQualifiedName)
        } else {
            val id = ctx.modCtx.nextNamelessFunctionId(ctx.nsCtx.namespacePath)
            val simpleName = "function#$id"
            C_StringQualifiedName.of(simpleName)
        }
        return ctx.defBase(cName)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        if (qualifiedName != null) {
            val name = qualifiedName.last
            b.node(this, name, IdeOutlineNodeType.FUNCTION)
        }
    }
}

private class C_FunctionCompilerBase(
    val sFn: S_FunctionDefinition,
    val cDefBase: C_DefinitionBase,
    val qualifiedNameHand: C_QualifiedNameHandle?,
    val qualifiedName: C_QualifiedName?,
    val deprecated: C_Deprecated?,
    val ideId: IdeSymbolId,
)

private abstract class C_FunctionCompiler(protected val base: C_FunctionCompilerBase) {
    protected val sFn = base.sFn
    protected val cDefBase = base.cDefBase
    protected val fnPos = sFn.fnPos
    protected val typePos = sFn.typePos

    abstract fun compile(defCtx: C_DefinitionContext)

    protected fun nameErrCode() = cDefBase.appLevelName

    protected fun defBaseEx(ideKind: IdeSymbolKind): C_DefinitionBaseEx {
        return cDefBase.baseEx(fnPos, C_DefinitionType.FUNCTION, ideKind, base.ideId)
    }

    protected fun nsMemBase(cDefBaseEx: C_DefinitionBaseEx): C_NamespaceMemberBase {
        return cDefBaseEx.nsMemBase(deprecated = base.deprecated)
    }

    protected fun compileHeader0(defCtx: C_DefinitionContext): C_UserFunctionHeader {
        return C_FunctionUtils.compileFunctionHeader(defCtx, fnPos, cDefBase.defName, sFn.params, sFn.retType, sFn.body)
    }

    protected fun compileHeaderCommon(defCtx: C_DefinitionContext, cFn: C_UserGlobalFunction): C_UserFunctionHeader {
        val header = compileHeader0(defCtx)
        cFn.setHeader(header)
        return header
    }

    protected fun compileBodyCommon(header: C_UserFunctionHeader, rFnBase: R_FunctionBase) {
        if (header.fnBody != null) {
            val rBody = header.fnBody.compile()
            rFnBase.setBody(rBody)
            val rHeader = R_FunctionHeader(rBody.type, rBody.params)
            rFnBase.setHeader(rHeader)
        } else {
            // Actually needed only for body-less extendable functions - rell.get_app_structure fails for them
            // without this special handling (entire "else" part was added).
            val type = header.returnType()
            val params = header.params.list.map { R_Param(it.name.rName, it.type) }.toImmList()
            val rHeader = R_FunctionHeader(type, params)
            rFnBase.setHeader(rHeader)
        }
    }

    protected fun checkHasBody(msgCtx: C_MessageContext) {
        if (sFn.body == null) {
            val nameCode = nameErrCode()
            msgCtx.error(fnPos, "fn:no_body:$nameCode", "Function must have a body")
        }
    }

    protected fun checkQualifiedName(msgCtx: C_MessageContext): C_QualifiedNameHandle? {
        return if (base.qualifiedNameHand != null) base.qualifiedNameHand else {
            msgCtx.error(fnPos, "fn:no_name", "Function needs a name")
            null
        }
    }

    protected fun checkSimpleName(msgCtx: C_MessageContext): C_NameHandle? {
        val qualifiedName = checkQualifiedName(msgCtx)
        return when {
            qualifiedName == null -> null
            qualifiedName.parts.size >= 2 -> {
                val qName = qualifiedName.str()
                qualifiedName.setIdeInfo(IdeSymbolInfo.UNKNOWN)
                msgCtx.error(fnPos, "fn:qname_no_override:$qName",
                        "Invalid function name: '$qName' (qualified names allowed only for override)")
                null
            }
            else -> qualifiedName.last
        }
    }
}

private class C_FunctionCompiler_Regular(base: C_FunctionCompilerBase): C_FunctionCompiler(base) {
    override fun compile(defCtx: C_DefinitionContext) {
        checkHasBody(defCtx.msgCtx)
        val simpleNameHand = checkSimpleName(defCtx.msgCtx)

        val cDefBaseEx = defBaseEx(IdeSymbolKind.DEF_FUNCTION)
        val defBase = cDefBaseEx.rBase(defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase(defBase.defName)
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val cFn = C_RegularUserGlobalFunction(rFn, null)

        if (simpleNameHand != null) {
            simpleNameHand.setIdeInfo(cDefBaseEx.ideDefInfo)
            defCtx.mntCtx.nsBuilder.addFunction(nsMemBase(cDefBaseEx), simpleNameHand.name, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeaderCommon(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBodyCommon(header, rFnBase)
            }
        }
    }
}

private class C_FunctionCompiler_Abstract(base: C_FunctionCompilerBase): C_FunctionCompiler(base) {
    override fun compile(defCtx: C_DefinitionContext) {
        val simpleNameHand = checkSimpleName(defCtx.msgCtx)

        if (!defCtx.modCtx.abstract) {
            val mName = defCtx.modCtx.moduleName.str()
            val nameCode = nameErrCode()
            defCtx.msgCtx.error(fnPos, "fn:abstract:non_abstract_module:$mName:$nameCode",
                    "Abstract function can be defined only in abstract module")
        }

        val cDefBaseEx = defBaseEx(IdeSymbolKind.DEF_FUNCTION_ABSTRACT)
        val defBase = cDefBaseEx.rBase(defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase(defBase.defName)
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val cFn = C_AbstractUserGlobalFunction(fnPos, rFn, sFn.body != null, rFnBase)
        defCtx.mntCtx.fileCtx.addAbstractFunction(cFn.descriptor)

        if (simpleNameHand != null) {
            simpleNameHand.setIdeInfo(cDefBaseEx.ideDefInfo)
            defCtx.mntCtx.nsBuilder.addFunction(nsMemBase(cDefBaseEx), simpleNameHand.name, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeaderCommon(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBodyCommon(header, rFnBase)
                cFn.compileOverride()
            }
        }
    }
}

private class C_FunctionCompiler_Override(base: C_FunctionCompilerBase): C_FunctionCompiler(base) {
    override fun compile(defCtx: C_DefinitionContext) {
        checkHasBody(defCtx.msgCtx)

        if (defCtx.modCtx.repl) {
            defCtx.msgCtx.error(fnPos, "fn:override:repl", "Cannot override a function in REPL")
        }

        val rFnBase = R_FunctionBase(defCtx.defName)
        val descriptor = C_OverrideFunctionDescriptor(fnPos, rFnBase)
        defCtx.mntCtx.fileCtx.addOverrideFunction(descriptor)

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            compileHeader(defCtx, rFnBase, descriptor)
        }
    }

    private fun compileHeader(
            defCtx: C_DefinitionContext,
            rFnBase: R_FunctionBase,
            overDescriptor: C_OverrideFunctionDescriptor
    ) {
        val header = compileHeader0(defCtx)

        val absDescriptor = getAbstractDescriptor(defCtx)
        overDescriptor.setAbstract(absDescriptor)

        defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            compileBody(defCtx.mntCtx, rFnBase, header, absDescriptor)
        }
    }

    private fun getAbstractDescriptor(defCtx: C_DefinitionContext): C_AbstractFunctionDescriptor? {
        val qualifiedName = checkQualifiedName(defCtx.msgCtx)
        qualifiedName ?: return null

        val fn = defCtx.nsCtx.getFunction(qualifiedName)
        fn ?: return null

        val desc = fn.getAbstractDescriptor()

        if (desc == null) {
            val qName = fn.getFunctionDefinition()?.appLevelName ?: qualifiedName.str()
            defCtx.msgCtx.error(fnPos, "fn:override:not_abstract:[$qName]", "Function is not abstract: '$qName'")
        }

        return desc
    }

    private fun compileBody(
            ctx: C_MountContext,
            rFnBase: R_FunctionBase,
            overHeader: C_UserFunctionHeader,
            absDescriptor: C_AbstractFunctionDescriptor?
    ) {
        compileBodyCommon(overHeader, rFnBase)

        if (absDescriptor != null) {
            val absHeader = absDescriptor.header()
            checkSignature(ctx.msgCtx, sFn, absHeader, overHeader, "override", cDefBase.defName)
        }
    }

    companion object {
        fun checkSignature(
                msgCtx: C_MessageContext,
                sFn: S_FunctionDefinition,
                baseHeader: C_UserFunctionHeader,
                subHeader: C_UserFunctionHeader,
                subCode: String,
                subDefName: R_DefinitionName
        ) {
            checkOverrideType(msgCtx, baseHeader, subHeader, sFn.typePos, subCode, subDefName)
            checkOverrideParams(msgCtx, baseHeader.params, subHeader.params, sFn.fnPos, subCode, subDefName)
        }

        private fun checkOverrideType(
                msgCtx: C_MessageContext,
                baseHeader: C_UserFunctionHeader,
                subHeader: C_UserFunctionHeader,
                subTypePos: S_Pos,
                subCode: String,
                subDefName: R_DefinitionName
        ) {
            val baseType = baseHeader.returnType()
            val subType = subHeader.returnType()
            val nameCode = subDefName.appLevelName
            C_Types.matchOpt(msgCtx, baseType, subType, subTypePos) {
                "fn:$subCode:ret_type:$nameCode" toCodeMsg "Return type mismatch"
            }
        }

        private fun checkOverrideParams(
                msgCtx: C_MessageContext,
                baseParams: C_FormalParameters,
                subParams: C_FormalParameters,
                subPos: S_Pos,
                subCode: String,
                subDefName: R_DefinitionName
        ) {
            val baseParamsList = baseParams.list
            val subParamsList = subParams.list

            if (baseParamsList.size != subParamsList.size) {
                val nameCode = subDefName.appLevelName
                msgCtx.error(subPos, "fn:$subCode:param_cnt:$nameCode:${baseParamsList.size}:${subParamsList.size}",
                        "Wrong number of parameters: ${subParamsList.size} instead of ${baseParamsList.size}")
            }

            for (i in 0 until min(subParamsList.size, baseParamsList.size)) {
                val baseParam = baseParamsList[i]
                val subParam = subParamsList[i]
                val baseType = baseParam.type
                val subType = subParam.type

                if (subType != baseType) {
                    C_Errors.errTypeMismatch(msgCtx, subParam.name.pos, subType, baseType) {
                        val nameCode = subDefName.appLevelName
                        "fn:$subCode:param_type:$nameCode:$i:${baseParam.name}" toCodeMsg
                        "Parameter '${baseParam.name}' (${i+1}) type mismatch "
                    }
                }
            }
        }
    }
}

private class C_FunctionCompiler_Extendable(base: C_FunctionCompilerBase): C_FunctionCompiler(base) {
    override fun compile(defCtx: C_DefinitionContext) {
        val simpleNameHand = checkSimpleName(defCtx.msgCtx)

        val cDefBaseEx = defBaseEx(IdeSymbolKind.DEF_FUNCTION_EXTENDABLE)
        val defBase = cDefBaseEx.rBase(defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase(defBase.defName)
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val baseExt = if (sFn.body == null) null else R_FunctionExtension(rFnBase)
        val extFnUid = defCtx.appCtx.extendableFunctionCompiler.addExtendableFunction(cDefBase.appLevelName, baseExt)

        val cFn = C_ExtendableUserGlobalFunction(defCtx.appCtx, rFn, extFnUid, typePos)

        if (simpleNameHand != null) {
            simpleNameHand.setIdeInfo(cDefBaseEx.ideDefInfo)
            defCtx.mntCtx.nsBuilder.addFunction(nsMemBase(cDefBaseEx), simpleNameHand.name, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeaderCommon(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(header, cFn, rFnBase)
            }
        }

        defCtx.mntCtx.checkNotTest(fnPos) {
            "fn_extendable:${cDefBase.appLevelName}" toCodeMsg "extendable function"
        }
    }

    private fun compileBody(
            header: C_UserFunctionHeader,
            cFn: C_ExtendableUserGlobalFunction,
            rFnBase: R_FunctionBase
    ) {
        compileBodyCommon(header, rFnBase)
        cFn.compileDefinition()
    }
}

private class C_FunctionCompiler_Extend(
        base: C_FunctionCompilerBase,
        private val extendName: S_QualifiedName
): C_FunctionCompiler(base) {
    override fun compile(defCtx: C_DefinitionContext) {
        val simpleNameHand = if (base.qualifiedName == null) null else checkSimpleName(defCtx.msgCtx)
        checkHasBody(defCtx.msgCtx)

        val cDefBaseEx = defBaseEx(IdeSymbolKind.DEF_FUNCTION_EXTEND)
        val defBase = cDefBaseEx.rBase(defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase(defBase.defName)
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val cFn = C_RegularUserGlobalFunction(rFn, null)

        if (simpleNameHand != null) {
            simpleNameHand.setIdeInfo(cDefBaseEx.ideDefInfo)
            defCtx.mntCtx.nsBuilder.addFunction(nsMemBase(cDefBaseEx), simpleNameHand.name, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cFn, rFnBase)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(defCtx.msgCtx, header, rFnBase)
            }
        }
    }

    private fun compileHeader(
            defCtx: C_DefinitionContext,
            cFn: C_UserGlobalFunction,
            rFnBase: R_FunctionBase
    ): C_ExtendFunctionHeader {
        val extendNameHand = extendName.compile(defCtx)

        val cBaseFn = defCtx.nsCtx.getFunction(extendNameHand)
        val cExtDescriptor = cBaseFn?.getExtendableDescriptor()

        if (cExtDescriptor == null) {
            if (cBaseFn != null) {
                val qName = extendName.str()
                defCtx.msgCtx.error(extendName.pos, "fn:extend:not_extendable:$qName", "Function is not extendable: '$qName'")
            }
        } else {
            val ok = defCtx.mntCtx.checkNotTest(fnPos) {
                val nameCode = nameErrCode()
                "fn_extend:$nameCode" toCodeMsg "extend function"
            }
            if (ok && !defCtx.modCtx.isTestDependency) {
                val ext = R_FunctionExtension(rFnBase)
                defCtx.appCtx.extendableFunctionCompiler.addExtension(cExtDescriptor.uid, ext)
            }
        }

        val regHeader = compileHeaderCommon(defCtx, cFn)
        return C_ExtendFunctionHeader(regHeader, cExtDescriptor)
    }

    private fun compileBody(
            msgCtx: C_MessageContext,
            header: C_ExtendFunctionHeader,
            rFnBase: R_FunctionBase
    ) {
        compileBodyCommon(header.regHeader, rFnBase)

        header.extDescriptor ?: return
        val baseHeader = header.extDescriptor.header()
        C_FunctionCompiler_Override.checkSignature(msgCtx, sFn, baseHeader, header.regHeader, "extend", cDefBase.defName)
    }

    private class C_ExtendFunctionHeader(
            val regHeader: C_UserFunctionHeader,
            val extDescriptor: C_ExtendableFunctionDescriptor?
    )
}