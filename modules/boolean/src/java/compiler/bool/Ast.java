package compiler.bool;

import java.util.List;

/** Phase 2 output: the syntax tree for Boolean programs. Pure data, no behaviour. */
public final class Ast {
    private Ast() {}

    public record Pos(int line, int column) {}

    // ---- expressions ----------------------------------------------------
    public sealed interface Expr permits BoolLit, NumberLit, StringLit, VarRef, Not, Binary {
        Pos pos();
    }

    public record BoolLit(boolean value, Pos pos) implements Expr {}
    /** Parsed only so the checker can report a type mismatch; never reaches code generation. */
    public record NumberLit(String text, Pos pos) implements Expr {}
    public record StringLit(String text, Pos pos) implements Expr {}
    public record VarRef(String name, Pos pos) implements Expr {}
    public record Not(Expr operand, Pos pos) implements Expr {}
    /** {@code pos} is the position of the operator token. */
    public record Binary(BinOp op, Expr left, Expr right, Pos pos) implements Expr {}

    public enum BinOp {
        LOGICAL_AND("&&", true), LOGICAL_OR("||", true),
        BIT_AND("&", false), BIT_OR("|", false), XOR("^", false),
        EQ("==", false), NE("!=", false);

        public final String symbol;
        public final boolean shortCircuit;

        BinOp(String symbol, boolean shortCircuit) {
            this.symbol = symbol;
            this.shortCircuit = shortCircuit;
        }

        public boolean isComparison() { return this == EQ || this == NE; }
    }

    // ---- statements -----------------------------------------------------
    public sealed interface Stmt permits Decl, Assign {
        Pos pos();
        String source();
    }

    /** {@code boolean name;} or {@code boolean name = init;} ({@code init} may be null). */
    public record Decl(String name, Expr init, String source, Pos pos) implements Stmt {}

    public record Assign(String name, AssignOp op, Expr value, String source, Pos pos) implements Stmt {}

    public enum AssignOp {
        ASSIGN("=", null), AND("&=", BinOp.BIT_AND), OR("|=", BinOp.BIT_OR), XOR("^=", BinOp.XOR);

        public final String symbol;
        /** For compound forms: the binary operator they expand to (a op= e is a = a op e). */
        public final BinOp binOp;

        AssignOp(String symbol, BinOp binOp) {
            this.symbol = symbol;
            this.binOp = binOp;
        }
    }

    public record Program(List<Stmt> statements) {}
}
