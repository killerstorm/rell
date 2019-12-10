package net.postchain.rell

import net.postchain.rell.lib.LibBlockTransactionTest
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.GtvTestUtils
import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class AppGtvMetaTest: BaseRellTest(false) {
    @Test fun testEmpty() {
        chkMeta("", """{"modules":{"":{"name":""}}}""")
    }

    @Test fun testModuleAbstract() {
        file("lib.rell", "abstract module;")
        chkMeta("import lib;", """{"modules":{
            "":{"name":""},
            "lib":{"name":"lib","abstract":1}
        }}""")
    }

    @Test fun testModuleExternal() {
        file("lib.rell", "@external module;")
        chkMeta("import lib;", """{"modules":{
            "":{"name":""},
            "lib":{"name":"lib","external":1}
        }}""")
    }

    @Test fun testModuleExternalImport() {
        tstCtx.useSql = true
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "beefdead")
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 2000)
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain(333, true)
        initExternalChain(555, false)

        file("lib.rell", "@external module;")

        chkMeta("@external('foo') import foo_lib: lib; @external('bar') import bar_lib: lib; import lib;", """{"modules":{
            "": {"name":""},
            "lib": {"name":"lib","external":1},
            "lib[foo]": {"name":"lib", "external":1, "externalChain":"foo"},
            "lib[bar]": {"name":"lib", "external":1, "externalChain":"bar"}
        }}""")
    }

    private fun initExternalChain(chainId: Long, resetDatabase: Boolean) {
        run {
            val t = RellCodeTester(tst.tstCtx)
            t.chainId = chainId
            t.dropTables = resetDatabase
            t.init()
        }
        tst.dropTables = false
    }

    @Test fun testDefEntity() {
        chkMetaDef("entity user { name; }", "entities", "user", """{
            "mount":"user",
            "log":0,
            "attributes": {"name":{"type":"text", "mutable":0}},
            "keys": [],
            "indexes": []
        }""")

        chkMetaDef("entity user { name; value: integer; key name, value; }", "entities", "user", """{
            "mount":"user",
            "log":0,
            "attributes": {
                "name":{"type":"text", "mutable":0},
                "value":{"type":"integer", "mutable":0}
            },
            "keys": [{"attributes":["name","value"]}],
            "indexes": []
        }""")

        chkMetaDef("entity user { name; value: integer; index name, value; }", "entities", "user", """{
            "mount":"user",
            "log":0,
            "attributes": {
                "name":{"type":"text", "mutable":0},
                "value":{"type":"integer", "mutable":0}
            },
            "keys": [],
            "indexes": [{"attributes":["name","value"]}]
        }""")

        chkMetaDef("@mount('foo.bar.') entity user { name; }", "entities", "user", """{
            "mount":"foo.bar.user",
            "log":0,
            "attributes": {"name":{"type":"text", "mutable":0}},
            "keys": [],
            "indexes": []
        }""")
    }

    @Test fun testDefObject() {
        chkMetaDef("object state { mutable value: integer = 0; }", "objects", "state", """{
            "mount":"state",
            "attributes": {"value":{"type":"integer", "mutable":1}}
        }""")
    }

    @Test fun testDefStruct() {
        chkMetaDef("struct data { value: integer; }", "structs", "data", """{
            "attributes": {"value":{"type":"integer", "mutable":0}}
        }""")
    }

    @Test fun testDefOperation() {
        chkMetaDef("operation op(x: text) {}", "operations", "op", """{
            "parameters":[
                {"name":"x", "type":"text"}
            ]
        }""")
    }

    @Test fun testDefQuery() {
        chkMetaDef("query q(x: text) = x.size();", "queries", "q", """{
            "type": "integer",
            "parameters":[
                {"name":"x", "type":"text"}
            ]
        }""")
    }

    @Test fun testDefFunction() {
        chkMetaDef("function f(x: text, y: integer): integer = x.size();", "functions", "f", """{
            "type":"integer",
            "parameters":[
                {"name":"x", "type":"text"},
                {"name":"y", "type":"integer"}
            ]
        }""")
    }

    @Test fun testDefEnum() {
        chkMetaDef("enum e {A, B, C}", "enums", "e", """{
            "values":{
                "A":{},
                "B":{},
                "C":{}
            }
        }""")
    }

    @Test fun testDefNamespace() {
        chkMetaDef("namespace foo { namespace bar { function f() {} }}", "functions", "foo.bar.f", """{
            "type":"unit",
            "parameters":[]
        }""")
    }

    @Test fun testTypeSimple() {
        initTypes()
        chkMetaType("integer", """ "integer" """)
        chkMetaType("decimal", """ "decimal" """)
        chkMetaType("boolean", """ "boolean" """)
        chkMetaType("text", """ "text" """)
        chkMetaType("byte_array", """ "byte_array" """)
        chkMetaType("rowid", """ "rowid" """)
        chkMetaType("json", """ "json" """)
        chkMetaType("range", """ "range" """)
        chkMetaType("gtv", """ "gtv" """)
    }

    @Test fun testTypeCustom() {
        initTypes()
        chkMetaType("lib.user", """ "lib!user" """)
        chkMetaType("lib.rec", """ "lib!rec" """)
        chkMetaType("lib.kind", """ "lib!kind" """)
    }

    @Test fun testTypeCollections() {
        initTypes()
        chkMetaType("list<integer>", """{"type":"list","value":"integer"}""")
        chkMetaType("set<integer>", """{"type":"set","value":"integer"}""")
        chkMetaType("map<integer,text>", """{"type":"map","key":"integer","value":"text"}""")
    }

    @Test fun testTypeTuple() {
        initTypes()

        chkMetaType("(integer,text)", """{
            "type":"tuple",
            "fields":[
                {"name":null,"type":"integer"},
                {"name":null,"type":"text"}
            ]
        }""")

        chkMetaType("(x:integer,y:text)", """{
            "type":"tuple",
            "fields":[
                {"name":"x","type":"integer"},
                {"name":"y","type":"text"}
            ]
        }""")

        chkMetaType("(integer,y:text)", """{
            "type":"tuple",
            "fields":[
                {"name":null,"type":"integer"},
                {"name":"y","type":"text"}
            ]
        }""")
    }

    @Test fun testTypeNullable() {
        initTypes()
        chkMetaType("integer?", """{"type":"nullable","value":"integer"}""")
        chkMetaType("lib.user?", """{"type":"nullable","value":"lib!user"}""")
    }

    @Test fun testTypeVirtual() {
        initTypes()

        chkMetaType("virtual<lib.rec>", """{"type":"virtual","value":"lib!rec"}""")
        chkMetaType("virtual<list<integer>>", """{"type":"virtual","value":{"type":"list","value":"integer"}}""")
        chkMetaType("virtual<set<integer>>", """{"type":"virtual","value":{"type":"set","value":"integer"}}""")
        chkMetaType("virtual<map<text,integer>>", """{"type":"virtual","value":{"type":"map","key":"text","value":"integer"}}""")

        chkMetaType("virtual<(integer,text)>", """{"type":"virtual","value":{
            "type":"tuple",
            "fields":[
                {"name":null,"type":"integer"},
                {"name":null,"type":"text"}
            ]
        }}""")
    }

    @Test fun testTypeComplex() {
        initTypes()

        chkMetaType("list<integer?>", """{"type":"list","value":{"type":"nullable","value":"integer"}}""")
        chkMetaType("list<integer>?", """{"type":"nullable","value":{"type":"list","value":"integer"}}""")

        chkMetaType("map<(integer,text),list<set<lib.user>>>", """{
            "type":"map",
            "key":{
                "type":"tuple",
                "fields":[
                    {"name":null,"type":"integer"},
                    {"name":null,"type":"text"}
                ]
            },
            "value":{
                "type":"list",
                "value":{"type":"set","value":"lib!user"}
            }
        }""")
    }

    private fun initTypes() {
        file("lib.rell", """module;
            entity user {}
            struct rec {}
            enum kind { A }
        """)
        def("import lib;")
    }

    private fun chkMetaType(type: String, expected: String) {
        chkMeta("function f(x: $type) {}", """{"modules":{
            "":{
                "name":"",
                "functions":{"f":{
                    "type": "unit",
                    "parameters":[
                        {"name":"x", "type":$expected}
                    ]
                }}
            },
            "lib":{
                "name":"lib",
                "entities":{"user":{"attributes":{},"indexes":[],"keys":[],"log":0,"mount":"user"}},
                "structs":{"rec":{"attributes":{}}},
                "enums":{"kind":{"values":{"A":{}}}}
            }
        }}""")
    }

    private fun chkMetaDef(code: String, key: String, name: String, expected: String) {
        chkMeta(code, """{"modules":{"":{"name":"","$key":{"$name":$expected}}}}""")
    }

    private fun chkMeta(code: String, expected: String) {
        tst.strictToString = false
        val expected2 = GtvTestUtils.encodeGtvStr(GtvTestUtils.decodeGtvStr(expected))
        chkQueryEx(code, "rell.get_app_structure", listOf(), expected2)
    }
}
