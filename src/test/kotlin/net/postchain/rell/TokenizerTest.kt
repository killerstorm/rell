package net.postchain.rell

import org.junit.Test

class TokenizerTest: BaseRellTest(false) {
    @Test fun testIntegerLiteral() {
        tst.errMsgPos = true

        chk("0", "int[0]")
        chk("123456789", "int[123456789]")
        chk("9223372036854775807", "int[9223372036854775807]")
        chk("01234567", "int[1234567]")
        chk("\n9223372036854775808", "ct_err(2:1):lex_int:9223372036854775808")
        chk("\n1a", "ct_err(2:1):lex_int:1a")
        chk("\n1e", "ct_err(2:1):lex_int:1e")

        chk("0x0", "int[0]")
        chk("0xA", "int[10]")
        chk("0xa", "int[10]")
        chk("0xF", "int[15]")
        chk("0xf", "int[15]")
        chk("0xABCD", "int[43981]")
        chk("0xabcd", "int[43981]")
        chk("0x1234ABCD", "int[305441741]")
        chk("0x1234abcd", "int[305441741]")
        chk("0xFED", "int[4077]")
        chk("0xFed", "int[4077]")
        chk("0xfed", "int[4077]")
        chk("\n0x", "ct_err(2:1):lex_int:0x")
        chk("\n0X0", "ct_err(2:1):lex_int:0X0")
        chk("\n0xg", "ct_err(2:1):lex_int:0xg")
        chk("\n0xz", "ct_err(2:1):lex_int:0xz")
    }

    @Test fun testStringLiteral() {
        tst.errMsgPos = true
        val nl = "\n"

        chk("""  ''  """, "text[]")
        chk("""  ""  """, "text[]")
        chk("""  "Hello"  """, "text[Hello]")
        chk("""  'Hello'  """, "text[Hello]")
        chk("""  "'"  """, "text[']")
        chk("""  '"'  """, "text[\"]")
        chk("""  " "  """, "text[ ]")
        chk("""  ' '  """, "text[ ]")

        chk("""  "\b"  """, """text[\b]""")
        chk("""  '\b'  """, """text[\b]""")
        chk("""  "\t"  """, """text[\t]""")
        chk("""  '\t'  """, """text[\t]""")
        chk("""  "\r"  """, """text[\r]""")
        chk("""  '\r'  """, """text[\r]""")
        chk("""  "\n"  """, """text[\n]""")
        chk("""  '\n'  """, """text[\n]""")
        chk("""  "\""  """, """text["]""")
        chk("""  '\"'  """, """text["]""")
        chk("""  "\'"  """, """text[']""")
        chk("""  '\''  """, """text[']""")
        chk("""  "\\"  """, """text[\\]""")
        chk("""  '\\'  """, """text[\\]""")
        chk("""$nl "\q"  """, "ct_err(2:3):lex_string_esc")
        chk("""$nl '\q'  """, "ct_err(2:3):lex_string_esc")

        chk(""" "\u0031\u0032\u0033" """, "text[123]")
        chk(""" '\u0031\u0032\u0033' """, "text[123]")
        chk(""" "\u003A\u003B\u003C" """, "text[:;<]")
        chk(""" '\u003A\u003B\u003C' """, "text[:;<]")
        chk(""" "\u003a\u003b\u003c" """, "text[:;<]")
        chk(""" '\u003a\u003b\u003c' """, "text[:;<]")
        chk("""$nl "\u003g" """, "ct_err(2:3):lex_string_esc_unicode")
        chk("""$nl '\u003g' """, "ct_err(2:3):lex_string_esc_unicode")
        chk("""$nl "\u003" """, "ct_err(2:3):lex_string_esc_unicode")
        chk("""$nl '\u003' """, "ct_err(2:3):lex_string_esc_unicode")
        chk("""$nl "\u003!" """, "ct_err(2:3):lex_string_esc_unicode")
        chk("""$nl '\u003!' """, "ct_err(2:3):lex_string_esc_unicode")

        chk("""$nl "Hello${nl}World"  """, "ct_err(2:8):lex_string_unclosed")
        chk("""$nl 'Hello${nl}World'  """, "ct_err(2:8):lex_string_unclosed")
        chk("""$nl "Hello\${nl}World"  """, "ct_err(2:8):lex_string_esc")
        chk("""$nl 'Hello\${nl}World'  """, "ct_err(2:8):lex_string_esc")
        chk("""$nl "Hello  """, "ct_err(2:12):lex_string_unclosed")
        chk("""$nl 'Hello  """, "ct_err(2:12):lex_string_unclosed")
    }

