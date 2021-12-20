/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.C_OperationFunctionHeader
import net.postchain.rell.compiler.base.def.C_OperationGlobalFunction
import net.postchain.rell.compiler.base.def.C_QueryFunctionHeader
import net.postchain.rell.compiler.base.def.C_QueryGlobalFunction
import net.postchain.rell.compiler.base.fn.C_FormalParameters
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.modifier.C_ModifierFields
import net.postchain.rell.compiler.base.modifier.C_ModifierTargetType
import net.postchain.rell.compiler.base.modifier.C_ModifierValues
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.*

class S_OperationDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_FormalParameter>,
        val body: S_Statement
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.OPERATION)
        ctx.checkNotReplOrTest(name.pos, C_DeclarationType.OPERATION)

        val mods = C_ModifierValues(C_ModifierTargetType.OPERATION, name)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        modifiers.compile(ctx, mods)

        val names = ctx.nsCtx.defNames(name)
        val mountName = ctx.mountName(modMount, name)
        checkSysMountNameConflict(ctx, name.pos, C_DeclarationType.OPERATION, mountName, PostchainUtils.STD_OPS)

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.OPERATION, names.defId)
        val defBase = R_DefinitionBase(names, defCtx.initFrameGetter)

        val mirrorStructs = C_Utils.createMirrorStructs(ctx.appCtx, defBase, defCtx.definitionType, operation = mountName)

        val rOperation = R_OperationDefinition(defBase, mountName, mirrorStructs)
        val cOperation = C_OperationGlobalFunction(rOperation)

        ctx.appCtx.defsAdder.addOperation(rOperation)
        ctx.nsBuilder.addOperation(name, cOperation)
        ctx.mntBuilder.addOperation(name, rOperation)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cOperation, mirrorStructs)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(defCtx, rOperation, header)
            }
        }
    }

    private fun compileHeader(
            defCtx: C_DefinitionContext,
            cOperation: C_OperationGlobalFunction,
            mirrorStructs: R_MirrorStructs
    ): C_OperationFunctionHeader {
        val forParams = C_FormalParameters.compile(defCtx, params, true)
        val header = C_OperationFunctionHeader(forParams)
        cOperation.setHeader(header)
        compileMirrorStructAttrs(mirrorStructs, forParams, false)
        compileMirrorStructAttrs(mirrorStructs, forParams, true)
        return header
    }

    private fun compileMirrorStructAttrs(mirrorStructs: R_MirrorStructs, forParams: C_FormalParameters, mutable: Boolean) {
        val struct = mirrorStructs.getStruct(mutable)

        val attrMapMut = mutableMapOf<String, R_Attribute>()
        val attrNames = forParams.list.map { it.name.str }.toImmSet()

        for (param in forParams.list) {
            val attr = param.createMirrorAttr(mutable)
            var name = attr.name
            if (name in attrMapMut) {
                // A workaround to handle parameter name conflict (multiple parameters with same name).
                // Without it, there would be less struct attributes than parameters, what violates R_Struct's contract
                // (see MirrorStructOperationTest.testBugParameterNameConflict).
                var ctr = 0
                while (true) {
                    name = "${attr.name}__$ctr"
                    if (name !in attrMapMut && name !in attrNames) {
                        break
                    }
                    ctr += 1
                }
            }
            attrMapMut[name] = attr
        }

        val attrMap = attrMapMut.toImmMap()
        struct.setAttributes(attrMap)
    }

    private fun compileBody(defCtx: C_DefinitionContext, rOperation: R_OperationDefinition, header: C_OperationFunctionHeader) {
        val statementVars = processStatementVars()
        val fnCtx = C_FunctionContext(defCtx, rOperation.appLevelName, null, statementVars)
        val frameCtx = C_FrameContext.create(fnCtx)

        val actParams = header.params.compile(frameCtx)
        val cBody = body.compile(actParams.stmtCtx)
        val rBody = cBody.rStmt
        val callFrame = frameCtx.makeCallFrame(cBody.guardBlock)

        rOperation.setInternals(actParams.rParams, rBody, callFrame.rFrame)
    }

    private fun processStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        body.discoverVars(map)
        return map.immutableCopy()
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.OPERATION)
    }
}

class S_QueryDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val name: S_Name,
        val params: List<S_FormalParameter>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_BasicDefinition(pos, modifiers) {
    override fun compileBasic(ctx: C_MountContext) {
        ctx.checkNotExternal(name.pos, C_DeclarationType.QUERY)
        ctx.checkNotReplOrTest(name.pos, C_DeclarationType.QUERY)

        val mods = C_ModifierValues(C_ModifierTargetType.QUERY, name)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        modifiers.compile(ctx, mods)

        val names = ctx.nsCtx.defNames(name)
        val mountName = ctx.mountName(modMount, name)
        checkSysMountNameConflict(ctx, name.pos, C_DeclarationType.QUERY, mountName, PostchainUtils.STD_QUERIES)

        val defCtx = C_DefinitionContext(ctx, C_DefinitionType.QUERY, names.defId)
        val defBase = R_DefinitionBase(names, defCtx.initFrameGetter)

        val rQuery = R_QueryDefinition(defBase, mountName)
        val cQuery = C_QueryGlobalFunction(rQuery)

        ctx.appCtx.defsAdder.addQuery(rQuery)
        ctx.nsBuilder.addQuery(name, cQuery)
        ctx.mntBuilder.addQuery(name, rQuery)

        ctx.executor.onPass(C_CompilerPass.MEMBERS) {
            val header = compileHeader(defCtx, cQuery)
            ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                compileBody(ctx, header, rQuery)
            }
        }
    }

    private fun compileHeader(defCtx: C_DefinitionContext, cQuery: C_QueryGlobalFunction): C_QueryFunctionHeader {
        val header = C_FunctionUtils.compileQueryHeader(defCtx, name, cQuery.rQuery.names, params, retType, body)
        cQuery.setHeader(header)
        return header
    }

    private fun compileBody(ctx: C_MountContext, header: C_QueryFunctionHeader, rQuery: R_QueryDefinition) {
        if (header.queryBody == null) return

        val rBody = header.queryBody.compile()

        if (ctx.globalCtx.compilerOptions.gtv) {
            ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                checkGtvResult(ctx.msgCtx, rBody.retType)
            }
        }

        rQuery.setBody(rBody)
    }

    private fun checkGtvResult(msgCtx: C_MessageContext, rType: R_Type) {
        C_Utils.checkGtvCompatibility(msgCtx, name.pos, rType, false, "result_nogtv:${name.str}",
                "Return type of query '${name.str}'")
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        b.node(this, name, IdeOutlineNodeType.QUERY)
    }
}
