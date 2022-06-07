/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.*
import net.postchain.rell.compiler.base.fn.C_FormalParameters
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.modifier.C_AnnUtils
import net.postchain.rell.compiler.base.modifier.C_ModifierFields
import net.postchain.rell.compiler.base.modifier.C_ModifierTargetType
import net.postchain.rell.compiler.base.modifier.C_ModifierValues
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_RawQualifiedName
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_FunctionExtension
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.toImmList
import kotlin.math.min

class S_FunctionDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val qualifiedName: S_QualifiedName?,
        val params: List<S_FormalParameter>,
        val retType: S_Type?,
        val body: S_FunctionBody?
): S_BasicDefinition(pos, modifiers) {
    val fnPos = qualifiedName?.pos ?: kwPos
    val typePos = retType?.pos ?: fnPos

    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(fnPos, C_DeclarationType.FUNCTION)

        val simpleName = qualifiedName?.last

        val mods = C_ModifierValues(C_ModifierTargetType.FUNCTION, simpleName)
        val modAbstract = mods.field(C_ModifierFields.ABSTRACT)
        val modOverride = mods.field(C_ModifierFields.OVERRIDE)
        val modExtendable = mods.field(C_ModifierFields.EXTENDABLE)
        val modExtend = mods.field(C_ModifierFields.EXTEND)

        modifiers.compile(ctx, mods)
        C_AnnUtils.checkModsZeroOne(ctx.msgCtx, modAbstract, modOverride, modExtendable, modExtend)

        val abstract = modAbstract.hasValue()
        val override = modOverride.hasValue()
        val extendable = modExtendable.hasValue()
        val extend = modExtend.value()

        val defNames = definitionNames(ctx)
        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.FUNCTION, defNames.defId)

        val compiler = if (abstract) {
            C_FunctionCompiler_Abstract(this, defNames)
        } else if (override) {
            C_FunctionCompiler_Override(this, defNames)
        } else if (extendable) {
            C_FunctionCompiler_Extendable(this, defNames)
        } else if (extend != null) {
            C_FunctionCompiler_Extend(this, defNames, extend)
        } else {
            C_FunctionCompiler_Regular(this, defNames)
        }

        compiler.compile(defCtx)
    }

    private fun definitionNames(ctx: C_MountContext): R_DefinitionNames {
        val cName = if (qualifiedName != null) {
            C_RawQualifiedName.of(qualifiedName)
        } else {
            val id = ctx.modCtx.nextNamelessFunctionId(ctx.nsCtx.rNamespaceName)
            val simpleName = "function#$id"
            C_RawQualifiedName.of(simpleName)
        }

        return ctx.nsCtx.defNames(cName)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        if (qualifiedName != null) {
            val name = qualifiedName.last
            b.node(this, name, IdeOutlineNodeType.FUNCTION)
        }
    }
}

