package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.parser
import net.postchain.rell.S_Grammar.getValue
import net.postchain.rell.S_Grammar.provideDelegate

class S_Attribute(val name: String, val type: String)
class S_Key(val attrNames: List<String>)
class S_Index(val attrNames: List<String>)

sealed class S_Expression
class S_VarRef(val varname: String): S_Expression()
class S_StringLiteral(val literal: String): S_Expression()
class S_BinOp(val op: String, val left: S_Expression, val right: S_Expression): S_Expression()
class S_AtExpr(val clasname: String, val where: List<S_BinOp>): S_Expression()

sealed class S_Statement
class S_BindStatement(val varname: String, val expr: S_Expression): S_Statement()
class S_CreateStatement(val classname: String, val attrs: List<S_Expression>): S_Statement()

sealed class S_RelClause
class S_AttributeClause(val attr: S_Attribute): S_RelClause()
class S_KeyClause(val attrs: List<S_Attribute>): S_RelClause()
class S_IndexClause(val attrs: List<S_Attribute>): S_RelClause()

sealed class S_Definition(val identifier: String)
open class S_RelDefinition (identifier: String, val attributes: List<S_Attribute>,
                       val keys: List<S_Key>, val indices: List<S_Index>): S_Definition(identifier)
class S_ClassDefinition (identifier: String, attributes: List<S_Attribute>,
                         keys: List<S_Key>, indices: List<S_Index>)
    : S_RelDefinition(identifier, attributes, keys, indices)

class S_OpDefinition(identifier: String, var args: List<S_Attribute>, val statements: List<S_Statement>): S_Definition(identifier)

class S_ModuleDefinition(val definitions: List<S_Definition>)

fun relFromClauses(identifier: String, clauses: List<S_RelClause>): S_RelDefinition {
    val attributes = mutableMapOf<String, String>()
    val keys = mutableListOf<S_Key>()
    val indices = mutableListOf<S_Index>()

    fun addAttr(a: S_Attribute) {
        if (a.name in attributes) {
            if (a.type != attributes[a.name])
                throw Exception("Conflicting attribute type")
        } else
            attributes[a.name] = a.type
    }

    for (clause in clauses) {
        when (clause) {
            is S_AttributeClause -> addAttr(clause.attr)
            is S_KeyClause -> {
                keys.add(S_Key(clause.attrs.map { it.name }))
                clause.attrs.forEach { addAttr(it) }
            }
            is S_IndexClause -> {
                indices.add(S_Index(clause.attrs.map { it.name }))
                clause.attrs.forEach { addAttr(it) }
            }
        }.let {} // ensure exhaustiveness
    }
    return S_RelDefinition(identifier,
            attributes.entries.map { S_Attribute(it.key, it.value) },
            keys, indices
    )
}

object S_Grammar : Grammar<S_ModuleDefinition>() {
    private val ws by token("\\s+", ignore = true)
    private val LPAR by token("\\(")
    private val RPAR by token("\\)")
    private val LCURL by token("\\{")
    private val RCURL by token("\\}")
    private val AT by token("@")
    private val COLON by token(":")
    private val SEMI by token(";")
    private val COMMA by token(",")
    private val EQLS by token("=")

    private val A_OPERATOR by token("[\\+\\-\\*/%]")
    private val C_OPERATOR by token("==|!=|[<>]=?")
    private val L_OPERATOR by token("or|and|not")

    val CREATE by token("create\\b")
    val SELECT by token("select\\b")
    val FROM by token("from\\b")
    val WHERE by token("where\\b")
    val CLASS by token("class\\b")
    val REL by token("rel\\b")
    val KEY by token("key\\b")
    val INDEX by token("index\\b")
    val OPER by token("operation\\b")
    val VAL by token("val\\b")
    val IDT by token("\\w+")
    private val NUMBER by token("\\d+")
    val STRINGLIT by token("\".*?\"")
    val id by (IDT) use { text }

