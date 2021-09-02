/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.api

import net.postchain.rell.RellConfigGen
import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.Rt_RellVersion
import net.postchain.rell.runtime.Rt_RellVersionProperty
import net.postchain.rell.utils.RellCliEnv
import net.postchain.rell.utils.toImmList
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler
import java.io.File

class IdeModuleInfo(
        @JvmField val name: R_ModuleName,
        @JvmField val directory: Boolean
)

@Suppress("UNUSED")
object IdeApi {
    const val RELL_VERSION = RellVersions.VERSION_STR

    @JvmStatic fun parseModuleName(s: String): R_ModuleName? {
        return R_ModuleName.ofOpt(s)
    }

    @JvmStatic fun getModuleInfo(path: C_SourcePath, ast: S_RellFile): IdeModuleInfo? {
        val (moduleName, directory) = C_ModuleUtils.getModuleInfo(path, ast)
        return if (moduleName == null) null else IdeModuleInfo(moduleName, directory)
    }

    @JvmStatic fun getImportedModules(moduleName: R_ModuleName, ast: S_RellFile): List<R_ModuleName> {
        val res = mutableSetOf<R_ModuleName>()
        ast.ideGetImportedModules(moduleName, res)
        return res.toImmList()
    }

    private object IdeRellCliEnv: RellCliEnv() {
        override fun print(msg: String, err: Boolean) = println(msg)
        override fun exit(status: Int): Nothing = throw IllegalStateException("$status")
    }

    @JvmStatic fun getAppFiles(sourceDir: C_SourceDir, modules: List<R_ModuleName>): Map<String, String> {
        val configGen = RellConfigGen.create(IdeRellCliEnv, sourceDir, modules)
        val ms = configGen.getModuleSources()
        return ms.files
    }

    @JvmStatic fun buildOutlineTree(b: IdeOutlineTreeBuilder, ast: S_RellFile) {
        ast.ideBuildOutlineTree(b)
    }

    @JvmStatic fun validate(
            sourceDir: C_SourceDir,
            modules: List<R_ModuleName>,
            options: C_CompilerOptions
    ): List<C_Message> {
        val res = C_Compiler.compile(sourceDir, modules, options)
        return res.messages
    }

    @JvmStatic fun getRellVersionInfo(): Map<Rt_RellVersionProperty, String>? {
        val ver = Rt_RellVersion.getInstance()
        return ver?.properties
    }

    @JvmStatic fun isValidDatabaseProperties(file: File): Boolean {
        val params = Parameters().properties()
                .setFile(file)
                .setListDelimiterHandler(DefaultListDelimiterHandler(','))

        val conf = FileBasedConfigurationBuilder(PropertiesConfiguration::class.java)
                .configure(params)
                .configuration

        val res = conf.containsKey("database.url")
        return res
    }

    @JvmStatic fun isValidRellTestFile(text: String): Boolean {
        val ast = C_Parser.parse(C_SourcePath.EMPTY, text)
        return ast.ideIsTestFile()
    }
}

@Suppress("UNUSED")
object IdeDirApi {
    @JvmField
    val EMPTY_DIR: C_SourceDir = C_SourceDir.EMPTY

    @JvmStatic
    fun mapDir(files: Map<C_SourcePath, C_SourceFile>): C_SourceDir = C_SourceDir.mapDir(files)

    @JvmStatic
    fun mapDirOf(files: Map<String, String>): C_SourceDir = C_SourceDir.mapDirOf(files)

    @JvmStatic
    fun diskDir(dir: File): C_SourceDir = C_SourceDir.diskDir(dir)

    @JvmStatic
    fun parseSourcePath(s: String): C_SourcePath? = C_SourcePath.parseOpt(s)
}