private abstract class C_FunctionCompiler(
        protected val sFn: S_FunctionDefinition,
        protected val defNames: R_DefinitionNames
) {
    protected val fnPos = sFn.fnPos
    protected val typePos = sFn.typePos

    abstract fun compile(defCtx: C_DefinitionContext)

    protected fun nameErrCode() = defNames.appLevelName

    protected fun compileHeader0(defCtx: C_DefinitionContext): C_UserFunctionHeader {
        return C_FunctionUtils.compileFunctionHeader(defCtx, fnPos, defNames, sFn.params, sFn.retType, sFn.body)
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
            val params = header.params.list.map { R_Param(it.name.str, it.type) }.toImmList()
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

    protected fun checkQualifiedName(msgCtx: C_MessageContext): S_QualifiedName? {
        return if (sFn.qualifiedName != null) sFn.qualifiedName else {
            msgCtx.error(fnPos, "fn:no_name", "Function needs a name")
            null
        }
    }

    protected fun checkSimpleName(msgCtx: C_MessageContext): S_Name? {
        val qualifiedName = checkQualifiedName(msgCtx)
        return when {
            qualifiedName == null -> null
            qualifiedName.parts.size >= 2 -> {
                val qName = qualifiedName.str()
                msgCtx.error(fnPos, "fn:qname_no_override:$qName",
                        "Invalid function name: '$qName' (qualified names allowed only for override)")
                null
            }
            else -> qualifiedName.last
        }
    }
}

private class C_FunctionCompiler_Regular(
        sFn: S_FunctionDefinition,
        defNames: R_DefinitionNames
): C_FunctionCompiler(sFn, defNames) {
    override fun compile(defCtx: C_DefinitionContext) {
        checkHasBody(defCtx.msgCtx)
        val simpleName = checkSimpleName(defCtx.msgCtx)

        val defBase = R_DefinitionBase(defNames, defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase()
        val rFn = R_FunctionDefinition(defBase, rFnBase)
        val cFn = C_RegularUserGlobalFunction(rFn, null)

        if (simpleName != null) {
            defCtx.mntCtx.nsBuilder.addFunction(simpleName, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeaderCommon(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBodyCommon(header, rFnBase)
            }
        }
    }
}

private class C_FunctionCompiler_Abstract(
        sFn: S_FunctionDefinition,
        defNames: R_DefinitionNames
): C_FunctionCompiler(sFn, defNames) {
    override fun compile(defCtx: C_DefinitionContext) {
        val simpleName = checkSimpleName(defCtx.msgCtx)

        if (!defCtx.modCtx.abstract) {
            val mName = defCtx.modCtx.moduleName.str()
            val nameCode = nameErrCode()
            defCtx.msgCtx.error(fnPos, "fn:abstract:non_abstract_module:$mName:$nameCode",
                    "Abstract function can be defined only in abstract module")
        }

        val defBase = R_DefinitionBase(defNames, defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase()
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val cFn = C_AbstractUserGlobalFunction(fnPos, rFn, sFn.body != null, rFnBase)
        defCtx.mntCtx.fileCtx.addAbstractFunction(cFn.descriptor)

        if (simpleName != null) {
            defCtx.mntCtx.nsBuilder.addFunction(simpleName, cFn)
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

private class C_FunctionCompiler_Override(
        sFn: S_FunctionDefinition,
        defNames: R_DefinitionNames
): C_FunctionCompiler(sFn, defNames) {
    override fun compile(defCtx: C_DefinitionContext) {
        checkHasBody(defCtx.msgCtx)

        if (defCtx.modCtx.repl) {
            defCtx.msgCtx.error(fnPos, "fn:override:repl", "Cannot override a function in REPL")
        }

        val rFnBase = R_FunctionBase()
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

        val fn = defCtx.nsCtx.getFunctionOpt(qualifiedName)?.getDef()
        if (fn == null) {
            val qName = qualifiedName.str()
            defCtx.msgCtx.error(fnPos, "fn:override:not_found:$qName", "Function not found: '$qName'")
            return null
        }

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
            checkSignature(ctx.msgCtx, sFn, absHeader, overHeader, "override", defNames)
        }
    }

    companion object {
        fun checkSignature(
                msgCtx: C_MessageContext,
                sFn: S_FunctionDefinition,
                baseHeader: C_UserFunctionHeader,
                subHeader: C_UserFunctionHeader,
                subCode: String,
                subNames: R_DefinitionNames
        ) {
            checkOverrideType(msgCtx, baseHeader, subHeader, sFn.typePos, subCode, subNames)
            checkOverrideParams(msgCtx, baseHeader.params, subHeader.params, sFn.fnPos, subCode, subNames)
        }

        private fun checkOverrideType(
                msgCtx: C_MessageContext,
                baseHeader: C_UserFunctionHeader,
                subHeader: C_UserFunctionHeader,
                subTypePos: S_Pos,
                subCode: String,
                subNames: R_DefinitionNames
        ) {
            val baseType = baseHeader.returnType()
            val subType = subHeader.returnType()
            val nameCode = subNames.appLevelName
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
                subNames: R_DefinitionNames
        ) {
            val baseParamsList = baseParams.list
            val subParamsList = subParams.list

            if (baseParamsList.size != subParamsList.size) {
                val nameCode = subNames.appLevelName
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
                        val nameCode = subNames.appLevelName
                        "fn:$subCode:param_type:$nameCode:$i:${baseParam.name.str}" toCodeMsg
                        "Parameter '${baseParam.name.str}' (${i+1}) type mismatch "
                    }
                }
            }
        }
    }
}

private class C_FunctionCompiler_Extendable(
        sFn: S_FunctionDefinition,
        defNames: R_DefinitionNames
): C_FunctionCompiler(sFn, defNames) {
    override fun compile(defCtx: C_DefinitionContext) {
        val simpleName = checkSimpleName(defCtx.msgCtx)

        val defBase = R_DefinitionBase(defNames, defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase()
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val baseExt = if (sFn.body == null) null else R_FunctionExtension(rFnBase)
        val extFnUid = defCtx.appCtx.extendableFunctionCompiler.addExtendableFunction(defNames.appLevelName, baseExt)

        val cFn = C_ExtendableUserGlobalFunction(defCtx.appCtx, rFn, extFnUid, typePos)

        if (simpleName != null) {
            defCtx.mntCtx.nsBuilder.addFunction(simpleName, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeaderCommon(defCtx, cFn)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(header, cFn, rFnBase)
            }
        }

        defCtx.mntCtx.checkNotTest(fnPos) {
            "fn_extendable:${defNames.appLevelName}" toCodeMsg "extendable function"
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
        sFn: S_FunctionDefinition,
        defNames: R_DefinitionNames,
        private val extendName: S_QualifiedName
): C_FunctionCompiler(sFn, defNames) {
    override fun compile(defCtx: C_DefinitionContext) {
        val simpleName = if (sFn.qualifiedName == null) null else checkSimpleName(defCtx.msgCtx)
        checkHasBody(defCtx.msgCtx)

        val defBase = R_DefinitionBase(defNames, defCtx.initFrameGetter)
        val rFnBase = R_FunctionBase()
        val rFn = R_FunctionDefinition(defBase, rFnBase)

        val cFn = C_RegularUserGlobalFunction(rFn, null)

        if (simpleName != null) {
            defCtx.mntCtx.nsBuilder.addFunction(simpleName, cFn)
        }

        defCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cFn, rFnBase, extendName)
            defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(defCtx.msgCtx, header, rFnBase)
            }
        }
    }

    private fun compileHeader(
            defCtx: C_DefinitionContext,
            cFn: C_UserGlobalFunction,
            rFnBase: R_FunctionBase,
            extendName: S_QualifiedName
    ): C_ExtendFunctionHeader {
        val cBaseFn = defCtx.nsCtx.getFunctionOpt(extendName)?.getDef()

        if (cBaseFn == null) {
            val qName = extendName.str()
            defCtx.msgCtx.error(extendName.pos, "fn:extend:not_found:$qName", "Function not found: '$qName'")
        }

        val cExtDescriptor = cBaseFn?.getExtendableDescriptor()

        if (cExtDescriptor == null) {
            val qName = extendName.str()
            defCtx.msgCtx.error(extendName.pos, "fn:extend:not_extendable:$qName", "Function is not extendable: '$qName'")
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
        C_FunctionCompiler_Override.checkSignature(msgCtx, sFn, baseHeader, header.regHeader, "extend", defNames)
    }

    private class C_ExtendFunctionHeader(
            val regHeader: C_UserFunctionHeader,
            val extDescriptor: C_ExtendableFunctionDescriptor?
    )
}
