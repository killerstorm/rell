package net.postchain.rell.misc

import net.postchain.rell.PostchainUtils
import net.postchain.rell.makeRellPostchainConfig
import net.postchain.rell.parser.C_IncludeResolver
import net.postchain.rell.parser.C_VirtualIncludeDir
import net.postchain.rell.test.GtvTestUtils
import org.junit.Test
import kotlin.test.assertEquals

class RellConfigGenTest {
    @Test fun testNoTemplateSingleFile() {
        val files = mapOf("main.rell" to "class foo {}")
        chkCfg(files, null, "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")
    }

    @Test fun testNoTemplateMultipleFiles() {
        val files = mapOf("main.rell" to "class user {}", "foo.rell" to "class foo {}", "bar.rell" to "class bar {}")
        chkCfg(files, null, "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class user {}'}}}}")
    }

    @Test fun testNoTemplateIncludedFiles() {
        val files = mapOf("main.rell" to "include 'foo';", "foo.rell" to "include 'bar';", "bar.rell" to "class bar {}")
        val expFiles = "{'main.rell':'include \\u0027foo\\u0027;','foo.rell':'include \\u0027bar\\u0027;','bar.rell':'class bar {}'}"
        chkCfg(files, null, "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':$expFiles}}}")
    }

    @Test fun testTemplate() {
        val files = mapOf("main.rell" to "class foo {}")

        chkCfg(files, "{}", "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'foo':'bar'}",
                "{'foo':'bar','gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'gtx':{}}",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'gtx':{'foo':'bar'}}",
                "{'gtx':{'foo':'bar','rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{}}}",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'foo':'bar'}}}",
                "{'gtx':{'rell':{'foo':'bar','mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'mainFile':'garbage.rell'}}}",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg(files, "{'gtx':{'rell':{'sources_v0.8':{}}}}",
                "{'gtx':{'rell':{'sources_v0.8':{'main.rell':'class foo {}'},'mainFile':'main.rell'}}}")

        chkCfg(files, "{'gtx':{'rell':{'sources_v0.8':{'garbage.rell':'garbage'}}}}",
                "{'gtx':{'rell':{'sources_v0.8':{'garbage.rell':'garbage','main.rell':'class foo {}'},'mainFile':'main.rell'}}}")

        chkCfg(files, "{'gtx':{'rell':{'sources_v0.8':{'main.rell':'garbage'}}}}",
                "{'gtx':{'rell':{'sources_v0.8':{'main.rell':'class foo {}'},'mainFile':'main.rell'}}}")
    }

    @Test fun testTemplateXml() {
        val files = mapOf("main.rell" to "class foo {}")

        chkCfg0(files, "<dict/>", "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")
        chkCfg0(files, "<dict></dict>", "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        val header = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

        chkCfg0(files, """$header<dict></dict>""",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg0(files, """$header<dict/>""",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg0(files, """$header<dict><entry key="gtx"><dict/></entry></dict>""",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")

        chkCfg0(files, """$header<dict><entry key="gtx"><dict></dict></entry></dict>""",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'class foo {}'}}}}")
    }

    private fun chkCfg(files: Map<String, String>, templateJson: String?, expectedJson: String) {
        val templateXml = if (templateJson == null) null else jsonToXml(templateJson)
        chkCfg0(files, templateXml, expectedJson)
    }

    private fun chkCfg0(files: Map<String, String>, templateXml: String?, expectedJson: String, pretty: Boolean = false) {
        val incDir = C_VirtualIncludeDir(files)
        val incRes = C_IncludeResolver(incDir)
        val actualXml = makeRellPostchainConfig(incRes, "main.rell", templateXml, pretty)
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
