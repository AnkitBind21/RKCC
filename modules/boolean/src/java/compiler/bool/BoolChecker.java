package compiler.bool;

import compiler.bool.Ast.*;
import compiler.bool.BoolCompileException.Phase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3: semantic validation. Statements are straight-line, so definite assignment is exact:
 * a variable may be read only after an earlier statement has assigned it (as javac requires).
 */
public final class BoolChecker {

    /** Types the checker can tell apart. Only BOOLEAN is acceptable; the others exist for error messages. */
    public enum ValueType {
        BOOLEAN("boolean"), NUMBER("number"), STRING("String");
        final String javaName;
        ValueType(String javaName) { this.javaName = javaName; }
    }

    /** Validated program plus its variables in declaration order. */
    public record Result(Program program, List<String> variables) {}

    private static final class VarInfo {
        boolean assigned;
    }

    private final Map<String, VarInfo> symbols = new LinkedHashMap<>();

    public static Result check(Program program) throws BoolCompileException {
        return new BoolChecker().run(program);
    }

    private Result run(Program program) throws BoolCompileException {
        for (Stmt s : program.statements()) {
            switch (s) {
                case Decl d -> checkDecl(d);
                case Assign a -> checkAssign(a);
            }
        }
        return new Result(program, new ArrayList<>(symbols.keySet()));
    }

    private void checkDecl(Decl d) throws BoolCompileException {
        if (symbols.containsKey(d.name())) {
            throw error(d.pos(), "variable '" + d.name() + "' is already defined");
        }
        symbols.put(d.name(), new VarInfo());       // in scope inside its own initializer, as in Java
        if (d.init() != null) {
            requireBoolean(d.init(), typeOf(d.init()));
            symbols.get(d.name()).assigned = true;
        }
    }

    private void checkAssign(Assign a) throws BoolCompileException {
        VarInfo info = symbols.get(a.name());
        if (info == null) {
            throw error(a.pos(), "cannot find symbol: variable '" + a.name() + "'");
        }
        if (a.op() != AssignOp.ASSIGN && !info.assigned) {   // a op= e reads a first
            throw error(a.pos(), "variable '" + a.name() + "' might not have been initialized");
        }
        requireBoolean(a.value(), typeOf(a.value()));
        info.assigned = true;
    }

    private void requireBoolean(Expr e, ValueType t) throws BoolCompileException {
        if (t != ValueType.BOOLEAN) {
            throw error(e.pos(), "incompatible types: " + t.javaName + " cannot be converted to boolean");
        }
    }

    private ValueType typeOf(Expr e) throws BoolCompileException {
        switch (e) {
            case BoolLit b: return ValueType.BOOLEAN;
            case NumberLit n: return ValueType.NUMBER;
            case StringLit s: return ValueType.STRING;
            case VarRef v: {
                VarInfo info = symbols.get(v.name());
                if (info == null) {
                    throw error(v.pos(), "cannot find symbol: variable '" + v.name() + "'"
                            + (v.name().equals("True") || v.name().equals("False") || v.name().equals("TRUE")
                            || v.name().equals("FALSE") ? " (boolean literals are lowercase: true, false)" : ""));
                }
                if (!info.assigned) {
                    throw error(v.pos(), "variable '" + v.name() + "' might not have been initialized");
                }
                return ValueType.BOOLEAN;
            }
            case Not n: {
                ValueType t = typeOf(n.operand());
                if (t != ValueType.BOOLEAN) {
                    throw error(n.pos(), "bad operand type " + t.javaName + " for unary operator '!'");
                }
                return ValueType.BOOLEAN;
            }
            case Binary b: {
                ValueType l = typeOf(b.left());
                ValueType r = typeOf(b.right());
                if (b.op().isComparison()) {
                    if (l == ValueType.BOOLEAN && r == ValueType.BOOLEAN) return ValueType.BOOLEAN;
                    if (l != ValueType.BOOLEAN && r != ValueType.BOOLEAN) {
                        throw error(b.pos(), "operator '" + b.op().symbol + "' on " + l.javaName + " and "
                                + r.javaName + " operands is not supported by the Boolean module");
                    }
                    throw error(b.pos(), "incomparable types: " + l.javaName + " and " + r.javaName);
                }
                if (l != ValueType.BOOLEAN || r != ValueType.BOOLEAN) {
                    throw error(b.pos(), "bad operand types for binary operator '" + b.op().symbol
                            + "' (first type: " + l.javaName + ", second type: " + r.javaName + ")");
                }
                return ValueType.BOOLEAN;
            }
        }
    }

    private static BoolCompileException error(Pos p, String msg) {
        return new BoolCompileException(Phase.SEMANTIC, msg, p.line(), p.column());
    }
}