    val relAutoAttribute by (id) map { S_Attribute(it, it) }
    val relNamedAttribute by (id * -COLON * id) map { (name, type) -> S_Attribute(name, type)}
    val relAttribute by (relNamedAttribute or relAutoAttribute)
    val relAttributes by separatedTerms(relAttribute, COMMA, false)

    val relKey by (-KEY * relAttributes) map { S_KeyClause(it) }
    val relIndex by (-INDEX * relAttributes) map { S_IndexClause(it) }

    val anyRelClause by (relKey or relIndex or (relAttribute map { S_AttributeClause(it)}))

    val relClauses by zeroOrMore(anyRelClause * -SEMI)

    val classDef by (-CLASS * id * -LCURL * relClauses * -RCURL ) map {
        (identifier, clauses) ->
        val rel = relFromClauses(identifier, clauses)
        S_ClassDefinition(identifier, rel.attributes, rel.keys, rel.indices)
    }

    val relDef by (-REL * id * -LCURL * relClauses * -RCURL ) map {
        (identifier, clauses) -> relFromClauses(identifier, clauses)
    }

    val selectExpr by (-SELECT * -LCURL * separatedTerms(id, COMMA) * -WHERE)
    val varExpr by (id map { S_VarRef(it) })
    var literalExpr = (STRINGLIT map { S_StringLiteral(it.text) })
    val operator = (A_OPERATOR or L_OPERATOR or C_OPERATOR) map { it.text }
    val binExpr : Parser<S_BinOp> = (-LPAR * parser(this::anyExpr) * operator * parser(this::anyExpr) * -RPAR) map {
        (lexpr, oper, rexpr) -> S_BinOp(oper, lexpr, rexpr)
    }

    val anyExpr by (binExpr or varExpr or literalExpr)
    val bindStatement = (-VAL * id * -EQLS * anyExpr * -SEMI ) map { (varname, expr) -> S_BindStatement(varname, expr)  }
    val whereExpr by (id * -EQLS * id) map {
        (what, where) -> S_BinOp("=", S_VarRef(what), S_VarRef(where))
    }
    val atExpr by (id * -AT * -LCURL * separatedTerms(whereExpr, COMMA) * -RCURL) map {
        (relname, where) -> S_AtExpr(relname, where)
    }
    val sqlExpr = (atExpr or varExpr)
    val createStatement = (-CREATE * id * -LPAR * separatedTerms(sqlExpr, COMMA, true) * -RPAR * -SEMI ) map {
        (classname, values) -> S_CreateStatement(classname, values)
    }
    val statement = (bindStatement or createStatement)

    val opDefinition by (-OPER * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * -LCURL * oneOrMore(statement) * -RCURL) map {
        (identifier, args, statements) -> S_OpDefinition(identifier, args, statements)
    }

    val anyDef by (classDef or relDef or opDefinition)

    override val rootParser by oneOrMore(anyDef) map { S_ModuleDefinition(it) }

    /*val term by
    (id use { Variable(text) }) or
            (-not * parser(this::term) map { (Not(it) }) or
            (-lpar * parser(this::rootParser) * -rpar)

    val andChain by leftAssociative(term, and) { l, _, r -> And(l, r) }
    override val rootParser by leftAssociative(andChain, or) { l, _, r -> Or(l, r) }*/
}

val ast = S_Grammar.parseToEnd("""
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
        issue_date: date;
        maturity_date: date;
        value: integer;
        shares: integer;
    }

    class project {
        index issuer;
        index pool;
        index framework;
        started: date;
        key name;
        description: text;
    }

    class project_report_category {
        index project;
        reporter;
        key project, name;
        created: date;
    }

    class document {
        key hash: byte_array;
        contents: byte_array;
    }

    class project_report {
        index project_report_category;
        date;
        reporter;
        document;
        value: text;
        metadata: text;
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

    operation add_bond (issuer_pubkey: signer, ISIN: text, pool_name: text, framework_name: text, issue_date: date, maturity_date: date, value: integer, shares: integer)
    {
        create bond (ISIN,
            issuer@{pubkey=issuer_pubkey},
            pool@{name=pool_name},
            framework@{name=framework_name},
            issue_date,
            maturity_date,
            value, shares);
    }


""")
