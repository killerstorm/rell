package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
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

        chkEx("{ val x = 'Hello'; x[2] = 'X'; return x; }", "ct_err:expr_unmodifiable:text")
    }

    @Test fun testCompareTo() {
        chk("'A'.compare_to('B')", "int[-1]")
        chk("'A'.compare_to('A')", "int[0]")
        chk("'B'.compare_to('A')", "int[1]")
        chk("'A'.compare_to('AA')", "int[-1]")
        chk("'ABCDE'.compare_to('ABCDE')", "int[0]")
        chk("'ABCDE'.compare_to('ABCDF')", "int[-1]")
        chk("'ABCDE'.compare_to('ABCDD')", "int[1]")
        chk("'ABCDE'.compare_to(123)", "ct_err:expr_call_argtypes:text.compare_to:integer")
    }

    @Test fun testStartsWith() {
        chk("'Hello'.starts_with('Hello')", "boolean[true]")
        chk("'Hello'.starts_with('Hell')", "boolean[true]")
        chk("'Hello'.starts_with('H')", "boolean[true]")
        chk("'Hello'.starts_with('')", "boolean[true]")
        chk("'Hello'.starts_with('hello')", "boolean[false]")
        chk("'Hello'.starts_with('Hellou')", "boolean[false]")
        chk("'Hello'.starts_with(123)", "ct_err:expr_call_argtypes:text.starts_with:integer")
    }

    @Test fun testEndsWith() {
        chk("'Hello'.ends_with('Hello')", "boolean[true]")
        chk("'Hello'.ends_with('ello')", "boolean[true]")
        chk("'Hello'.ends_with('o')", "boolean[true]")
        chk("'Hello'.ends_with('')", "boolean[true]")
        chk("'Hello'.ends_with('hello')", "boolean[false]")
        chk("'Hello'.ends_with('XHello')", "boolean[false]")
        chk("'Hello'.ends_with(123)", "ct_err:expr_call_argtypes:text.ends_with:integer")
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
        chk("'Hello'.index_of('Hello')", "int[0]")
        chk("'Hello'.index_of('ello')", "int[1]")
        chk("'Hello'.index_of('ll')", "int[2]")
        chk("'Hello'.index_of('l')", "int[2]")
        chk("'Hello'.index_of('lo')", "int[3]")
        chk("'Hello'.index_of('hello')", "int[-1]")
        chk("'Hello'.index_of('L')", "int[-1]")
        chk("'Hello'.index_of(123)", "ct_err:expr_call_argtypes:text.index_of:integer")

        chk("'Hello World'.index_of('o', 0)", "int[4]")
        chk("'Hello World'.index_of('o', 4)", "int[4]")
        chk("'Hello World'.index_of('o', 5)", "int[7]")
        chk("'Hello World'.index_of('o', 6)", "int[7]")
        chk("'Hello World'.index_of('o', 7)", "int[7]")
        chk("'Hello World'.index_of('o', 8)", "int[-1]")

        chkEx(": integer = 'Hello'.index_of('l');", "int[2]")
        chkEx(": integer = 'Hello'.index_of('l', 3);", "int[3]")
    }

    @Test fun testLastIndexOf() {
        chk("'Hello'.last_index_of('Hello')", "int[0]")
        chk("'Hello'.last_index_of('ello')", "int[1]")
        chk("'Hello'.last_index_of('ll')", "int[2]")
        chk("'Hello'.last_index_of('l')", "int[3]")
        chk("'Hello'.last_index_of('lo')", "int[3]")
        chk("'Hello'.last_index_of('hello')", "int[-1]")
        chk("'Hello'.last_index_of('L')", "int[-1]")
        chk("'Hello'.last_index_of(123)", "ct_err:expr_call_argtypes:text.last_index_of:integer")
        chk("'Hello Hello'.last_index_of('Hello')", "int[6]")

        chk("'Hello World'.last_index_of('o')", "int[7]")
        chk("'Hello World'.last_index_of('o', 10)", "int[7]")
        chk("'Hello World'.last_index_of('o', 8)", "int[7]")
        chk("'Hello World'.last_index_of('o', 7)", "int[7]")
        chk("'Hello World'.last_index_of('o', 6)", "int[4]")
        chk("'Hello World'.last_index_of('o', 5)", "int[4]")
        chk("'Hello World'.last_index_of('o', 4)", "int[4]")
        chk("'Hello World'.last_index_of('o', 3)", "int[-1]")
        chk("'Hello World'.last_index_of('o', -1)", "rt_err:fn:text.last_index_of:index:11:-1")
        chk("'Hello World'.last_index_of('o', 11)", "rt_err:fn:text.last_index_of:index:11:11")

        chkEx(": integer = 'Hello World'.last_index_of('o');", "int[7]")
        chkEx(": integer = 'Hello World'.last_index_of('o', 6);", "int[4]")
    }

    @Test fun testSub() {
        chk("'Hello World'.sub(6)", "text[World]")
        chk("'Hello World'.sub(11)", "text[]")
        chk("'Hello World'.sub(0)", "text[Hello World]")
        chk("'Hello World'.sub(12)", "rt_err:fn:text.sub:range:11:12:11")
        chk("'Hello World'.sub(6, 9)", "text[Wor]")
        chk("'Hello World'.sub(6, 6)", "text[]")
        chk("'Hello World'.sub(6, 11)", "text[World]")
        chk("'Hello World'.sub(6, 12)", "rt_err:fn:text.sub:range:11:6:12")
        chk("'Hello World'.sub(6, 5)", "rt_err:fn:text.sub:range:11:6:5")
        chk("'Hello World'.sub(0, 11)", "text[Hello World]")
    }

    @Test fun testReplace() {
        chk("'Hello World'.replace('Hello', 'Bye')", "text[Bye World]")
        chk("'Hello World'.replace('o', '0')", "text[Hell0 W0rld]")
        chk("'Hello World'.replace('Bye', 'Tschus')", "text[Hello World]")
    }

    @Test fun testUpperCase() {
        chk("'Hello World'.upper_case()", "text[HELLO WORLD]")
        chk("'HELLO WORLD'.upper_case()", "text[HELLO WORLD]")
        chk("''.upper_case()", "text[]")
    }

    @Test fun testLowerCase() {
        chk("'Hello World'.lower_case()", "text[hello world]")
        chk("'hello world'.lower_case()", "text[hello world]")
        chk("'HELLO WORLD'.lower_case()", "text[hello world]")
        chk("''.lower_case()", "text[]")
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
        chk("'  \t\tHello\t   '", """text[  \t\tHello\t   ]""")
        chk("'  Hello   '.trim()", "text[Hello]")
        chk("'  \t\t   Hello   \t  '.trim()", "text[Hello]")
    }

    @Test fun testMatches() {
        chk("'Hello'.matches('Hello')", "boolean[true]")
        chk("'Hello'.matches('Bye')", "boolean[false]")
        chk("'Hello'.matches('[A-Za-z]+')", "boolean[true]")
        chk("'Hello'.matches('[0-9]+')", "boolean[false]")
    }

    @Test fun testCharAt() {
        chk("'Hello'.char_at(0)", "int[72]")
        chk("'Hello'.char_at(1)", "int[101]")
        chk("'Hello'.char_at(2)", "int[108]")
        chk("'Hello'.char_at(3)", "int[108]")
        chk("'Hello'.char_at(4)", "int[111]")
        chk("'Hello'.char_at(5)", "rt_err:fn:text.char_at:index:5:5")
        chk("'Hello'.char_at(-1)", "rt_err:fn:text.char_at:index:5:-1")
        chk("'Hello World'.char_at(5)", "int[32]")
    }

    @Test fun testFormat() {
        chk("'%s'.format(123)", "text[123]")
        chk("'%s'.format('Hello')", "text[Hello]")

        chk("'%d'.format(123)", "text[123]")
        chk("'%5d'.format(123)", "text[  123]")
        chk("'%-5d'.format(123)", "text[123  ]")
        chk("'%05d'.format(123)", "text[00123]")
        chk("'%5d'.format(1234567)", "text[1234567]")
        chk("'%,d'.format(1234567)", "text[1,234,567]")
        chk("'%+d'.format(123)", "text[+123]")
        chk("'%+d'.format(-123)", "text[-123]")

        chk("'%s'.format()", "text[%s]")
        chk("'%d'.format('Hello')", "text[%d]")
    }

    @Test fun testToFromBytes() {
        chk("''.to_bytes()", "byte_array[]")
        chk("text.from_bytes(x'')", "text[]")
        chk("text.from_bytes(x'', false)", "text[]")
        chk("text.from_bytes(x'', true)", "text[]")

        chk("'Hello'.to_bytes()", "byte_array[48656c6c6f]")
        chk("text.from_bytes(x'48656c6c6f')", "text[Hello]")
        chk("text.from_bytes(x'48656c6c6f', false)", "text[Hello]")
        chk("text.from_bytes(x'48656c6c6f', true)", "text[Hello]")

        chk("'\u041f\u0440\u0438\u0432\u0435\u0442'.to_bytes()", "byte_array[d09fd180d0b8d0b2d0b5d182]")
        chk("text.from_bytes(x'd09fd180d0b8d0b2d0b5d182')", """text[\u041f\u0440\u0438\u0432\u0435\u0442]""")
        chk("text.from_bytes(x'd09fd180d0b8d0b2d0b5d182', false)", """text[\u041f\u0440\u0438\u0432\u0435\u0442]""")
        chk("text.from_bytes(x'd09fd180d0b8d0b2d0b5d182', true)", """text[\u041f\u0440\u0438\u0432\u0435\u0442]""")

        // See https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-test.txt
        chk("text.from_bytes(x'80')", "rt_err:fn:text.from_bytes")
        chk("text.from_bytes(x'bf')", "rt_err:fn:text.from_bytes")
        chk("text.from_bytes(x'80', false)", "rt_err:fn:text.from_bytes")
        chk("text.from_bytes(x'bf', false)", "rt_err:fn:text.from_bytes")
        chk("text.from_bytes(x'80', true)", "text[\\ufffd]")
        chk("text.from_bytes(x'bf', true)", "text[\\ufffd]")
    }
}
