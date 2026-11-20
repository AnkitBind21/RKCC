package compiler.bool;

/** Lexical unit. {@code offset} is the 0-based index into the source (used for statement text in comments). */
public record Token(Type type, String text, int line, int column, int offset) {

    public enum Type {
        BOOLEAN_KW, TRUE, FALSE, IDENT,
        NUMBER_LIT, STRING_LIT,                       // recognised only so type errors can be reported
        NOT, AND_AND, OR_OR, AMP, PIPE, CARET,         // ! && || & | ^
        EQ_EQ, NOT_EQ,                                 // == !=
        ASSIGN, AND_ASSIGN, OR_ASSIGN, XOR_ASSIGN,     // = &= |= ^=
        LPAREN, RPAREN, SEMI,
        UNSUPPORTED_OP,                                // + - * / % < > <= >= ~ ? : , . etc.
        RESERVED_WORD,                                 // any other Java keyword (int, if, ...)
        EOF
    }
}
