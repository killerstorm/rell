package net.postchain.rell.lib

import net.postchain.rell.BaseRellTest
import org.junit.Test

class LibListTest: BaseRellTest(false) {
    @Test fun testLiteral() {
        chk("[]", "ct_err:expr_list_empty")
        chk("[123]", "list<integer>[int[123]]")
        chk("[123, 456, 789]", "list<integer>[int[123],int[456],int[789]]")
        chk("['Hello', 'World']", "list<text>[text[Hello],text[World]]")
        chk("[123, 'Hello']", "ct_err:expr_list_itemtype:integer:text")
    }

    @Test fun testConstructor() {
        chk("list()", "ct_err:expr_list_notype")
        chk("list<integer>()", "list<integer>[]")
        chk("list([])", "ct_err:expr_list_empty")
        chk("list<integer>([])", "ct_err:expr_list_empty")
        chk("list([123])", "list<integer>[int[123]]")
        chk("list([123, 456, 789])", "list<integer>[int[123],int[456],int[789]]")
        chk("list(set<integer>())", "list<integer>[]")
        chk("list(set([123, 456, 789]))", "list<integer>[int[123],int[456],int[789]]")
        chk("list(set<text>())", "list<text>[]")
        chk("list(set(['Hello']))", "list<text>[text[Hello]]")
        chk("list<integer>(set<text>())", "ct_err:expr_list_typemiss:integer:text")
        chk("list<integer>(set(['Hello']))", "ct_err:expr_list_typemiss:integer:text")
    }

    @Test fun testEmpty() {
        chk("list<integer>().empty()", "boolean[true]")
        chk("list<integer>([1]).empty()", "boolean[false]")
        chk("list<integer>([1, 2, 3, 4, 5]).empty()", "boolean[false]")
    }

    @Test fun testSize() {
        chk("list<integer>().size()", "int[0]")
        chk("list([1]).size()", "int[1]")
        chk("list([1, 2, 3, 4, 5]).size()", "int[5]")

        chk("list<integer>().len()", "int[0]")
        chk("list([1]).len()", "int[1]")
        chk("list([1, 2, 3, 4, 5]).len()", "int[5]")
    }

    @Test fun testGet() {
        chk("list([1, 2, 3, 4, 5]).calculate(0)", "int[1]")
        chk("list([1, 2, 3, 4, 5]).calculate(4)", "int[5]")
        chk("list([1, 2, 3, 4, 5]).calculate(-1)", "rt_err:fn_list_get_index:5:-1")
        chk("list([1, 2, 3, 4, 5]).calculate(5)", "rt_err:fn_list_get_index:5:5")

        chk("[1, 2, 3, 4, 5].calculate(0)", "int[1]")
        chk("[1, 2, 3, 4, 5].calculate(4)", "int[5]")
        chk("[1, 2, 3, 4, 5].calculate(-1)", "rt_err:fn_list_get_index:5:-1")
        chk("[1, 2, 3, 4, 5].calculate(5)", "rt_err:fn_list_get_index:5:5")
    }

    @Test fun testSubscriptGet() {
        chk("list([1, 2, 3, 4, 5])[0]", "int[1]")
        chk("list([1, 2, 3, 4, 5])[4]", "int[5]")
        chk("list([1, 2, 3, 4, 5])[-1]", "rt_err:expr_list_lookup_index:5:-1")
        chk("list([1, 2, 3, 4, 5])[5]", "rt_err:expr_list_lookup_index:5:5")

        chk("[1, 2, 3, 4, 5][0]", "int[1]")
        chk("[1, 2, 3, 4, 5][4]", "int[5]")
        chk("[1, 2, 3, 4, 5][-1]", "rt_err:expr_list_lookup_index:5:-1")
        chk("[1, 2, 3, 4, 5][5]", "rt_err:expr_list_lookup_index:5:5")

        chkEx("{ val x: list<integer>? = [1,2,3]; return x[1]; }", "ct_err:expr_lookup_null")
        chkEx("{ val x: list<integer>? = [1,2,3]; return x!![1]; }", "int[2]")
    }

