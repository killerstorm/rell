/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.core.C_CompilerExecutor
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.RellVersions
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf

// Not a normal library, only provides queries that are not bound to a namespace, but are accessible via their mount names.

object C_Lib_SysQueries {
    fun createQueries(executor: C_CompilerExecutor): List<R_QueryDefinition> {
        return immListOf(
            C_Utils.createSysQuery(executor, "get_rell_version", R_TextType, SysQueryFns.GetRellVersion),
            C_Utils.createSysQuery(executor, "get_postchain_version", R_TextType, SysQueryFns.GetPostchainVersion),
            C_Utils.createSysQuery(executor, "get_build", R_TextType, SysQueryFns.GetBuild),
            C_Utils.createSysQuery(executor, "get_build_details", SysQueryFns.GetBuildDetails.TYPE, SysQueryFns.GetBuildDetails),
            C_Utils.createSysQuery(executor, "get_app_structure", R_GtvType, SysQueryFns.GetAppStructure)
        )
    }
}

private object SysQueryFns {
    object GetRellVersion: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            return Rt_TextValue(RellVersions.VERSION_STR)
        }
    }

    object GetPostchainVersion: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val ver = ctx.globalCtx.rellVersion()
            val postchainVer = ver.properties.getValue(Rt_RellVersionProperty.POSTCHAIN_VERSION)
            return Rt_TextValue(postchainVer)
        }
    }

    object GetBuild: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val ver = ctx.globalCtx.rellVersion()
            return Rt_TextValue(ver.buildDescriptor)
        }
    }

    object GetBuildDetails: R_SysFunction {
        val TYPE = R_MapType(R_TextType, R_TextType)

        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val ver = ctx.globalCtx.rellVersion()
            return Rt_MapValue(TYPE, ver.rtProperties.toMutableMap())
        }
    }

    object GetAppStructure: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val v = ctx.appCtx.app.toMetaGtv()
            return Rt_GtvValue(v)
        }
    }
}
