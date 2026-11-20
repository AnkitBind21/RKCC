package compiler.bool;

import compiler.bool.Ast.*;
import compiler.bool.BoolCompileException.Phase;
import compiler.bool.Token.Type;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2: syntax validation and expression parsing (recursive descent, Java precedence).
 *
 * <pre>
 * program    := statement* EOF
 * statement  := 'boolean' IDENT ('=' expr)? ';'
 *             | IDENT ('=' | '&=' | '|=' | '^=') expr ';'
 * expr       := logicalOr
 * logicalOr  := logicalAnd ('||' logicalAnd)*
 * logicalAnd := bitOr  ('&amp;&amp;' bitOr)*
 * bitOr      := bitXor ('|' bitXor)*
 * bitXor     := bitAnd ('^' bitAnd)*
 * bitAnd     := equality ('&amp;' equality)*
 * equality   := unary (('==' | '!=') unary)*
 * unary      := '!' unary | primary
 * primary    := 'true' | 'false' | IDENT | NUMBER | STRING | '(' expr ')'
 * </pre>
 */
public final class BoolParser {

    private final String source;
    private final List<Token> tokens;
    private int index = 0;

    /**
     * Deepest allowed expression, so parsing, checking and code generation can never exhaust the stack.
     * Depth = longest path from the expression root to a leaf, counting the leaf operand and every
     * parenthesis pair, '!' and binary operator on the way (so {@code true} is depth 1, and 149 '!' before it is depth 150).
     */
    public static final int MAX_EXPRESSION_DEPTH = 150;

    /** Currently open '(' and '!' levels: the parser's only unbounded recursion, guarded top-down. */
    private int nesting = 0;
    /** Depth of every node that is deeper than a leaf; checked bottom-up for operator chains. */
    private final Map<Expr, Integer> depths = new IdentityHashMap<>();

