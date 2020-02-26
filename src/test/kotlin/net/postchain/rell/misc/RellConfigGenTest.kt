/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.gtv.GtvFactory
import net.postchain.rell.PostchainUtils
import net.postchain.rell.RellConfigGen
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.compiler.C_Compiler
import net.postchain.rell.compiler.C_MapSourceDir
import net.postchain.rell.test.GtvTestUtils
import net.postchain.rell.test.RellTestUtils
import org.junit.Test
import kotlin.test.assertEquals

class RellConfigGenTest {
    private val ver = "v${RellTestUtils.RELL_VER}"

    @Test fun testNoTemplateSingleFile() {
        val files = mapOf("main.rell" to "entity foo {}")
        chkCfg(files, null, "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")
    }

    @Test fun testNoTemplateMultipleFiles() {
        val files = mapOf("main.rell" to "entity user {}", "foo.rell" to "module; entity foo {}", "bar.rell" to "module; entity bar {}")
        chkCfg(files, null, "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity user {}'}}}}")
    }

    @Test fun testNoTemplateImportedModules() {
        val files = mapOf("main.rell" to "import foo;", "foo.rell" to "module; import bar;", "bar.rell" to "module; entity bar {}")
        val expFiles = "{'bar.rell':'module; entity bar {}','foo.rell':'module; import bar;','main.rell':'import foo;'}"
        chkCfg(files, null, "{'gtx':{'rell':{'modules':[''],'sources_$ver':$expFiles}}}")
    }

    @Test fun testTemplate() {
        val files = mapOf("main.rell" to "entity foo {}")

        chkCfg(files, "{}", "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'foo':'bar'}",
                "{'foo':'bar','gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'foo':'bar'}}",
                "{'gtx':{'foo':'bar','rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{}}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'foo':'bar'}}}",
                "{'gtx':{'rell':{'foo':'bar','modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'modules':['junk','trash','garbage']}}}",
                "{'gtx':{'rell':{'modules':['junk','trash','garbage',''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'modules':['','','']}}}",
                "{'gtx':{'rell':{'modules':['','',''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'modules':['']}}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'modules':[]}}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'sources_$ver':{}}}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'sources_$ver':{'garbage.rell':'garbage'}}}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'garbage.rell':'garbage','main.rell':'entity foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'sources_$ver':{'main.rell':'garbage'}}}}",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")
    }

    @Test fun testTemplateXml() {
        val files = mapOf("main.rell" to "entity foo {}")

        chkCfg0(files, "<dict/>", "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")
        chkCfg0(files, "<dict></dict>", "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        val header = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

        chkCfg0(files, """$header<dict></dict>""",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg0(files, """$header<dict/>""",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg0(files, """$header<dict><entry key="gtx"><dict/></entry></dict>""",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")

        chkCfg0(files, """$header<dict><entry key="gtx"><dict></dict></entry></dict>""",
                "{'gtx':{'rell':{'modules':[''],'sources_$ver':{'main.rell':'entity foo {}'}}}}")
    }

    private fun chkCfg(files: Map<String, String>, templateJson: String?, expectedJson: String) {
        val templateXml = if (templateJson == null) null else jsonToXml(templateJson)
        chkCfg0(files, templateXml, expectedJson)
    }

    private fun chkCfg0(files: Map<String, String>, templateXml: String?, expectedJson: String) {
        val sourceDir = C_MapSourceDir.of(files)
        val modules = listOf(R_ModuleName.EMPTY)
        val cRes = C_Compiler.compile(sourceDir, modules)

        check(cRes.errors.isEmpty()) { "Errors: ${cRes.errors.map { it.code }}" }
        check(cRes.app != null)

        val templateGtv = if (templateXml == null) GtvFactory.gtv(mapOf()) else PostchainUtils.xmlToGtv(templateXml)
        val configGen = RellConfigGen(sourceDir, modules, cRes.files)

        val actualGtv = configGen.makeConfig(templateGtv)
        val actualXml = RellConfigGen.configToText(actualGtv)
        val actualJson = xmlToJson(actualXml)
        assertEquals(expectedJson, actualJson)
    }

    private fun xmlToJson(xml: String): String {
        val gtv = PostchainUtils.xmlToGtv(xml)
        return GtvTestUtils.gtvToStr(gtv)
    }

    private fun jsonToXml(json: String): String {
        val gtv = GtvTestUtils.strToGtv(json)
        return PostchainUtils.gtvToXml(gtv)
    }
}
