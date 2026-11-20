package compiler.bool;

/**
 * Any error found while compiling a Boolean program; carries phase and source position.
 * LIMIT errors mean the program may be valid Java but exceeds an implementation limit (nesting depth).
 * A line of 0 means "position unknown" (used only by the stack-overflow safety net) and is left out of the message.
 */
public final class BoolCompileException extends Exception {

    public enum Phase { LEXICAL, SYNTAX, SEMANTIC, LIMIT }

    private final Phase phase;
    private final int line;
    private final int column;
    private final String detail;

    public BoolCompileException(Phase phase, String detail, int line, int column) {
        super(phase.name().toLowerCase() + " error" + (line > 0 ? " at " + line + ":" + column : "") + ": " + detail);
        this.phase = phase;
        this.detail = detail;
        this.line = line;
        this.column = column;
    }

    public Phase phase() { return phase; }
    public int line() { return line; }
    public int column() { return column; }
    public String detail() { return detail; }
}