    public BoolParser(String source, List<Token> tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    public static Program parse(String source) throws BoolCompileException {
        return new BoolParser(source, new BoolLexer(source).tokenize()).parseProgram();
    }

    public Program parseProgram() throws BoolCompileException {
        List<Stmt> statements = new ArrayList<>();
        while (peek().type() != Type.EOF) {
            statements.add(parseStatement());
        }
        return new Program(statements);
    }

    // ---- statements -----------------------------------------------------

    private Stmt parseStatement() throws BoolCompileException {
        Token first = peek();
        switch (first.type()) {
            case BOOLEAN_KW: return parseDeclaration();
            case IDENT:      return parseAssignment();
            case RESERVED_WORD:
                throw error(first, "'" + first.text() + "' is not supported by the Boolean module");
            default:
                throw error(first, "expected a declaration or assignment, found " + describe(first));
        }
    }

    private Stmt parseDeclaration() throws BoolCompileException {
        Token start = advance();                       // 'boolean'
        Token name = peek();
        if (name.type() != Type.IDENT) {
            throw error(name, "expected variable name after 'boolean', found " + describe(name));
        }
        advance();
        Expr init = null;
        if (peek().type() == Type.ASSIGN) {
            advance();
            init = parseExpression();
        }
        Token semi = expectSemicolon();
        return new Decl(name.text(), init, sourceText(start, semi), pos(name));
    }

    private Stmt parseAssignment() throws BoolCompileException {
        Token name = advance();
        Token opTok = peek();
        AssignOp op = switch (opTok.type()) {
            case ASSIGN -> AssignOp.ASSIGN;
            case AND_ASSIGN -> AssignOp.AND;
            case OR_ASSIGN -> AssignOp.OR;
            case XOR_ASSIGN -> AssignOp.XOR;
            default -> throw error(opTok, "expected an assignment operator after '" + name.text()
                    + "', found " + describe(opTok));
        };
        advance();
        Expr value = parseExpression();
        Token semi = expectSemicolon();
        return new Assign(name.text(), op, value, sourceText(name, semi), pos(name));
    }

    private Token expectSemicolon() throws BoolCompileException {
        Token t = peek();
        if (t.type() == Type.SEMI) return advance();
        if (t.type() == Type.ASSIGN || t.type() == Type.AND_ASSIGN
                || t.type() == Type.OR_ASSIGN || t.type() == Type.XOR_ASSIGN) {
            throw error(t, "assignment used as an expression is not supported");
        }
        if (t.type() == Type.UNSUPPORTED_OP) throw unsupportedOperator(t);
        throw error(t, "expected ';' or an operator, found " + describe(t));
    }

    // ---- expressions (lowest precedence first) ----------------------------

    private Expr parseExpression() throws BoolCompileException {
        return parseLogicalOr();
    }

    private Expr parseLogicalOr() throws BoolCompileException {
        Expr left = parseLogicalAnd();
        while (peek().type() == Type.OR_OR) left = binary(BinOp.LOGICAL_OR, left, this::parseLogicalAnd);
        return left;
    }

    private Expr parseLogicalAnd() throws BoolCompileException {
        Expr left = parseBitOr();
        while (peek().type() == Type.AND_AND) left = binary(BinOp.LOGICAL_AND, left, this::parseBitOr);
        return left;
    }

    private Expr parseBitOr() throws BoolCompileException {
        Expr left = parseBitXor();
        while (peek().type() == Type.PIPE) left = binary(BinOp.BIT_OR, left, this::parseBitXor);
        return left;
    }

    private Expr parseBitXor() throws BoolCompileException {
        Expr left = parseBitAnd();
        while (peek().type() == Type.CARET) left = binary(BinOp.XOR, left, this::parseBitAnd);
        return left;
    }

    private Expr parseBitAnd() throws BoolCompileException {
        Expr left = parseEquality();
        while (peek().type() == Type.AMP) left = binary(BinOp.BIT_AND, left, this::parseEquality);
        return left;
    }

    private Expr parseEquality() throws BoolCompileException {
        Expr left = parseUnary();
        while (peek().type() == Type.EQ_EQ || peek().type() == Type.NOT_EQ) {
            BinOp op = peek().type() == Type.EQ_EQ ? BinOp.EQ : BinOp.NE;
            left = binary(op, left, this::parseUnary);
        }
        return left;
    }

    private Expr parseUnary() throws BoolCompileException {
        if (peek().type() == Type.NOT) {
            Token bang = advance();
            enterNesting(bang);
            Expr operand = parseUnary();
            nesting--;
            return track(new Not(operand, pos(bang)), depthOf(operand) + 1, bang);
        }
        return parsePrimary();
    }

    private Expr parsePrimary() throws BoolCompileException {
        Token t = peek();
        switch (t.type()) {
            case TRUE:       advance(); return new BoolLit(true, pos(t));
            case FALSE:      advance(); return new BoolLit(false, pos(t));
            case IDENT:      advance(); return new VarRef(t.text(), pos(t));
            case NUMBER_LIT: advance(); return new NumberLit(t.text(), pos(t));
            case STRING_LIT: advance(); return new StringLit(t.text(), pos(t));
            case LPAREN: {
                Token open = advance();
                enterNesting(open);
                Expr inner = parseExpression();
                Token close = peek();
                if (close.type() != Type.RPAREN) {
                    throw error(close, "expected ')', found " + describe(close));
                }
                advance();
                nesting--;
                return track(inner, depthOf(inner) + 1, open);   // a parenthesis pair is one level
            }
            case UNSUPPORTED_OP: throw unsupportedOperator(t);
            case RESERVED_WORD:
                throw error(t, "'" + t.text() + "' is not supported by the Boolean module");
            default:
                throw error(t, "expected an expression (operand missing), found " + describe(t));
        }
    }

    /** Consumes the operator at the cursor, parses the right operand with {@code rhs}, builds the node. */
    private Expr binary(BinOp op, Expr left, ExprParser rhs) throws BoolCompileException {
        Token opTok = advance();
        Expr right = rhs.parse();
        return track(new Binary(op, left, right, pos(opTok)), 1 + Math.max(depthOf(left), depthOf(right)), opTok);
    }

    @FunctionalInterface
    private interface ExprParser { Expr parse() throws BoolCompileException; }

    // ---- helpers ---------------------------------------------------------

    private Token peek() { return tokens.get(index); }

    private Token advance() {
        Token t = tokens.get(index);
        if (t.type() != Type.EOF) index++;
        return t;
    }

    // ---- depth limit -----------------------------------------------------

    private int depthOf(Expr e) { return depths.getOrDefault(e, 1); }

    /** Records the depth of {@code e}; rejects the program at token {@code at} if it exceeds the limit. */
    private Expr track(Expr e, int depth, Token at) throws BoolCompileException {
        if (depth > MAX_EXPRESSION_DEPTH) throw tooDeep(at);
        depths.put(e, depth);
        return e;
    }

    /** Called before descending into '(' or '!': stops the recursion before it gets dangerous. */
    private void enterNesting(Token at) throws BoolCompileException {
        nesting++;
        if (nesting >= MAX_EXPRESSION_DEPTH) throw tooDeep(at);   // the leaf below would be depth > limit
    }

    private static BoolCompileException tooDeep(Token t) {
        return new BoolCompileException(Phase.LIMIT, "expression is nested too deeply (limit is "
                + MAX_EXPRESSION_DEPTH + " levels; each '(' pair, '!' and binary operator adds one level)",
                t.line(), t.column());
    }

    private static Pos pos(Token t) { return new Pos(t.line(), t.column()); }

    private String sourceText(Token from, Token to) {
        return source.substring(from.offset(), to.offset() + to.text().length()).replaceAll("\\s+", " ");
    }

    private static String describe(Token t) {
        return t.type() == Type.EOF ? "end of input" : "'" + t.text() + "'";
    }

    private BoolCompileException unsupportedOperator(Token t) {
        return error(t, "operator '" + t.text() + "' is not supported by the Boolean module");
    }

    private BoolCompileException error(Token t, String message) {
        return new BoolCompileException(Phase.SYNTAX, message, t.line(), t.column());
    }
}