    @Test fun testEquals() {
        chk("[1, 2, 3] == [1, 2, 3]", "boolean[true]")
        chk("[1, 2, 3] == [4, 5, 6]", "boolean[false]")
        chk("[1, 2, 3] == [1, 2]", "boolean[false]")
        chk("[1, 2, 3] == [1, 2, 3, 4]", "boolean[false]")
        chk("[1, 2, 3] == list<integer>()", "boolean[false]")
        chk("[1, 2, 3] == list<text>()", "ct_err:binop_operand_type:==:list<integer>:list<text>")
        chk("[1, 2, 3] == ['Hello']", "ct_err:binop_operand_type:==:list<integer>:list<text>")
        chk("[1, 2, 3] == set<integer>()", "ct_err:binop_operand_type:==:list<integer>:set<integer>")
        chk("[1, 2, 3] == set([1, 2, 3])", "ct_err:binop_operand_type:==:list<integer>:set<integer>")
    }

    @Test fun testContains() {
        chk("[1, 2, 3].contains(1)", "boolean[true]")
        chk("[1, 2, 3].contains(3)", "boolean[true]")
        chk("[1, 2, 3].contains(5)", "boolean[false]")
        chk("[1, 2, 3].contains('Hello')", "ct_err:expr_call_argtypes:list<integer>.contains:text")
    }

    @Test fun testIn() {
        chk("1 in [1, 2, 3]", "boolean[true]")
        chk("3 in [1, 2, 3]", "boolean[true]")
        chk("5 in [1, 2, 3]", "boolean[false]")
        chk("'Hello' in [1, 2, 3]", "ct_err:binop_operand_type:in:text:list<integer>")
    }

    @Test fun testContainsAll() {
        chk("list<integer>().containsAll(list<integer>())", "boolean[true]")
        chk("list<integer>().containsAll(set<integer>())", "boolean[true]")
        chk("list<integer>().containsAll(list<text>())", "ct_err:expr_call_argtypes:list<integer>.containsAll:list<text>")
        chk("[1, 2, 3].containsAll([1, 2, 3])", "boolean[true]")
        chk("[1, 2, 3].containsAll(set([1, 2, 3]))", "boolean[true]")
        chk("[1, 2, 3].containsAll([0])", "boolean[false]")
        chk("[1, 2, 3].containsAll([2])", "boolean[true]")
        chk("[1, 2, 3].containsAll(set([0]))", "boolean[false]")
        chk("[1, 2, 3].containsAll(set([2]))", "boolean[true]")
        chk("[1, 2, 3].containsAll([1, 3])", "boolean[true]")
        chk("[1, 2, 3].containsAll([0, 1])", "boolean[false]")
        chk("[1, 2, 3].containsAll([1, 2, 3, 4])", "boolean[false]")
    }

    @Test fun testIndexOf() {
        chk("[1, 2, 3].indexOf(1)", "int[0]")
        chk("[1, 2, 3].indexOf(3)", "int[2]")
        chk("[1, 2, 3].indexOf(5)", "int[-1]")
        chk("[1, 2, 3].indexOf('Hello')", "ct_err:expr_call_argtypes:list<integer>.indexOf:text")
    }

    @Test fun testSub() {
        chk("list<integer>().sub(0)", "list<integer>[]")
        chk("list<integer>().sub(0, 0)", "list<integer>[]")
        chk("list<integer>().sub(-1)", "rt_err:fn_list_sub_args:0:-1:0")
        chk("list<integer>().sub(-1, 0)", "rt_err:fn_list_sub_args:0:-1:0")
        chk("list<integer>().sub(0, 1)", "rt_err:fn_list_sub_args:0:0:1")
        chk("list<integer>().sub(0, 0, 0)", "ct_err:expr_call_argtypes:list<integer>.sub:integer,integer,integer")
        chk("list<integer>([1, 2, 3]).sub(-1)", "rt_err:fn_list_sub_args:3:-1:3")
        chk("list<integer>([1, 2, 3]).sub(0)", "list<integer>[int[1],int[2],int[3]]")
        chk("list<integer>([1, 2, 3]).sub(1)", "list<integer>[int[2],int[3]]")
        chk("list<integer>([1, 2, 3]).sub(2)", "list<integer>[int[3]]")
        chk("list<integer>([1, 2, 3]).sub(3)", "list<integer>[]")
        chk("list<integer>([1, 2, 3]).sub(4)", "rt_err:fn_list_sub_args:3:4:3")
        chk("list<integer>([1, 2, 3]).sub(0, 1)", "list<integer>[int[1]]")
        chk("list<integer>([1, 2, 3]).sub(1, 2)", "list<integer>[int[2]]")
        chk("list<integer>([1, 2, 3]).sub(2, 3)", "list<integer>[int[3]]")
        chk("list<integer>([1, 2, 3]).sub(0, 2)", "list<integer>[int[1],int[2]]")
        chk("list<integer>([1, 2, 3]).sub(1, 3)", "list<integer>[int[2],int[3]]")
        chk("list<integer>([1, 2, 3]).sub(0, 3)", "list<integer>[int[1],int[2],int[3]]")
        chk("list<integer>([1, 2, 3]).sub(0, 4)", "rt_err:fn_list_sub_args:3:0:4")
        chk("list<integer>([1, 2, 3]).sub(2, 2)", "list<integer>[]")
        chk("list<integer>([1, 2, 3]).sub(2, 1)", "rt_err:fn_list_sub_args:3:2:1")
    }

