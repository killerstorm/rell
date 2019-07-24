package net.postchain.rell.misc

import net.postchain.rell.MapGeneralDir
import net.postchain.rell.parser.C_MapSourceDir
import net.postchain.rell.tools.runcfg.RellRunConfigGenerator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RunConfigGenTest {
    @Test fun testNodeConfig() {
        val configFiles = mapOf(
                "my-config.properties" to """
                    include=private.properties
                    foo=123
                    node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
                """.trimIndent(),
                "private.properties" to "bar=456\n",
                "other.properties" to "other=789"
        )

        val files = generate(mapOf(), configFiles, """
            <run>
                <nodes>
                    <config src="my-config.properties"/>
                </nodes>
                <chains>
                    <chain name="user" iid="33" brid="01234567abcdef01234567abcdef01234567abcdef01234567abcdef01234567">
                        <config height="0">
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", """
            include=private.properties
            foo=123
            node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
        """)

        chkFile(files, "private.properties", "bar=456")

        chkFile(files, "blockchains/33/brid.txt", "01234567ABCDEF01234567ABCDEF01234567ABCDEF01234567ABCDEF01234567")

        chkFile(files, "blockchains/33/0.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <dict>
                <entry key="signers">
                    <array>
                        <bytea>0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57</bytea>
                    </array>
                </entry>
            </dict>
        """)

        assertEquals(setOf<String>(), files.keys)
    }

    @Test fun testModule() {
        val sourceFiles = mapOf(
                "app.rell" to "include 'sub';\nfunction main(){}",
                "sub.rell" to "function sub(){}",
                "other.rell" to "function other(){}"
        )

        val files = generate(sourceFiles, mapOf(), """
            <run>
                <nodes>
                    <config>x=123</config>
                </nodes>
                <chains>
                    <chain name="user" iid="33" brid="01234567abcdef01234567abcdef01234567abcdef01234567abcdef01234567">
                        <config height="0">
                            <module src="app" add-defaults="false"/>
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", "x=123")

        chkFile(files, "blockchains/33/brid.txt", "01234567ABCDEF01234567ABCDEF01234567ABCDEF01234567ABCDEF01234567")

        chkFile(files, "blockchains/33/0.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <dict>
                <entry key="gtx">
                    <dict>
                        <entry key="rell">
                            <dict>
                                <entry key="mainFile">
                                    <string>app.rell</string>
                                </entry>
                                <entry key="sources_v0.9">
                                    <dict>
                                        <entry key="app.rell">
                                            <string>include 'sub';
            function main(){}</string>
                                        </entry>
                                        <entry key="sub.rell">
                                            <string>function sub(){}</string>
                                        </entry>
                                    </dict>
                                </entry>
                            </dict>
                        </entry>
                    </dict>
                </entry>
            </dict>
        """)

        assertEquals(setOf<String>(), files.keys)
    }

    private fun generate(
            sourceFiles: Map<String, String>,
            configFiles: Map<String, String>,
            confText: String
    ): MutableMap<String, String> {
        val sourceDir = C_MapSourceDir.of(sourceFiles)
        val configDir = MapGeneralDir(configFiles)
        val conf = RellRunConfigGenerator.generate(sourceDir, configDir, "run.xml", confText.trimIndent())
        val files = RellRunConfigGenerator.buildFiles(conf).toMutableMap()
        return files
    }

    private fun chkFile(files: MutableMap<String, String>, path: String, expected: String) {
        val actual = files.remove(path)
        assertNotNull(actual, "File not found: $path ${files.keys}")
        assertEquals(expected.trimIndent().trim(), actual!!.trim())
    }
}
