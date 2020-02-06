package net.postchain.rell.repl

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class ReplValueFormatTest: BaseRellTest(false) {
    @Test fun testDefault() {
        initRepl("od")
        repl.chk("123", "123")
        repl.chk("l", "[(123,Hello), (456,Bye)]")
        repl.chk("m", "{123=Hello, 456=Bye}")
        repl.chk("m2", "{Hello=123, Bye=456}")
    }

    @Test fun testStrict() {
        initRepl("os")
        repl.chk("123", "int[123]")
        repl.chk("l", "list<(integer,text)>[(int[123],text[Hello]),(int[456],text[Bye])]")
        repl.chk("m", "map<integer,text>[int[123]=text[Hello],int[456]=text[Bye]]")
        repl.chk("m2", "map<text,integer>[text[Hello]=int[123],text[Bye]=int[456]]")
    }

    @Test fun testOneItemPerLine() {
        initRepl("ol")
        repl.chk("123", "123")
        repl.chk("l", "(123,Hello)\n(456,Bye)")
        repl.chk("m", "123=Hello\n456=Bye")
        repl.chk("m2", "Hello=123\nBye=456")
    }

    @Test fun testGtvJson() {
        initRepl("oj")
        repl.chk("123", "123")

        repl.chk("l", """
            [
              [
                123,
                "Hello"
              ],
              [
                456,
                "Bye"
              ]
            ]
        """.trimIndent())

        repl.chk("m", """
            [
              [
                123,
                "Hello"
              ],
              [
                456,
                "Bye"
              ]
            ]
        """.trimIndent())

        repl.chk("m2", """
            {
              "Bye": 456,
              "Hello": 123
            }
        """.trimIndent())
    }

    @Test fun testGtvXml() {
        initRepl("ox")
        repl.chk("123", "<int>123</int>")

        repl.chk("l", """
            <array>
                <array>
                    <int>123</int>
                    <string>Hello</string>
                </array>
                <array>
                    <int>456</int>
                    <string>Bye</string>
                </array>
            </array>
        """.trimIndent())

        repl.chk("m", """
            <array>
                <array>
                    <int>123</int>
                    <string>Hello</string>
                </array>
                <array>
                    <int>456</int>
                    <string>Bye</string>
                </array>
            </array>
        """.trimIndent())

        repl.chk("m2", """
            <dict>
                <entry key="Bye">
                    <int>456</int>
                </entry>
                <entry key="Hello">
                    <int>123</int>
                </entry>
            </dict>
        """.trimIndent())
    }

    private fun initRepl(format: String) {
        repl.outPlainValues = true
        repl.chk("\\$format")
        repl.chk("val l = [(123,'Hello'),(456,'Bye')];")
        repl.chk("val m = [123:'Hello',456:'Bye'];")
        repl.chk("val m2 = ['Hello':123,'Bye':456];")
    }
}