    @Test fun testByteArrayLiteral() {
        tst.errMsgPos = true
        val nl = "\n"

        chk("""  x""  """, "byte_array[]")
        chk("""  x''  """, "byte_array[]")
        chk("""  x"1234ABCD"  """, "byte_array[1234abcd]")
        chk("""  x'1234ABCD'  """, "byte_array[1234abcd]")
        chk("""  x"1234abcd"  """, "byte_array[1234abcd]")
        chk("""  x'1234abcd'  """, "byte_array[1234abcd]")

        chk("""$nl x"1"  """, "ct_err(2:2):parser_bad_hex:1")
        chk("""$nl x'1'  """, "ct_err(2:2):parser_bad_hex:1")
        chk("""$nl x"123"  """, "ct_err(2:2):parser_bad_hex:123")
        chk("""$nl x'123'  """, "ct_err(2:2):parser_bad_hex:123")
        chk("""$nl x"ge"  """, "ct_err(2:2):parser_bad_hex:ge")
        chk("""$nl x'ge'  """, "ct_err(2:2):parser_bad_hex:ge")
        chk("""$nl x"${'\n'}"  """, "ct_err(2:4):lex_bytearray_unclosed")
        chk("""$nl x'${'\n'}'  """, "ct_err(2:4):lex_bytearray_unclosed")
        chk("""$nl x"\u0030\u0031"  """, """ct_err(2:2):parser_bad_hex:\u0030\u0031""")
        chk("""$nl x'\u0030\u0031'  """, """ct_err(2:2):parser_bad_hex:\u0030\u0031""")
        chk("""$nl x" 1234"  """, "ct_err(2:2):parser_bad_hex: 1234")
        chk("""$nl x' 1234'  """, "ct_err(2:2):parser_bad_hex: 1234")
        chk("""$nl x"1234 "  """, "ct_err(2:2):parser_bad_hex:1234 ")
        chk("""$nl x'1234 '  """, "ct_err(2:2):parser_bad_hex:1234 ")

        chk("""$nl X"1234"  """, "ct_err(2:3):syntax")
        chk("""$nl X'1234'  """, "ct_err(2:3):syntax")
        chk("""$nl x "1234"  """, "ct_err(2:4):syntax")
        chk("""$nl x '1234'  """, "ct_err(2:4):syntax")
    }

    @Test fun testComments() {
        tst.errMsgPos = true

        chk("123//456\n", "int[123]")
        chk("//456\n123", "int[123]")
        chk("//456\n\n123", "int[123]")
        chk("123//789\n+456", "int[579]")
        chk("123+//789\n456", "int[579]")
        chk("// /*\n 123 // */ 456\n", "int[123]")
        chk("'Hello//' + '//World'", "text[Hello////World]")

        chk("/*123*/456", "int[456]")
        chk("/*/123*/456", "int[456]")
        chk("123/*456*/", "int[123]")
        chk("/*456\n789*/123", "int[123]")
        chk("123/*777\n888*/+456", "int[579]")
        chk("/*123//*/456", "int[456]")
        chk("'Hello/*'+'*/World'", "text[Hello/**/World]")

        chkEx("{\n 123 /* 456", "ct_err(2:12):lex_comment_eof")
    }

    @Test fun testErrPos() {
        tst.errMsgPos = true
        chkEx("{ val x = 5;\nval x = 10; }", "ct_err(2:5):var_dupname:x")
        chkEx("{ val x = 5;\nreturn; }", "ct_err(2:1):stmt_return_query_novalue")
    }
}
