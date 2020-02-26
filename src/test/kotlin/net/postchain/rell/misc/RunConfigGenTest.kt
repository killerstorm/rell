/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.gtv.GtvDecoder
import net.postchain.rell.*
import net.postchain.rell.compiler.C_MapSourceDir
import net.postchain.rell.test.GtvTestUtils
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.test.unwrap
import net.postchain.rell.tools.runcfg.RellRunConfigGenerator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                    <chain name="user" iid="33">
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

        chkFile(files, "blockchains/33/brid.txt", "A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99")

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

        chkFileBin(files, "blockchains/33/0.gtv", """{"signers":["0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57"]}""")

        assertEquals(setOf<String>(), files.keys)
    }

    @Test fun testAddSignersFalse() {
        val configFiles = mapOf(
                "my-config.properties" to "node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57"
        )

        val files = generate(mapOf(), configFiles, """
            <run>
                <nodes>
                    <config src="my-config.properties" add-signers="false"/>
                </nodes>
                <chains>
                    <chain name="user" iid="33">
                        <config height="0">
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", "node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57")
        chkFile(files, "blockchains/33/brid.txt", "F9161BC0ABE91C6ACAB68B4A0B18CCD5763AE5B5798BC522209DDCF6D0302630")
        chkFileBin(files, "blockchains/33/0.gtv", """{"signers":[]}""")

        chkFile(files, "blockchains/33/0.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <dict>
                <entry key="signers">
                    <array/>
                </entry>
            </dict>
        """)

        assertEquals(setOf<String>(), files.keys)
    }

    @Test fun testAddSignersDefault() {
        val configFiles = mapOf(
                "my-config.properties" to "node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57"
        )

        val files = generate(mapOf(), configFiles, """
            <run>
                <nodes>
                    <config src="my-config.properties"/>
                </nodes>
                <chains>
                    <chain name="user" iid="33">
                        <config height="0">
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", "node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57")
        chkFile(files, "blockchains/33/brid.txt", "A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99")
        chkFileBin(files, "blockchains/33/0.gtv", """{"signers":["0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57"]}""")

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
                "app.rell" to "module; import sub;\nfunction main(){}",
                "sub.rell" to "module; function sub(){}",
                "other.rell" to "module; function other(){}"
        )

        val files = generate(sourceFiles, mapOf(), """
            <run>
                <nodes>
                    <config>x=123</config>
                </nodes>
                <chains>
                    <chain name="user" iid="33">
                        <config height="0">
                            <app module="app" add-defaults="false"/>
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", "x=123")

        chkFile(files, "blockchains/33/brid.txt", "D44732A2C0C545B0CDB44C90F3B05432531283BCB35D5BBA0BAD7A1793B06C90")

        chkFile(files, "blockchains/33/0.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <dict>
                <entry key="gtx">
                    <dict>
                        <entry key="rell">
                            <dict>
                                <entry key="modules">
                                    <array>
                                        <string>app</string>
                                    </array>
                                </entry>
                                <entry key="sources_v${RellTestUtils.RELL_VER}">
                                    <dict>
                                        <entry key="app.rell">
                                            <string>module; import sub;
            function main(){}</string>
                                        </entry>
                                        <entry key="sub.rell">
                                            <string>module; function sub(){}</string>
                                        </entry>
                                    </dict>
                                </entry>
                            </dict>
                        </entry>
                    </dict>
                </entry>
                <entry key="signers">
                    <array/>
                </entry>
            </dict>
        """)

        chkFileBin(files, "blockchains/33/0.gtv",
                """{"gtx":{"rell":{"modules":["app"],"sources_v0.10":{
                    "app.rell":"module; import sub;\nfunction main(){}","sub.rell":"module; function sub(){}"}}},
                    "signers":[]}
                """.unwrap()
        )

        assertEquals(setOf<String>(), files.keys)
    }

    @Test fun testDependenciesChainName() {
        chkDependencies(""" chain="user" """)
    }

    @Test fun testDependenciesBrid() {
        chkDependencies(""" brid="A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99" """)
    }

    private fun chkDependencies(depStr: String) {
        val files = generate(mapOf(), mapOf(), """
            <run>
                <nodes>
                    <config>
                        node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
                    </config>
                </nodes>
                <chains>
                    <chain name="user" iid="33">
                        <config height="0"/>
                    </chain>
                    <chain name="city" iid="55">
                        <config height="0">
                            <dependencies>
                                <dependency name="user_dep" $depStr />
                            </dependencies>
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", "node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57")
        chkFile(files, "blockchains/33/brid.txt", "A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99")
        chkFileBin(files, "blockchains/33/0.gtv", """{"signers":["0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57"]}""")
        chkFile(files, "blockchains/55/brid.txt", "D2DE94C67C3FAEDFB14D241FA902D3D4BA285F4C2813A6750F59CF502B5ADDEC")

        chkFileBin(files, "blockchains/55/0.gtv", """{
            "dependencies":[["user_dep","A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99"]],
            "signers":["0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57"]}
        """.unwrap())

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

        chkFile(files, "blockchains/55/0.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <dict>
                <entry key="dependencies">
                    <array>
                        <array>
                            <string>user_dep</string>
                            <bytea>A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99</bytea>
                        </array>
                    </array>
                </entry>
                <entry key="signers">
                    <array>
                        <bytea>0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57</bytea>
                    </array>
                </entry>
            </dict>
        """)

        assertEquals(setOf<String>(), files.keys)
    }

    @Test(expected = RellCliErr::class)
    fun testDependencyWithGreaterIid() {
        generate(mapOf(), mapOf(), """
            <run>
                <nodes>
                    <config>
                        node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
                    </config>
                </nodes>
                <chains>
                    <chain name="user" iid="55">
                        <config height="0"/>
                    </chain>
                    <chain name="city" iid="33">
                        <config height="0">
                            <dependencies>
                                <dependency name="user_dep" chain="user" />
                            </dependencies>
                        </config>
                    </chain>
                </chains>
            </run>
        """)
    }

    @Test fun testGeneratedBrid() {
        val files = generate(mapOf(), mapOf(), """
            <run>
                <nodes>
                    <config>
                        node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
                    </config>
                </nodes>
                <chains>
                    <chain name="user" iid="33">
                        <config height="0"/>
                    </chain>
                    <chain name="city" iid="55">
                        <config height="0">
                            <dependencies>
                                <dependency name="user_dep" chain="user" />
                            </dependencies>
                        </config>
                    </chain>
                </chains>
            </run>
        """)

        chkFile(files, "node-config.properties", "node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57")
        chkFile(files, "blockchains/33/brid.txt", "A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99")
        chkFileBin(files, "blockchains/33/0.gtv", """{"signers":["0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57"]}""")
        chkFile(files, "blockchains/55/brid.txt", "D2DE94C67C3FAEDFB14D241FA902D3D4BA285F4C2813A6750F59CF502B5ADDEC")

        chkFileBin(files, "blockchains/55/0.gtv", """{
            "dependencies":[["user_dep","A5D2F114B70602A5145FB705EC4FED482F47B2AAB9780DBD17564DF1E4150F99"]],
            "signers":["0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57"]}
        """.unwrap())
    }

    private fun generate(
            sourceFiles: Map<String, String>,
            configFiles: Map<String, String>,
            confText: String
    ): MutableMap<String, DirFile> {
        val sourceDir = C_MapSourceDir.of(sourceFiles)
        val configDir = MapGeneralDir(configFiles)
        val conf = RellRunConfigGenerator.generate(sourceDir, configDir, "run.xml", confText.trimIndent())
        val files = RellRunConfigGenerator.buildFiles(conf).toMutableMap()
        return files
    }

    private fun chkFile(files: MutableMap<String, DirFile>, path: String, expected: String) {
        val actualFile = files.remove(path)
        assertNotNull(actualFile, "File not found: $path ${files.keys}")
        val actual = (actualFile as TextDirFile).text
        assertEquals(expected.trimIndent().trim(), actual.trim())
    }

    private fun chkFileBin(files: MutableMap<String, DirFile>, path: String, expected: String) {
        val actualFile = files.remove(path)
        assertNotNull(actualFile, "File not found: $path ${files.keys}")
        assertTrue(actualFile is BinaryDirFile, "File is not binary: $path")

        val bytes = actualFile.data.toByteArray()
        val gtv = GtvDecoder.decodeGtv(bytes)
        val actual = GtvTestUtils.encodeGtvStr(gtv)
        assertEquals(expected, actual)
    }
}
