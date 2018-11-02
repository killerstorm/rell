package net.postchain.rell.lib

import net.postchain.rell.BaseRellTest
import org.junit.Test

class LibTextTest: BaseRellTest(false) {
    @Test fun testEmpty() {
        chk("''.empty()", "boolean[true]")
        chk("'X'.empty()", "boolean[false]")
        chk("'Hello'.empty()", "boolean[false]")
    }

    @Test fun testSize() {
        chk("''.size()", "int[0]")
        chk("'X'.size()", "int[1]")
        chk("'Hello'.size()", "int[5]")
    }

    @Test fun testSubscript() {
        chk("'Hello'[0]", "text[H]")
        chk("'Hello'[1]", "text[e]")
        chk("'Hello'[2]", "text[l]")
        chk("'Hello'[3]", "text[l]")
        chk("'Hello'[4]", "text[o]")
        chk("'Hello'[-1]", "rt_err:expr_text_subscript_index:5:-1")
        chk("'Hello'[5]", "rt_err:expr_text_subscript_index:5:5")
        chk("'Hello'['World']", "ct_err:expr_lookup_keytype:integer:text")

        chkEx("{ val x = 'Hello'; x[2] = 'X'; return x; }", "ct_err:expr_lookup_base:text")
    }

    @Test fun testCompareTo() {
        chk("'A'.compareTo('B')", "int[-1]")
        chk("'A'.compareTo('A')", "int[0]")
        chk("'B'.compareTo('A')", "int[1]")
        chk("'A'.compareTo('AA')", "int[-1]")
        chk("'ABCDE'.compareTo('ABCDE')", "int[0]")
        chk("'ABCDE'.compareTo('ABCDF')", "int[-1]")
        chk("'ABCDE'.compareTo('ABCDD')", "int[1]")
        chk("'ABCDE'.compareTo(123)", "ct_err:expr_call_argtypes:text.compareTo:integer")
    }

    @Test fun testStartsWith() {
        chk("'Hello'.startsWith('Hello')", "boolean[true]")
        chk("'Hello'.startsWith('Hell')", "boolean[true]")
        chk("'Hello'.startsWith('H')", "boolean[true]")
        chk("'Hello'.startsWith('')", "boolean[true]")
        chk("'Hello'.startsWith('hello')", "boolean[false]")
        chk("'Hello'.startsWith('Hellou')", "boolean[false]")
        chk("'Hello'.startsWith(123)", "ct_err:expr_call_argtypes:text.startsWith:integer")
    }

    @Test fun testEndsWith() {
        chk("'Hello'.endsWith('Hello')", "boolean[true]")
        chk("'Hello'.endsWith('ello')", "boolean[true]")
        chk("'Hello'.endsWith('o')", "boolean[true]")
        chk("'Hello'.endsWith('')", "boolean[true]")
        chk("'Hello'.endsWith('hello')", "boolean[false]")
        chk("'Hello'.endsWith('XHello')", "boolean[false]")
        chk("'Hello'.endsWith(123)", "ct_err:expr_call_argtypes:text.endsWith:integer")
    }

    @Test fun testContains() {
        chk("'Hello'.contains('Hello')", "boolean[true]")
        chk("'Hello'.contains('ello')", "boolean[true]")
        chk("'Hello'.contains('ll')", "boolean[true]")
        chk("'Hello'.contains('lo')", "boolean[true]")
        chk("'Hello'.contains('hello')", "boolean[false]")
        chk("'Hello'.contains('L')", "boolean[false]")
        chk("'Hello'.contains('Hello1')", "boolean[false]")
        chk("'Hello'.contains(123)", "ct_err:expr_call_argtypes:text.contains:integer")
    }

    @Test fun testIndexOf() {
        chk("'Hello'.indexOf('Hello')", "int[0]")
        chk("'Hello'.indexOf('ello')", "int[1]")
        chk("'Hello'.indexOf('ll')", "int[2]")
        chk("'Hello'.indexOf('l')", "int[2]")
        chk("'Hello'.indexOf('lo')", "int[3]")
        chk("'Hello'.indexOf('hello')", "int[-1]")
        chk("'Hello'.indexOf('L')", "int[-1]")
        chk("'Hello'.indexOf(123)", "ct_err:expr_call_argtypes:text.indexOf:integer")

        chk("'Hello World'.indexOf('o', 0)", "int[4]")
        chk("'Hello World'.indexOf('o', 4)", "int[4]")
        chk("'Hello World'.indexOf('o', 5)", "int[7]")
        chk("'Hello World'.indexOf('o', 6)", "int[7]")
        chk("'Hello World'.indexOf('o', 7)", "int[7]")
        chk("'Hello World'.indexOf('o', 8)", "int[-1]")
    }

