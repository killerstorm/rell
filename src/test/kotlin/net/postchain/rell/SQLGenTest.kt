package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import org.junit.Test
import kotlin.test.assertEquals

class SQLGenTest {
    @Test
    fun test1() {
        val ast = S_Grammar.parseToEnd(
                """
    class issuer {
        pubkey;
        key name;
    }

    class reporter {
        index issuer;
        key name;
        index pubkey;
    }

    class framework {
        index issuer;
        key name;
    }

    class pool {  index issuer; key name; }

    class bond {
        key ISIN: text;
        index issuer;
        index pool;
        index framework;
        issue_date: timestamp;
        maturity_date: timestamp;
        value: integer;
        shares: integer;
    }

    class project {
        index issuer;
        index pool;
        index framework;
        started: timestamp;
        key name;
        description: text;
    }

    class project_report_category {
        index project;
        reporter;
        key project, name;
        created: timestamp;
    }

    class document {
        key hash: byte_array;
        contents: byte_array;
    }

    class project_report {
        index project_report_category;
        timestamp;
        reporter;
        document;
        value: text;
        metadata: text;
    }

    operation add_issuer (admin_pubkey: signer, pubkey, name) {
        require((admin_pubkey == x"deadbeaf"), "Admin pubkey doesnt match");
        create issuer (pubkey, name);
    }

    operation add_reporter (issuer_pubkey: signer, name, pubkey) {
        create reporter (issuer@{pubkey=issuer_pubkey}, name, pubkey);
    }

    operation add_framework (issuer_pubkey: signer, name) {
        create framework (issuer@{pubkey=issuer_pubkey}, name);
    }

    operation add_pool (issuer_pubkey: signer, name) {
        create pool (issuer@{pubkey=issuer_pubkey}, name);
    }

    operation add_bond (issuer_pubkey: signer, ISIN: text,
                        pool_name: text, framework_name: text, issue_date: timestamp,
                        maturity_date: timestamp, value: integer, shares: integer)
    {
        create bond (
            ISIN = ISIN,
            issuer = issuer@{pubkey=issuer_pubkey},
            pool@{name=pool_name},
            framework@{name=framework_name},
            issue_date,
            maturity_date,
            value, shares
        );
    }

"""
        )

        val model = makeModule(ast)
        println(gensql(model))
    }

    @Test
    fun test2() {

        val ast2 = S_Grammar.parseToEnd("""
     class user {
        key pubkey;
        index username: text;
        firstName: text;
        lastName: text;
        email: text;

    }
    class location {
        index longitude: integer;
        index latitude: integer;
        key name: text;
        creator: user;
    }
    class company {
        name: text;
        key vat: integer;
        index location;
    }
    class employee {
        index pubkey;
        index company;
        start_date: timestamp;
    }
    class ooorder {
        user;
        company;
        description: text;
        delivery_location: location;
    }
    operation add_user (admin_pubkey: signer, pubkey, username: text, firstName: text, lastName: text, email: text) {
        require((admin_pubkey == x"026a7825fdfdd00d87e8d590d1cdc73f563c0d8eb9ca5e2f6239b71b5e014a37f3"), "Admin pubkey does not match");
        create user (pubkey, username, firstName, lastName, email);
    }
    operation add_location (user_pubkey: signer, longitude: integer, latitude: integer, name) {
        create location (
            longitude,
            latitude,
            name,
            creator@{pubkey=user_pubkey}
        );
    }
    """
        )


    }

}