    @Test fun testStr() {
        chk("list<integer>().str()", "text[[]]")
        chk("list<integer>([1]).str()", "text[[1]]")
        chk("list<integer>([1, 2, 3, 4, 5]).str()", "text[[1, 2, 3, 4, 5]]")
    }

    @Test fun testAdd() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3];"
        chkEx("{ $init val r = x.add(4); return r+' '+x; }", "true [1, 2, 3, 4]")
        chkEx("{ $init val r = x.add(0, 4); return r+' '+x; }", "true [4, 1, 2, 3]")
        chkEx("{ $init val r = x.add(3, 4); return r+' '+x; }", "true [1, 2, 3, 4]")
        chkEx("{ $init val r = x.add(2); return r+' '+x; }", "true [1, 2, 3, 2]")
        chkEx("{ $init val r = x.add(-1, 4); return r+' '+x; }", "rt_err:fn_list_add_index:3:-1")
        chkEx("{ $init val r = x.add(4, 4); return r+' '+x; }", "rt_err:fn_list_add_index:3:4")
        chkEx("{ $init val r = x.add('Hello'); return r+' '+x; }", "ct_err:expr_call_argtypes:list<integer>.add:text")
    }

    @Test fun testAddAll() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3];"
        chkEx("{ $init val r = x.addAll(list<integer>()); return r+' '+x; }", "false [1, 2, 3]")
        chkEx("{ $init val r = x.addAll([4, 5, 6]); return r+' '+x; }", "true [1, 2, 3, 4, 5, 6]")
        chkEx("{ $init val r = x.addAll([1, 2, 3]); return r+' '+x; }", "true [1, 2, 3, 1, 2, 3]")
        chkEx("{ $init val r = x.addAll(['Hello']); return r+' '+x; }", "ct_err:expr_call_argtypes:list<integer>.addAll:list<text>")
        chkEx("{ $init val r = x.addAll(0, [4, 5, 6]); return r+' '+x; }", "true [4, 5, 6, 1, 2, 3]")
        chkEx("{ $init val r = x.addAll(3, [4, 5, 6]); return r+' '+x; }", "true [1, 2, 3, 4, 5, 6]")
        chkEx("{ $init val r = x.addAll(-1, [4, 5, 6]); return r+' '+x; }", "rt_err:fn_list_addAll_index:3:-1")
        chkEx("{ $init val r = x.addAll(4, [4, 5, 6]); return r+' '+x; }", "rt_err:fn_list_addAll_index:3:4")
        chkEx("{ $init val r = x.addAll(set([4, 5, 6])); return r+' '+x; }", "true [1, 2, 3, 4, 5, 6]")
        chkEx("{ $init val r = x.addAll(0, set([4, 5, 6])); return r+' '+x; }", "true [4, 5, 6, 1, 2, 3]")
    }

    @Test fun testRemove() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3, 2, 3, 4];"
        chkEx("{ $init val r = x.remove(1); return ''+r+' '+x; }", "true [2, 3, 2, 3, 4]")
        chkEx("{ $init val r = x.remove(2); return ''+r+' '+x; }", "true [1, 3, 2, 3, 4]")
        chkEx("{ $init val r = x.remove(3); return ''+r+' '+x; }", "true [1, 2, 2, 3, 4]")
        chkEx("{ $init val r = x.remove(0); return ''+r+' '+x; }", "false [1, 2, 3, 2, 3, 4]")
        chkEx("{ $init val r = x.remove('Hello'); return ''+r+' '+x; }", "ct_err:expr_call_argtypes:list<integer>.remove:text")
    }

    @Test fun testRemoveAll() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3, 2, 3, 4];"
        chkEx("{ $init val r = x.removeAll(set([0])); return ''+r+' '+x; }", "false [1, 2, 3, 2, 3, 4]")
        chkEx("{ $init val r = x.removeAll(set([1])); return ''+r+' '+x; }", "true [2, 3, 2, 3, 4]")
        chkEx("{ $init val r = x.removeAll(set([2])); return ''+r+' '+x; }", "true [1, 3, 3, 4]")
        chkEx("{ $init val r = x.removeAll(set([3])); return ''+r+' '+x; }", "true [1, 2, 2, 4]")
        chkEx("{ $init val r = x.removeAll([0]); return ''+r+' '+x; }", "false [1, 2, 3, 2, 3, 4]")
        chkEx("{ $init val r = x.removeAll([2]); return ''+r+' '+x; }", "true [1, 3, 3, 4]")
        chkEx("{ $init val r = x.removeAll([1, 2, 3]); return ''+r+' '+x; }", "true [4]")
        chkEx("{ $init val r = x.removeAll([1, 3]); return ''+r+' '+x; }", "true [2, 2, 4]")
        chkEx("{ $init val r = x.removeAll(['Hello']); return ''+r+' '+x; }", "ct_err:expr_call_argtypes:list<integer>.removeAll:list<text>")
        chkEx("{ $init val r = x.removeAll(set(['Hello'])); return ''+r+' '+x; }", "ct_err:expr_call_argtypes:list<integer>.removeAll:set<text>")
    }

    @Test fun testRemoveAt() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3];"
        chkEx("{ $init val r = x.removeAt(0); return ''+r+' '+x; }", "1 [2, 3]")
        chkEx("{ $init val r = x.removeAt(1); return ''+r+' '+x; }", "2 [1, 3]")
        chkEx("{ $init val r = x.removeAt(2); return ''+r+' '+x; }", "3 [1, 2]")
        chkEx("{ $init val r = x.removeAt(-1); return ''+r+' '+x; }", "rt_err:fn_list_removeAt_index:3:-1")
        chkEx("{ $init val r = x.removeAt(3); return ''+r+' '+x; }", "rt_err:fn_list_removeAt_index:3:3")
        chkEx("{ $init val r = x.removeAt('Hello'); return ''+r+' '+x; }", "ct_err:expr_call_argtypes:list<integer>.removeAt:text")
    }

    @Test fun testClear() {
        chkEx("{ val x = [1, 2, 3]; x.clear(); return x; }", "list<integer>[]")
    }

    @Test fun testSet() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3];"
        chkEx("{ $init val r = x._set(0, 5); return ''+r+' '+x; }", "1 [5, 2, 3]")
        chkEx("{ $init val r = x._set(1, 5); return ''+r+' '+x; }", "2 [1, 5, 3]")
        chkEx("{ $init val r = x._set(2, 5); return ''+r+' '+x; }", "3 [1, 2, 5]")
        chkEx("{ $init val r = x._set(-1, 5); return ''+r+' '+x; }", "rt_err:fn_list_set_index:3:-1")
        chkEx("{ $init val r = x._set(3, 5); return ''+r+' '+x; }", "rt_err:fn_list_set_index:3:3")
    }

    @Test fun testSubscriptSet() {
        tst.strictToString = false
        val init = "val x = [1, 2, 3];"
        chkEx("{ $init x[0] = 5; return x; }", "[5, 2, 3]")
        chkEx("{ $init x[1] = 5; return x; }", "[1, 5, 3]")
        chkEx("{ $init x[2] = 5; return x; }", "[1, 2, 5]")
        chkEx("{ $init x[-1] = 5; return x; }", "rt_err:expr_list_lookup_index:3:-1")
        chkEx("{ $init x[3] = 5; return x; }", "rt_err:expr_list_lookup_index:3:3")

        chkEx("{ val x: list<integer>? = [1, 2, 3]; x[1] = 5; return x; }", "ct_err:expr_lookup_base:list<integer>?")
        chkEx("{ val x: list<integer>? = [1, 2, 3]; x!![1] = 5; return x; }", "[1, 5, 3]")
    }

    @Test fun testFor() {
        tst.execOp("for (i in list([123, 456, 789])) print(i);")
        tst.chkStdout("123", "456", "789")
    }
}
