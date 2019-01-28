package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test

class UpdateDeleteExprTest: BaseRellTest() {
    override fun classDefs() = listOf(
            "class user { name: text; mutable score: integer; }"
    )

    override fun objInserts() = listOf(
            insUser(101, "Bob", 333),
            insUser(102, "Alice", 555)
    )

    @Test fun testUpdateObject() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update u ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Alice' }; update u ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,678)")
    }

    @Test fun testDeleteObject() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; delete u;")
        chkData("user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Alice' }; delete u;")
        chkData()
    }

    @Test fun testUpdateNullable() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @? { .name == 'Bob' }; update u ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,555)")

        execOp("val u = user @? { .name == 'Trudy' }; update u ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,555)")
    }

    @Test fun testDeleteNullable() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @? { .name == 'Bob' }; delete u;")
        chkData("user(102,Alice,555)")

        execOp("val u = user @? { .name == 'Trudy' }; delete u;")
        chkData("user(102,Alice,555)")
    }

    @Test fun testUpdateCollection() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @* {}; update u ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,678)")

        execOp("val u = user @* { .name == 'Bob' }; update u ( score += 123 );")
        chkData("user(101,Bob,579)", "user(102,Alice,678)")

        execOp("val u = user @* { .name == 'Alice' }; update u ( score += 123 );")
        chkData("user(101,Bob,579)", "user(102,Alice,801)")

        execOp("val u = user @* { .name == 'Trudy' }; update u ( score += 123 );")
        chkData("user(101,Bob,579)", "user(102,Alice,801)")
    }

    @Test fun testDeleteCollection() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @* { .name == 'Trudy' }; delete u;")
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @* { .name == 'Bob' }; delete u;")
        chkData("user(102,Alice,555)")

        execOp("val u = user @* {}; delete u;")
        chkData()
    }

    @Test fun testUpdateCollectionDuplicate() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update [u, u, u] ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,555)")

        execOp("val u = user @* {}; update [u[0], u[1], u[0], u[1], u[0], u[1]] ( score += 123 );")
        chkData("user(101,Bob,579)", "user(102,Alice,678)")
    }

    @Test fun testDeleteCollectionDuplicate() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; delete [u, u, u];")
        chkData("user(102,Alice,555)")
    }

    @Test fun testUpdateParentheses() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("update (user @ { .name == 'Bob' }) ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,555)")

        execOp("update (user @? { .name == 'Alice' }) ( score += 123 );")
        chkData("user(101,Bob,456)", "user(102,Alice,678)")

        execOp("update (user @* {}) ( score += 123 );")
        chkData("user(101,Bob,579)", "user(102,Alice,801)")
    }

    @Test fun testDeleteParentheses() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("delete (user @ { .name == 'Bob' });")
        chkData("user(102,Alice,555)")

        execOp("delete (user @? { .name == 'Trudy' });")
        chkData("user(102,Alice,555)")

        execOp("delete (user @* {});")
        chkData()
    }

    @Test fun testUpdateWhatSimple() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update u ( .score = 444 );")
        chkData("user(101,Bob,444)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update u ( score = 666 );")
        chkData("user(101,Bob,666)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update (u) ( score = 555 );")
        chkData("user(101,Bob,555)", "user(102,Alice,555)")
    }

    @Test fun testUpdateWhatAttributes() {
        chkData("user(101,Bob,333)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update u ( score = 500 );")
        chkData("user(101,Bob,500)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update u ( score = (user.score * 3 + 500) );")
        chkData("user(101,Bob,2000)", "user(102,Alice,555)")

        execOp("val u = user @ { .name == 'Bob' }; update u ( score = (u.score * 3 + 500) );")
        chkData("user(101,Bob,6500)", "user(102,Alice,555)")

        chkOp("val u = user @* { .name == 'Bob' }; update u ( score = (u.score * 3 + 500) );",
                "ct_err:unknown_member:list<user>:score")
    }

    @Test fun testWrongType() {
        chkOp("update 123 ( .x = 0 );", "ct_err:stmt_update_expr_type:integer")
        chkOp("update 'Hello' ( .x = 0 );", "ct_err:stmt_update_expr_type:text")
        chkOp("update true ( .x = 0 );", "ct_err:stmt_update_expr_type:boolean")
        chkOp("update null ( .x = 0 );", "ct_err:stmt_update_expr_type:null")
        chkOp("update list<text>() ( .x = 0 );", "ct_err:stmt_update_expr_type:list<text>")
        chkOp("val v: integer? = 123; update v ( .x = 0 );", "ct_err:stmt_update_expr_type:integer?")
        chkOp("val v: list<user>? = null; update v ( .x = 0 );", "ct_err:stmt_update_expr_type:list<user>?")
        chkOp("val v = list<user?>(); update v ( .x = 0 );", "ct_err:stmt_update_expr_type:list<user?>")

        chkOp("delete 123;", "ct_err:stmt_update_expr_type:integer")
        chkOp("delete 'Hello';", "ct_err:stmt_update_expr_type:text")
        chkOp("delete true;", "ct_err:stmt_update_expr_type:boolean")
        chkOp("delete null;", "ct_err:stmt_update_expr_type:null")
        chkOp("delete list<text>();", "ct_err:stmt_update_expr_type:list<text>")
        chkOp("val v: integer? = 123; delete v;", "ct_err:stmt_update_expr_type:integer?")
        chkOp("val v: list<user>? = null; delete v;", "ct_err:stmt_update_expr_type:list<user>?")
        chkOp("val v = list<user?>(); delete v;", "ct_err:stmt_update_expr_type:list<user?>")
    }

    @Test fun testError() {
        chkOp("update user @* {} ( .name ) ( .score += 123 );", "ct_err:stmt_update_expr_type:list<text>")
        chkOp("update (user @* {} ( .name )) ( .score += 123 );", "ct_err:stmt_update_expr_type:list<text>")
        chkOp("delete user @* {} ( .name );", "ct_err:stmt_update_expr_type:list<text>")
        chkOp("delete (user @* {} ( .name ));", "ct_err:stmt_update_expr_type:list<text>")
    }

    @Test fun testUpdatePortions() {
        tst.inserts = listOf()
        tst.rtSqlUpdatePortionSize = 5
        val n = 33

        execOp("for (i in range($n)) { create user(name = 'user_' + i, score = i * i); }")
        chk("(user@*{}).size()", "int[$n]")

        execOp("val u = user @* {}; update u (score += 100);")

        val code = """{
            val n = $n;
            val us = user @* {} ( user, .name, .score );
            if (us.size() != n) return 'size:' + us.size();
            for (i in range(n)) {
                val exp = i * i + 100;
                val u = us[i];
                if (u.score != exp) return 'score:' + u;
            }
            return 'OK';
        }""".trimIndent()
        chkEx(code, "text[OK]")
    }

    @Test fun testDeletePortions() {
        tst.inserts = listOf()
        tst.rtSqlUpdatePortionSize = 5
        val n = 33

        execOp("for (i in range($n)) { create user(name = 'user_' + i, score = i * i); }")
        chk("(user@*{}).size()", "int[$n]")

        execOp("val u = user @* {}; delete u;")
        chkData()
    }

    private fun insUser(id: Int, name: String, score: Int): String =
            SqlTestUtils.mkins("user", "name,score", "$id,'$name',$score")
}
