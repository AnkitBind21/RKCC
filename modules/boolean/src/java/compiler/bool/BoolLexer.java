package compiler.bool;

import compiler.bool.Token.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Phase 1: token recognition. Understands the Boolean subset plus enough Java to give good errors. */
public final class BoolLexer {

    /** Java keywords other than boolean/true/false; using one is reported as unsupported. */
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto",
            "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while");

    private final String src;
    private int pos = 0;
    private int line = 1;
    private int col = 1;

    public BoolLexer(String source) {
        this.src = source;
    }

    public List<Token> tokenize() throws BoolCompileException {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                out.add(new Token(Type.EOF, "", line, col, pos));
                return out;
            }
            out.add(next());
        }
    }

    private Token next() throws BoolCompileException {
        char c = src.charAt(pos);
        int startLine = line, startCol = col, start = pos;

        if (isIdentStart(c)) {
            while (pos < src.length() && isIdentPart(src.charAt(pos))) advance();
            String word = src.substring(start, pos);
            Type t = switch (word) {
                case "boolean" -> Type.BOOLEAN_KW;
                case "true" -> Type.TRUE;
                case "false" -> Type.FALSE;
                default -> RESERVED.contains(word) ? Type.RESERVED_WORD : Type.IDENT;
            };
            return new Token(t, word, startLine, startCol, start);
        }
        if (c >= '0' && c <= '9') {
            while (pos < src.length() && (isIdentPart(src.charAt(pos)) || src.charAt(pos) == '.')) advance();
            return new Token(Type.NUMBER_LIT, src.substring(start, pos), startLine, startCol, start);
        }
        if (c == '"') {
            advance();
            while (true) {
                if (pos >= src.length() || src.charAt(pos) == '\n') {
                    throw new BoolCompileException(BoolCompileException.Phase.LEXICAL,
                            "unterminated string literal", startLine, startCol);
                }
                char d = src.charAt(pos);
                advance();
                if (d == '\\' && pos < src.length()) advance();
                else if (d == '"') break;
            }
            return new Token(Type.STRING_LIT, src.substring(start, pos), startLine, startCol, start);
        }

        advance();
        switch (c) {
            case '(': return tok(Type.LPAREN, start, startLine, startCol);
            case ')': return tok(Type.RPAREN, start, startLine, startCol);
            case ';': return tok(Type.SEMI, start, startLine, startCol);
            case '^': return tok(match('=') ? Type.XOR_ASSIGN : Type.CARET, start, startLine, startCol);
            case '!': return tok(match('=') ? Type.NOT_EQ : Type.NOT, start, startLine, startCol);
            case '=': return tok(match('=') ? Type.EQ_EQ : Type.ASSIGN, start, startLine, startCol);
            case '&':
                if (match('&')) return tok(Type.AND_AND, start, startLine, startCol);
                return tok(match('=') ? Type.AND_ASSIGN : Type.AMP, start, startLine, startCol);
            case '|':
                if (match('|')) return tok(Type.OR_OR, start, startLine, startCol);
                return tok(match('=') ? Type.OR_ASSIGN : Type.PIPE, start, startLine, startCol);
            case '+': case '-': case '*': case '/': case '%': case '<': case '>': case '~':
                while (pos < src.length() && "+-*/%<>~".indexOf(src.charAt(pos)) >= 0) advance();
                match('=');
                return tok(Type.UNSUPPORTED_OP, start, startLine, startCol);
            case '?': case ':': case ',': case '.': case '[': case ']': case '{': case '}': case '@': case '#':
                return tok(Type.UNSUPPORTED_OP, start, startLine, startCol);
            default:
                throw new BoolCompileException(BoolCompileException.Phase.LEXICAL,
                        "illegal character '" + c + "'", startLine, startCol);
        }
    }

    private Token tok(Type type, int start, int l, int c) {
        return new Token(type, src.substring(start, pos), l, c, start);
    }

    private boolean match(char expected) {
        if (pos < src.length() && src.charAt(pos) == expected) {
            advance();
            return true;
        }
        return false;
    }

    private void skipWhitespaceAndComments() throws BoolCompileException {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') advance();
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                int l = line, k = col;
                advance(); advance();
                while (!(pos + 1 < src.length() && src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    if (pos >= src.length() - 1) {
                        throw new BoolCompileException(BoolCompileException.Phase.LEXICAL,
                                "unterminated comment", l, k);
                    }
                    advance();
                }
                advance(); advance();
            } else {
                return;
            }
        }
    }

    private void advance() {
        if (src.charAt(pos) == '\n') { line++; col = 1; } else { col++; }
        pos++;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}