    @Test fun testLastIndexOf() {
        chk("'Hello'.lastIndexOf('Hello')", "int[0]")
        chk("'Hello'.lastIndexOf('ello')", "int[1]")
        chk("'Hello'.lastIndexOf('ll')", "int[2]")
        chk("'Hello'.lastIndexOf('l')", "int[3]")
        chk("'Hello'.lastIndexOf('lo')", "int[3]")
        chk("'Hello'.lastIndexOf('hello')", "int[-1]")
        chk("'Hello'.lastIndexOf('L')", "int[-1]")
        chk("'Hello'.lastIndexOf(123)", "ct_err:expr_call_argtypes:text.lastIndexOf:integer")
        chk("'Hello Hello'.lastIndexOf('Hello')", "int[6]")

        chk("'Hello World'.lastIndexOf('o')", "int[7]")
        chk("'Hello World'.lastIndexOf('o', 10)", "int[7]")
        chk("'Hello World'.lastIndexOf('o', 8)", "int[7]")
        chk("'Hello World'.lastIndexOf('o', 7)", "int[7]")
        chk("'Hello World'.lastIndexOf('o', 6)", "int[4]")
        chk("'Hello World'.lastIndexOf('o', 5)", "int[4]")
        chk("'Hello World'.lastIndexOf('o', 4)", "int[4]")
        chk("'Hello World'.lastIndexOf('o', 3)", "int[-1]")
        chk("'Hello World'.lastIndexOf('o', -1)", "rt_err:fn_text_lastIndexOf_index:11:-1")
        chk("'Hello World'.lastIndexOf('o', 11)", "rt_err:fn_text_lastIndexOf_index:11:11")
    }

    @Test fun testSub() {
        chk("'Hello World'.sub(6)", "text[World]")
        chk("'Hello World'.sub(11)", "text[]")
        chk("'Hello World'.sub(0)", "text[Hello World]")
        chk("'Hello World'.sub(12)", "rt_err:fn_text_sub_range:11:12:11")
        chk("'Hello World'.sub(6, 9)", "text[Wor]")
        chk("'Hello World'.sub(6, 6)", "text[]")
        chk("'Hello World'.sub(6, 11)", "text[World]")
        chk("'Hello World'.sub(6, 12)", "rt_err:fn_text_sub_range:11:6:12")
        chk("'Hello World'.sub(6, 5)", "rt_err:fn_text_sub_range:11:6:5")
        chk("'Hello World'.sub(0, 11)", "text[Hello World]")
    }

    @Test fun testReplace() {
        chk("'Hello World'.replace('Hello', 'Bye')", "text[Bye World]")
        chk("'Hello World'.replace('o', '0')", "text[Hell0 W0rld]")
        chk("'Hello World'.replace('Bye', 'Tschus')", "text[Hello World]")
    }

    @Test fun testUpperCase() {
        chk("'Hello World'.upperCase()", "text[HELLO WORLD]")
        chk("'HELLO WORLD'.upperCase()", "text[HELLO WORLD]")
        chk("''.upperCase()", "text[]")
    }

    @Test fun testLowerCase() {
        chk("'Hello World'.lowerCase()", "text[hello world]")
        chk("'hello world'.lowerCase()", "text[hello world]")
        chk("'HELLO WORLD'.lowerCase()", "text[hello world]")
        chk("''.lowerCase()", "text[]")
    }

    @Test fun testSplit() {
        chk("'Hello World'.split(' ')", "list<text>[text[Hello],text[World]]")
        chk("'Hello World'.split(',')", "list<text>[text[Hello World]]")
        chk("'Hello,,World'.split(',')", "list<text>[text[Hello],text[],text[World]]")
        chk("',Hello,,World,'.split(',')", "list<text>[text[],text[Hello],text[],text[World],text[]]")
        chk("'Hello[A-Za-z]World'.split('[A-Za-z]')", "list<text>[text[Hello],text[World]]")
    }

    @Test fun testTrim() {
        chk("'  Hello   '", "text[  Hello   ]")
        chk("'  \t\tHello\t   '", "text[  \t\tHello\t   ]")
        chk("'  Hello   '.trim()", "text[Hello]")
        chk("'  \t\t   Hello   \t  '.trim()", "text[Hello]")
    }

    @Test fun testMatches() {
        chk("'Hello'.matches('Hello')", "boolean[true]")
        chk("'Hello'.matches('Bye')", "boolean[false]")
        chk("'Hello'.matches('[A-Za-z]+')", "boolean[true]")
        chk("'Hello'.matches('[0-9]+')", "boolean[false]")
    }

    @Test fun testEncode() {
        chk("'Hello'.encode()", "byte_array[48656c6c6f]")
        chk("'\u041f\u0440\u0438\u0432\u0435\u0442'.encode()", "byte_array[d09fd180d0b8d0b2d0b5d182]")
    }

    @Test fun testChatAt() {
        chk("'Hello'.chatAt(0)", "int[72]")
        chk("'Hello'.chatAt(1)", "int[101]")
        chk("'Hello'.chatAt(2)", "int[108]")
        chk("'Hello'.chatAt(3)", "int[108]")
        chk("'Hello'.chatAt(4)", "int[111]")
        chk("'Hello'.chatAt(5)", "rt_err:fn_text_charAt_index:5:5")
        chk("'Hello'.chatAt(-1)", "rt_err:fn_text_charAt_index:5:-1")
        chk("'Hello World'.chatAt(5)", "int[32]")
    }
}
