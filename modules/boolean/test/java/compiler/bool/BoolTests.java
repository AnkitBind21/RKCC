package compiler.bool;

import compiler.bool.Ast.*;
import compiler.bool.BoolCompileException.Phase;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free test runner (the team repo has no Maven/JUnit yet).
 * Execution tests assemble the generated NASM with nasm+ld, run it, and compare the printed
 * variable values with the same source compiled and run by real Java (javax.tools).
 * They are SKIPPED (and reported as such) when nasm/ld or Linux x86-64 are unavailable.
 * Library-mode integration tests additionally need gcc: the generated library routine is assembled,
 * linked with a small C host program through the normal C toolchain, executed, and compared with Java.
 */
public final class BoolTests {

    private static int passed, failed, skipped;
    private static final boolean CAN_EXECUTE = detectExecution();
    private static final boolean CAN_LINK_C = CAN_EXECUTE && detectTool("gcc", "--version");
    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    public static void main(String[] args) throws Exception {
        System.out.println("== RKCC Boolean module tests ==");
        System.out.println("asm execution: " + (CAN_EXECUTE ? "ENABLED (nasm + ld, Linux x86-64)" : "DISABLED (tests skipped)"));
        System.out.println("java reference: " + (JAVAC != null ? "ENABLED (javax.tools)" : "DISABLED"));
        System.out.println("library integration (C host via gcc): " + (CAN_LINK_C ? "ENABLED" : "DISABLED (tests skipped)"));

        lexerTests();
        parserTests();
        invalidProgramTests();
        onStackOf(1L << 20, BoolTests::depthLimitTests);   // explicit 1 MB: independent of the JVM's -Xss
        safetyNetTests();
        codegenStructureTests();
        gnuStackNoteTests();
        executionTests();
        libraryIntegrationTests();
        randomDifferentialTests(200);

        System.out.printf("%n== RESULT: %d passed, %d failed, %d skipped ==%n", passed, failed, skipped);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ lexer / parser

    private static void lexerTests() throws Exception {
        List<Token> t = new BoolLexer("boolean a = !b && c || d & e | f ^ g == h != i; a &= true; // c\n/* x */ a |= false; a ^= true;").tokenize();
        List<Token.Type> types = t.stream().map(Token::type).toList();
        check("lexer: all Boolean operators and compound assignments recognised",
                types.containsAll(List.of(Token.Type.BOOLEAN_KW, Token.Type.NOT, Token.Type.AND_AND, Token.Type.OR_OR,
                        Token.Type.AMP, Token.Type.PIPE, Token.Type.CARET, Token.Type.EQ_EQ, Token.Type.NOT_EQ,
                        Token.Type.AND_ASSIGN, Token.Type.OR_ASSIGN, Token.Type.XOR_ASSIGN, Token.Type.TRUE, Token.Type.FALSE)));
        check("lexer: comments are skipped", t.stream().noneMatch(x -> x.text().contains("//") || x.text().contains("/*")));
        check("lexer: 'True' is an identifier, not a literal",
                new BoolLexer("True").tokenize().get(0).type() == Token.Type.IDENT);
        check("lexer: 'int' is a reserved word",
                new BoolLexer("int").tokenize().get(0).type() == Token.Type.RESERVED_WORD);
        Token amp = new BoolLexer("a&&b").tokenize().get(1);
        check("lexer: '&&' is one token (not two '&')", amp.type() == Token.Type.AND_AND && amp.column() == 2);
    }

    private static void parserTests() throws Exception {
        Program p = BoolParser.parse("boolean r = a || b && c;");
        Binary or = (Binary) ((Decl) p.statements().get(0)).init();
        check("parser: && binds tighter than ||", or.op() == BinOp.LOGICAL_OR && ((Binary) or.right()).op() == BinOp.LOGICAL_AND);

        Binary and = (Binary) ((Decl) BoolParser.parse("boolean r = a == b & c;").statements().get(0)).init();
        check("parser: == binds tighter than &", and.op() == BinOp.BIT_AND && ((Binary) and.left()).op() == BinOp.EQ);

        Binary or2 = (Binary) ((Decl) BoolParser.parse("boolean r = a | b ^ c & d;").statements().get(0)).init();
        Binary xor = (Binary) or2.right();
        check("parser: precedence & > ^ > |", or2.op() == BinOp.BIT_OR && xor.op() == BinOp.XOR
                && ((Binary) xor.right()).op() == BinOp.BIT_AND);

        Binary left = (Binary) ((Decl) BoolParser.parse("boolean r = a == b == c;").statements().get(0)).init();
        check("parser: == is left-associative", left.op() == BinOp.EQ && left.left() instanceof Binary && left.right() instanceof VarRef);

        Expr n = ((Decl) BoolParser.parse("boolean r = !!a;").statements().get(0)).init();
        check("parser: nested NOT", n instanceof Not nn && nn.operand() instanceof Not);

        Expr paren = ((Decl) BoolParser.parse("boolean r = (a || b) && c;").statements().get(0)).init();
        check("parser: parentheses override precedence", paren instanceof Binary b && b.op() == BinOp.LOGICAL_AND && b.left() instanceof Binary);

        Stmt s = BoolParser.parse("boolean a;").statements().get(0);
        check("parser: declaration without initialiser", s instanceof Decl d && d.init() == null);
        Stmt c = BoolParser.parse("a ^= b;").statements().get(0);
        check("parser: compound assignment", c instanceof Assign a && a.op() == AssignOp.XOR);
        check("parser: empty program is valid", BoolParser.parse("  // nothing\n").statements().isEmpty());
    }

    // ------------------------------------------------------------------ invalid programs

    private static void invalidProgramTests() {
        // lexical
        bad("illegal character", "boolean a = true `;", Phase.LEXICAL, "illegal character");
        bad("unterminated string", "boolean a = \"abc;", Phase.LEXICAL, "unterminated string");
        bad("unterminated comment", "boolean a = true; /* oops", Phase.LEXICAL, "unterminated comment");
        // syntax: invalid literals / missing operands / bad operator usage
        bad("missing right operand", "boolean a = true &&;", Phase.SYNTAX, "operand missing");
        bad("missing left operand", "boolean a = && true;", Phase.SYNTAX, "operand missing");
        bad("double operator", "boolean a = true && && false;", Phase.SYNTAX, "operand missing");
        bad("dangling '&'", "boolean a = true & ;", Phase.SYNTAX, "operand missing");
        bad("'!' without operand", "boolean a = !;", Phase.SYNTAX, "operand missing");
        bad("empty initialiser", "boolean a = ;", Phase.SYNTAX, "operand missing");
        bad("two operands, no operator", "boolean a = true false;", Phase.SYNTAX, "expected ';' or an operator");
        bad("'!' used as binary operator", "boolean a = true ! false;", Phase.SYNTAX, "expected ';' or an operator");
        bad("missing semicolon at EOF", "boolean a = true", Phase.SYNTAX, "end of input");
        bad("unbalanced '('", "boolean a = (true && false;", Phase.SYNTAX, "expected ')'");
        bad("unbalanced ')'", "boolean a = true && false);", Phase.SYNTAX, "expected ';' or an operator");
        bad("empty parentheses", "boolean a = ();", Phase.SYNTAX, "operand missing");
        bad("relational operator", "boolean a = true < false;", Phase.SYNTAX, "not supported");
        bad("arithmetic operator", "boolean a = true + false;", Phase.SYNTAX, "not supported");
        bad("ternary operator", "boolean a = true ? false : true;", Phase.SYNTAX, "not supported");
        bad("assignment as expression", "boolean a = true; boolean b; b = a = false;", Phase.SYNTAX, "assignment used as an expression");
        bad("'=' in declaration after name", "boolean a == true;", Phase.SYNTAX, "expected ';' or an operator");
        bad("missing variable name", "boolean = true;", Phase.SYNTAX, "expected variable name");
        bad("variable name starts with digit", "boolean 1a = true;", Phase.SYNTAX, "expected variable name");
        bad("expression statement", "a && b;", Phase.SYNTAX, "expected an assignment operator");
        bad("literal statement", "true;", Phase.SYNTAX, "expected a declaration or assignment");
        bad("other type keyword", "int a = 1;", Phase.SYNTAX, "not supported by the Boolean module");
        bad("reserved word as operand", "boolean a = null;", Phase.SYNTAX, "not supported by the Boolean module");
        bad("empty statement ';;'", "boolean a = true;;", Phase.SYNTAX, "expected a declaration");
        // semantic: invalid boolean literals are identifiers, hence undefined variables
        bad("misspelled literal 'tru'", "boolean a = tru;", Phase.SEMANTIC, "cannot find symbol");
        bad("capitalised literal 'True'", "boolean a = True;", Phase.SEMANTIC, "lowercase");
        bad("capitalised literal 'FALSE'", "boolean a = FALSE;", Phase.SEMANTIC, "lowercase");
        bad("undefined variable in expression", "boolean a = true; boolean b = a && c;", Phase.SEMANTIC, "cannot find symbol: variable 'c'");
        bad("assignment to undeclared variable", "a = true;", Phase.SEMANTIC, "cannot find symbol");
        bad("use before assignment", "boolean a; boolean b = a;", Phase.SEMANTIC, "might not have been initialized");
        bad("self-reference in initialiser", "boolean a = a;", Phase.SEMANTIC, "might not have been initialized");
        bad("compound assign on unassigned", "boolean a; a |= true;", Phase.SEMANTIC, "might not have been initialized");
        bad("duplicate declaration", "boolean a = true; boolean a = false;", Phase.SEMANTIC, "already defined");
        bad("int literal to boolean", "boolean a = 1;", Phase.SEMANTIC, "number cannot be converted to boolean");
        bad("String literal to boolean", "boolean a = \"x\";", Phase.SEMANTIC, "String cannot be converted to boolean");
        bad("int assigned to boolean var", "boolean a = true; a = 0;", Phase.SEMANTIC, "cannot be converted to boolean");
        bad("'!' on number", "boolean a = !1;", Phase.SEMANTIC, "bad operand type number for unary operator '!'");
        bad("'&&' with number", "boolean a = true && 1;", Phase.SEMANTIC, "bad operand types for binary operator '&&'");
        bad("'^' with number", "boolean a = 1 ^ true;", Phase.SEMANTIC, "bad operand types for binary operator '^'");
        bad("boolean == number", "boolean a = true == 1;", Phase.SEMANTIC, "incomparable types: boolean and number");
        bad("number == number (other module)", "boolean a = 1 == 1;", Phase.SEMANTIC, "not supported by the Boolean module");
        bad("compound with number", "boolean a = true; a &= 1;", Phase.SEMANTIC, "cannot be converted to boolean");

        try {
            BoolCompiler.compile("boolean a = true;\nboolean b = a &&\n   zzz;");
            check("error position reported", false);
        } catch (BoolCompileException e) {
            check("error position reported (line 3, col 4)", e.line() == 3 && e.column() == 4);
        }
    }

    private static void bad(String name, String src, Phase phase, String messagePart) {
        try {
            BoolCompiler.compile(src);
            check("invalid: " + name + " (was accepted!)", false);
        } catch (BoolCompileException e) {
            boolean ok = e.phase() == phase && e.detail().contains(messagePart);
            check("invalid: " + name, ok);
            if (!ok) System.out.println("      got " + e.getMessage());
        } catch (RuntimeException e) {
            check("invalid: " + name + " (crashed: " + e + ")", false);
        }
    }

    // ------------------------------------------------------------------ assembly structure

    private static void codegenStructureTests() throws Exception {
        String and = BoolCompiler.compile("boolean a = true; boolean b = false; boolean c = a && b;");
        check("asm: '&&' emits test + conditional jump (short-circuit)", and.contains("test al, al") && and.contains("jz .L"));
        String or = BoolCompiler.compile("boolean a = true; boolean b = false; boolean c = a || b;");
        check("asm: '||' emits test + jnz (short-circuit)", or.contains("jnz .L"));
        String eager = BoolCompiler.compile("boolean a = true; boolean b = false; boolean c = a & b; boolean d = a | b; boolean e = a ^ b;");
        check("asm: '&', '|', '^' emit no jumps (non-short-circuit)", !Pattern.compile("(?m)^\\s+j\\w+ ").matcher(eager).find());
        check("asm: '&' uses AND, '|' uses OR, '^' uses XOR",
                eager.contains("and al, [b_b]") && eager.contains("or al, [b_b]") && eager.contains("xor al, [b_b]"));
        check("asm: NOT uses xor al, 1", BoolCompiler.compile("boolean a = !true;").contains("xor al, 1"));
        check("asm: header declares NASM 64-bit mode", and.contains("bits 64") && and.contains("section .data") && and.contains("section .text"));
        check("asm: library mode has no _start / syscall", !and.contains("_start") && !and.contains("syscall"));

        String nested = BoolCompiler.compile("boolean a = true; boolean r = ((a && !a) || (a && a)) && ((a || a) || (a && !a));");
        Set<String> defined = new HashSet<>(), used = new HashSet<>();
        Matcher d = Pattern.compile("(?m)^(\\.L\\w+):").matcher(nested);
        while (d.find()) { if (!defined.add(d.group(1))) defined.add("DUPLICATE"); }
        Matcher u = Pattern.compile("(?m)^\\s+j\\w+ (\\.L\\w+)").matcher(nested);
        while (u.find()) used.add(u.group(1));
        check("asm: labels are unique", !defined.contains("DUPLICATE") && defined.size() >= 6);
        check("asm: every jump target is defined", defined.containsAll(used));

        String balanced = BoolCompiler.compile("boolean a = true; boolean r = (a ^ (a & (a | !a))) == ((a != a) | (a ^ a));");
        check("asm: push/pop are balanced", count(balanced, "push rax") == count(balanced, "pop rax") && count(balanced, "push rax") > 0);
        check("asm: variable names are mangled (b_ prefix)", BoolCompiler.compile("boolean al = true; boolean rax = al;").contains("[b_al]"));
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    // ------------------------------------------------------------------ execution vs Java

    private static void executionTests() throws Exception {
        exec("basic declarations", "boolean a = true;\nboolean b = false;");
        exec("&&, ||, ! on literals", "boolean c = true && false;\nboolean d = true || false;\nboolean e = !false;");
        exec("comparisons", "boolean x = true == false;\nboolean y = true != false;");
        exec("nested expression", "boolean result = (true && false) || !false;");
        exec("variables", "boolean a = true;\nboolean b = false;\nboolean c = a || b;");
        exec("assignment", "boolean flag = true;\nboolean isActive = false;\nflag = false;\nisActive = true;");
        exec("compound &=, |=, ^=", "boolean a = true;\nboolean b = true;\nboolean c = false;\na &= false;\nb |= false;\nc ^= true;\nc ^= b;");
        exec("compound with complex right side", "boolean a = true; boolean b = false; a ^= (a | b) & !(b != a);");
        exec("precedence: a || b && c", "boolean t = true; boolean f = false; boolean r = t || f && f; boolean s = (t || f) && f;");
        exec("precedence: == vs &", "boolean t = true; boolean f = false; boolean r = f == f & t; boolean s = f == (f & t);");
        exec("precedence: & ^ |", "boolean t = true; boolean f = false; boolean r = f | t ^ t & f; boolean s = t ^ f | f & t;");
        exec("chained ==", "boolean t = true; boolean f = false; boolean r = t == t == f; boolean s = f != t != t;");
        exec("double negation", "boolean a = true; boolean b = !!a; boolean c = !!!a;");
        exec("deeply nested", "boolean a = true; boolean b = false;\nboolean r = ((a ^ (b | !a)) & (!(a == b) || (a != !b))) == !(b && (a || b));");
        exec("comments and whitespace", "// header\nboolean   a=true ; /* mid */ boolean b\n =\n !a ;");
        exec("tricky variable names", "boolean al = true; boolean rax = false; boolean mov = al ^ rax; boolean $x = !mov; boolean _y = $x == al; boolean dump_buf = _y; boolean bool_main = !dump_buf; boolean _start = bool_main | al;");
        exec("variable reassigned from itself", "boolean a = false; a = !a; a = !a; a = !a;");
        exec("uninitialised declaration, assigned later", "boolean a; boolean b = true; a = b && !b; ");
        execExpect("declared but never assigned reads as false", "boolean a; boolean b = true;", "a=0\nb=1\n");

        // exhaustive truth tables for every binary operator
        String[] ops = {"&&", "||", "&", "|", "^", "==", "!="};
        for (String op : ops) {
            for (String av : new String[]{"true", "false"}) {
                for (String bv : new String[]{"true", "false"}) {
                    exec("truth table: " + av + " " + op + " " + bv,
                            "boolean a = " + av + "; boolean b = " + bv + ";\n"
                                    + "boolean viaVars = a " + op + " b;\n"
                                    + "boolean viaLits = " + av + " " + op + " " + bv + ";\n"
                                    + "boolean viaNested = (a " + op + " b) " + op + " !b;\n"
                                    + "boolean viaRightNested = a " + op + " (b " + op + " !a);");
                }
            }
        }
    }

    // ------------------------------------------------------------------ randomised differential test

    private static void randomDifferentialTests(int programs) throws Exception {
        if (!CAN_EXECUTE || JAVAC == null) { skipped++; System.out.println("SKIP random differential tests (needs nasm, ld, javac)"); return; }
        Random rnd = new Random(20261118L);
        int bad = 0;
        String firstFailure = null;
        for (int i = 0; i < programs; i++) {
            String src = randomProgram(rnd);
            try {
                String expected = javaReference(src, variablesOf(src));
                String actual = runAsm(BoolCompiler.compile(src, BoolCodegen.Options.linuxStandalone()));
                if (!expected.equals(actual)) { bad++; if (firstFailure == null) firstFailure = src + "\nexpected:\n" + expected + "actual:\n" + actual; }
            } catch (Exception e) {
                bad++; if (firstFailure == null) firstFailure = src + "\n" + e;
            }
        }
        check("random differential: " + programs + " random programs, asm output == Java output", bad == 0);
        if (firstFailure != null) System.out.println("      first failure:\n" + firstFailure);
    }

    private static String randomProgram(Random r) {
        int vars = 3 + r.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vars; i++) sb.append("boolean v").append(i).append(" = ").append(randomExpr(r, i, 2)).append(";\n");
        int extra = 2 + r.nextInt(4);
        String[] assignOps = {"=", "&=", "|=", "^="};
        for (int i = 0; i < extra; i++) {
            sb.append("v").append(r.nextInt(vars)).append(' ').append(assignOps[r.nextInt(4)]).append(' ')
                    .append(randomExpr(r, vars, 4)).append(";\n");
        }
        return sb.toString();
    }

    private static String randomExpr(Random r, int visibleVars, int depth) {
        if (depth == 0 || r.nextInt(5) == 0) {
            if (visibleVars > 0 && r.nextBoolean()) return "v" + r.nextInt(visibleVars);
            return r.nextBoolean() ? "true" : "false";
        }
        switch (r.nextInt(4)) {
            case 0: return "!" + wrap(randomExpr(r, visibleVars, depth - 1));
            default:
                String[] ops = {"&&", "||", "&", "|", "^", "==", "!="};
                return wrap(randomExpr(r, visibleVars, depth - 1) + " " + ops[r.nextInt(ops.length)] + " "
                        + randomExpr(r, visibleVars, depth - 1));
        }
    }

    /** Randomly parenthesise so both explicit grouping and implicit precedence are exercised. */
    private static String wrap(String s) {
        boolean simple = !s.contains(" ");
        return simple ? s : "(" + s + ")";
    }

    // ------------------------------------------------------------------ execution helpers

    private static void exec(String name, String src) {
        if (!CAN_EXECUTE || JAVAC == null) { skipped++; return; }
        try {
            List<String> vars = variablesOf(src);
            String expected = javaReference(src, vars);
            String actual = runAsm(BoolCompiler.compile(src, BoolCodegen.Options.linuxStandalone()));
            boolean ok = expected.equals(actual);
            check("exec: " + name, ok);
            if (!ok) System.out.println("      expected:\n" + expected + "      actual:\n" + actual);
        } catch (Exception e) {
            check("exec: " + name + " (" + e + ")", false);
        }
    }

    private static void execExpect(String name, String src, String expected) {
        if (!CAN_EXECUTE) { skipped++; return; }
        try {
            String actual = runAsm(BoolCompiler.compile(src, BoolCodegen.Options.linuxStandalone()));
            check("exec: " + name, expected.equals(actual));
        } catch (Exception e) {
            check("exec: " + name + " (" + e + ")", false);
        }
    }

    private static List<String> variablesOf(String src) throws BoolCompileException {
        return BoolChecker.check(BoolParser.parse(src)).variables();
    }

    /** Runs the program as real Java and prints "name=0/1" per variable, same format as the asm dump. */
    private static String javaReference(String source, List<String> vars) throws Exception {
        Path dir = Files.createTempDirectory("rkcc-ref");
        StringBuilder sb = new StringBuilder("public class Ref { public static void main(String[] $args) {\n").append(source).append('\n');
        for (String v : vars) sb.append("System.out.println(\"").append(v).append("=\" + (").append(v).append(" ? 1 : 0));\n");
        sb.append("}}");
        Path file = dir.resolve("Ref.java");
        Files.writeString(file, sb);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        if (JAVAC.run(null, null, err, "-d", dir.toString(), file.toString()) != 0) {
            throw new IllegalStateException("javac rejected the program: " + err);
        }
        PrintStream oldOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{dir.toUri().toURL()})) {
            System.setOut(new PrintStream(captured, true));
            cl.loadClass("Ref").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(oldOut);
        }
        return captured.toString().replace("\r\n", "\n");
    }

    /** Assembles with nasm, links with ld, runs, returns stdout. */
    private static String runAsm(String asm) throws Exception {
        Path dir = Files.createTempDirectory("rkcc-asm");
        Files.writeString(dir.resolve("p.asm"), asm);
        sh(dir, "nasm", "-f", "elf64", "p.asm", "-o", "p.o");
        sh(dir, "ld", "p.o", "-o", "p");
        return sh(dir, dir.resolve("p").toString());
    }

    private static String sh(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("timeout: " + String.join(" ", cmd)); }
        if (p.exitValue() != 0) throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + out);
        return out;
    }

    private static boolean detectExecution() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "");
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) return false;
        try {
            return new ProcessBuilder("nasm", "-v").start().waitFor() == 0
                    && new ProcessBuilder("ld", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ nesting depth limit (150)

    private static final int LIMIT = BoolParser.MAX_EXPRESSION_DEPTH;
    private static final String VARS = "boolean t = true; boolean f = false;\n";
    private static final String[] ALL_OPS = {"&&", "||", "&", "|", "^", "==", "!="};

    private static String parensOf(int k)  { return VARS + "boolean r = " + "(".repeat(k) + "t" + ")".repeat(k) + ";"; }
    private static String notsOf(int k)    { return VARS + "boolean r = " + "!".repeat(k) + "t;"; }
    /** {@code t op f op t op f ...} with the given number of operands (left-deep chain, depth = operands). */
    private static String chainOf(String op, int operands) {
        StringBuilder sb = new StringBuilder(VARS).append("boolean r = t");
        for (int i = 1; i < operands; i++) sb.append(' ').append(op).append(' ').append(i % 2 == 1 ? "f" : "t");
        return sb.append(';').toString();
    }
    /** {@code t ^ (t ^ (... f))}: every level adds a binary operator and a parenthesis pair, depth = 1 + 2n. */
    private static String rightNestOf(int n) { return VARS + "boolean r = " + "t ^ (".repeat(n) + "f" + ")".repeat(n) + ";"; }
    /** {@code !(t ^ !(t ^ ... f))}: every level adds '!', a parenthesis pair and an operator, depth = 1 + 3n. */
    private static String mixedOf(int n)     { return VARS + "boolean r = " + "!(t ^ ".repeat(n) + "f" + ")".repeat(n) + ";"; }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    /** Runs {@code body} on a thread with a known stack size so results do not depend on the launcher's -Xss. */
    private static void onStackOf(long bytes, ThrowingRunnable body) throws Exception {
        Throwable[] failure = {null};
        Thread t = new Thread(null, () -> {
            try { body.run(); } catch (Throwable e) { failure[0] = e; }
        }, "depth-tests", bytes);
        t.start();
        t.join();
        if (failure[0] instanceof Exception e) throw e;
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    private static void depthLimitTests() throws Exception {
        check("depth: the limit is 150", LIMIT == 150);

        // ---- within the limit: compiles and runs exactly like Java
        accepted("within limit: 100 nested parentheses", parensOf(100));
        accepted("within limit: 99 '!' (odd count)", notsOf(99));
        accepted("within limit: 100 '!' (even count)", notsOf(100));
        for (String op : new String[]{"&&", "||", "^"}) accepted("within limit: chain of 100 operands with '" + op + "'", chainOf(op, 100));
        accepted("within limit: right-nested a ^ (a ^ (...)) x 50", rightNestOf(50));
        accepted("within limit: mixed !( ^ ) x 30", mixedOf(30));

        // ---- exactly at the limit (depth 150) and the deepest shapes that still fit
        accepted("exactly at limit: 149 nested parentheses (depth 150)", parensOf(LIMIT - 1));
        accepted("exactly at limit: 149 '!' (depth 150)", notsOf(LIMIT - 1));
        for (String op : ALL_OPS) accepted("exactly at limit: chain of 150 operands with '" + op + "' (depth 150)", chainOf(op, LIMIT));
        accepted("largest right-nest that fits: 74 levels (depth 149)", rightNestOf(74));
        accepted("largest mixed nest that fits: 49 levels (depth 148)", mixedOf(49));

        // ---- beyond the limit: rejected with LIMIT, at the exact token that crosses it
        rejectedAt("beyond limit: 150 nested parentheses", parensOf(LIMIT), nth(parensOf(LIMIT), "(", LIMIT));
        rejectedAt("beyond limit: 150 '!'", notsOf(LIMIT), nth(notsOf(LIMIT), "!", LIMIT));
        for (String op : ALL_OPS) {
            String src = chainOf(op, LIMIT + 1);
            rejectedAt("beyond limit: chain of 151 operands with '" + op + "'", src, nth(src, op, LIMIT));
        }
        rejectedAt("beyond limit: right-nest of 75 levels (depth 151)", rightNestOf(75), nth(rightNestOf(75), "^", 1));
        rejectedAt("beyond limit: mixed nest of 50 levels (depth 151)", mixedOf(50), nth(mixedOf(50), "!", 1));

        // line numbers across a multi-line program
        StringBuilder multi = new StringBuilder(VARS).append("boolean r = t");
        for (int i = 1; i <= LIMIT; i++) multi.append("\n&& ").append(i % 2 == 1 ? "f" : "t");
        rejectedAt("beyond limit: error line/column in a multi-line chain", multi.toString(), nth(multi.toString(), "&&", LIMIT));
        int[] lc = lineCol(multi.toString(), nth(multi.toString(), "&&", LIMIT));
        check("beyond limit: multi-line position is line 152, column 1", lc[0] == 152 && lc[1] == 1);

        // ---- long unary NOT chains and long binary chains, far beyond the limit (used to crash the JVM stack)
        rejectedAt("long NOT chain: 100000 '!'", notsOf(100000), nth(notsOf(100000), "!", LIMIT));
        rejectedAt("long NOT chain: 1000000 '!'", notsOf(1000000), nth(notsOf(1000000), "!", LIMIT));
        rejectedAt("long binary chain: 100000 operands with '&&'", chainOf("&&", 100000), nth(chainOf("&&", 100000), "&&", LIMIT));
        rejectedAt("long binary chain: 100000 operands with '^'", chainOf("^", 100000), nth(chainOf("^", 100000), "^", LIMIT));
        rejectedAt("long nesting: 100000 parentheses", parensOf(100000), nth(parensOf(100000), "(", LIMIT));
        rejectedAt("long nesting: right-nest of 100000 levels", rightNestOf(100000), nth(rightNestOf(100000), "(", LIMIT));

        // ---- the limit holds on small stacks (512 KB = half the JVM default), independent of JIT state
        String[][] atLimit = {{"149 parentheses", parensOf(149)}, {"149 '!'", notsOf(149)}, {"chain of 150 '&&'", chainOf("&&", 150)},
                {"right-nest 74", rightNestOf(74)}, {"mixed 49", mixedOf(49)}};
        for (String[] c : atLimit) {
            StackRun r = onStack(512L << 10, c[1]);
            check("small stack (512 KB): at-limit program compiles: " + c[0], r.kind().equals("OK"));
        }
        String[][] beyond = {{"100000 parentheses", parensOf(100000)}, {"100000 '!'", notsOf(100000)},
                {"chain of 100000 '&&'", chainOf("&&", 100000)}, {"right-nest 100000", rightNestOf(100000)}};
        for (String[] c : beyond) {
            StackRun r = onStack(512L << 10, c[1]);
            check("small stack (512 KB): beyond-limit program gets the parser's LIMIT error: " + c[0],
                    r.kind().equals("ERR") && r.ex().phase() == Phase.LIMIT && r.ex().line() > 0 && r.ex().detail().contains("nested too deeply"));
        }

        // ---- the compiler stays usable after rejecting deep programs
        String simple = "boolean a = true; boolean b = !a || (a ^ a); a &= b;";
        String libBefore = BoolCompiler.compile(simple);
        String standaloneBefore = BoolCompiler.compile(simple, BoolCodegen.Options.linuxStandalone());
        int rejected = 0;
        for (int i = 0; i < 20; i++) {
            for (String deep : new String[]{parensOf(100000), notsOf(100000), chainOf("||", 100000), rightNestOf(75)}) {
                try { BoolCompiler.compile(deep); } catch (BoolCompileException e) { if (e.phase() == Phase.LIMIT) rejected++; }
            }
        }
        check("recovery: all 80 deep programs were rejected with LIMIT", rejected == 80);
        check("recovery: library output after rejections is identical to before", BoolCompiler.compile(simple).equals(libBefore));
        check("recovery: standalone output after rejections is identical to before",
                BoolCompiler.compile(simple, BoolCodegen.Options.linuxStandalone()).equals(standaloneBefore));
        accepted("recovery: at-limit program still compiles and runs after rejections", chainOf("&&", LIMIT));
        exec("recovery: ordinary program still executes like Java after rejections", simple);
        try {
            BoolCompiler.compile("boolean a = true &&;");
            check("recovery: ordinary syntax errors are still reported normally", false);
        } catch (BoolCompileException e) {
            check("recovery: ordinary syntax errors are still reported normally", e.phase() == Phase.SYNTAX && e.line() == 1);
        }

        // ---- exception format
        check("exception: normal errors keep 'phase error at line:col: detail'",
                new BoolCompileException(Phase.SYNTAX, "x", 1, 2).getMessage().equals("syntax error at 1:2: x"));
        check("exception: line 0 means position unknown and is left out of the message",
                new BoolCompileException(Phase.LIMIT, "x", 0, 0).getMessage().equals("limit error: x"));
    }

    /** Compiles OK (library mode) and, where possible, runs like Java. */
    private static void accepted(String name, String src) {
        try {
            BoolCompiler.compile(src);
            check("depth: " + name + " - compiles", true);
        } catch (BoolCompileException e) {
            check("depth: " + name + " - compiles (" + e.getMessage() + ")", false);
            return;
        } catch (Throwable t) {
            check("depth: " + name + " - compiles (crashed: " + t + ")", false);
            return;
        }
        exec("depth: " + name + " - runs like Java", src);
    }

    /** Must be rejected with a LIMIT error positioned at source index {@code tokenIndex}. */
    private static void rejectedAt(String name, String src, int tokenIndex) {
        int[] want = lineCol(src, tokenIndex);
        try {
            BoolCompiler.compile(src);
            check("depth: " + name + " (was accepted!)", false);
        } catch (BoolCompileException e) {
            boolean ok = e.phase() == Phase.LIMIT && e.detail().contains("nested too deeply") && e.detail().contains("150")
                    && e.line() == want[0] && e.column() == want[1];
            check("depth: " + name + " - rejected at " + want[0] + ":" + want[1], ok);
            if (!ok) System.out.println("      got " + e.getMessage() + " (wanted " + want[0] + ":" + want[1] + ")");
        } catch (Throwable t) {
            check("depth: " + name + " (crashed: " + t + ")", false);
        }
    }

    /** 0-based index of the n-th (1-based) occurrence of {@code needle}. */
    private static int nth(String s, String needle, int n) {
        int idx = -1;
        for (int i = 0; i < n; i++) {
            idx = s.indexOf(needle, idx + 1);
            if (idx < 0) throw new IllegalStateException("fewer than " + n + " occurrences of " + needle);
        }
        return idx;
    }

    /** 1-based {line, column} of a 0-based source index. */
    private static int[] lineCol(String src, int index) {
        int line = 1, lastNewline = -1;
        for (int i = 0; i < index; i++) if (src.charAt(i) == '\n') { line++; lastNewline = i; }
        return new int[]{line, index - lastNewline};
    }

    private record StackRun(String kind, BoolCompileException ex) {}

    /** Compiles on a thread with the given stack size: kind is OK, ERR (BoolCompileException), SOE (escaped overflow) or OTHER. */
    private static StackRun onStack(long stackBytes, String src) throws Exception {
        StackRun[] result = {new StackRun("?", null)};
        Thread t = new Thread(null, () -> {
            try { BoolCompiler.compile(src); result[0] = new StackRun("OK", null); }
            catch (BoolCompileException e) { result[0] = new StackRun("ERR", e); }
            catch (StackOverflowError e) { result[0] = new StackRun("SOE", null); }
            catch (Throwable e) { result[0] = new StackRun("OTHER " + e, null); }
        }, "stack-test", stackBytes);
        t.start();
        t.join();
        return result[0];
    }

    // ------------------------------------------------------------------ StackOverflowError safety net

    /**
     * The parser limit should make a stack overflow unreachable on normal stacks, so the safety net in
     * BoolCompiler.compile is exercised by compiling programs that are WITHIN the limit on threads with
     * deliberately tiny stacks. Which sizes overflow depends on the JVM/JIT, so the assertions are:
     * an overflow must never escape; when one is triggered it must surface as a LIMIT error without position.
     * If the JVM never overflows (huge minimum stack) the test is reported as SKIPPED, not passed.
     */
    private static void safetyNetTests() throws Exception {
        String baseline = BoolCompiler.compile("boolean a = true; boolean b = a ^ !a;");
        String[] programs = {parensOf(149), mixedOf(49), chainOf("&&", 150), rightNestOf(74)};
        boolean escaped = false, malformed = false;
        int triggered = 0;
        for (long kb : new long[]{16, 32, 64, 128}) {
            for (String src : programs) {
                StackRun r = onStack(kb << 10, src);
                if (r.kind().equals("SOE") || r.kind().startsWith("OTHER")) escaped = true;
                if (r.kind().equals("ERR")) {
                    triggered++;
                    BoolCompileException e = r.ex();
                    if (e.phase() != Phase.LIMIT || e.line() != 0 || e.column() != 0 || !e.detail().contains("stack space")) malformed = true;
                }
            }
        }
        check("safety net: a StackOverflowError never escapes BoolCompiler.compile", !escaped);
        if (triggered == 0) {
            skipped++;
            System.out.println("SKIP safety net conversion (this JVM's minimum thread stack never overflowed)");
        } else {
            check("safety net: an overflow becomes a LIMIT BoolCompileException without position (" + triggered + " overflows triggered)", !malformed);
        }
        check("safety net: compiler output is unchanged after overflows", BoolCompiler.compile("boolean a = true; boolean b = a ^ !a;").equals(baseline));
    }

    // ------------------------------------------------------------------ non-executable stack marker

    private static final String NOTE = BoolCodegen.GNU_STACK_NOTE;

    private static void gnuStackNoteTests() throws Exception {
        String lib = BoolCompiler.compile("boolean a = true;");
        String standalone = BoolCompiler.compile("boolean a = true;", BoolCodegen.Options.linuxStandalone());
        check("asm: library output ends with the .note.GNU-stack section", lib.endsWith(NOTE + "\n") && count(lib, ".note.GNU-stack") >= 1 && count(lib, "\n" + NOTE) == 1);
        check("asm: standalone output ends with the .note.GNU-stack section", standalone.endsWith(NOTE + "\n") && count(standalone, "\n" + NOTE) == 1);
        check("asm: empty program still carries the .note.GNU-stack section", BoolCompiler.compile("").endsWith(NOTE + "\n"));
        check("asm: comments-off output still carries the .note.GNU-stack section",
                BoolCompiler.compile("boolean a = true;", new BoolCodegen.Options(false, false)).endsWith(NOTE + "\n"));

        if (!CAN_EXECUTE) { skipped += 2; System.out.println("SKIP object/link GNU-stack checks (needs Linux x86-64, nasm, ld)"); return; }

        Path dir = Files.createTempDirectory("rkcc-note");
        Files.writeString(dir.resolve("l.asm"), lib);
        sh(dir, "nasm", "-f", "elf64", "l.asm", "-o", "l.o");
        String sections = tryTool(dir, "readelf", "-SW", "l.o");
        if (sections == null) { skipped++; System.out.println("SKIP object-section check (readelf not available)"); }
        else check("object: assembled library output contains a .note.GNU-stack section", sections.contains(".note.GNU-stack"));

        Files.writeString(dir.resolve("s.asm"), standalone);
        sh(dir, "nasm", "-f", "elf64", "s.asm", "-o", "s.o");
        String linkOutput = sh(dir, "ld", "s.o", "-o", "s");
        check("link: standalone program links with no executable-stack warning",
                !linkOutput.contains("GNU-stack") && !linkOutput.contains("executable stack"));
    }

    // ------------------------------------------------------------------ library mode: nasm + gcc + C host

    /** Result of building and running a library-mode program from a C host. */
    private record LibRun(String stdout, String linkOutput, String stackHeader) {}

    private static void libraryIntegrationTests() throws Exception {
        if (!CAN_LINK_C || JAVAC == null) {
            skipped++;
            System.out.println("SKIP library integration tests (needs Linux x86-64, nasm, gcc, javac)");
            return;
        }
        libExec("basic declarations", "boolean a = true;\nboolean b = false;");
        libExec("&&, ||, ! on literals", "boolean c = true && false;\nboolean d = true || false;\nboolean e = !false;");
        libExec("comparisons", "boolean x = true == false;\nboolean y = true != false;");
        libExec("nested expression", "boolean result = (true && false) || !false;");
        libExec("variables", "boolean a = true;\nboolean b = false;\nboolean c = a || b;");
        libExec("assignment", "boolean flag = true;\nboolean isActive = false;\nflag = false;\nisActive = true;");
        libExec("compound &=, |=, ^=", "boolean a = true;\nboolean b = true;\nboolean c = false;\na &= false;\nb |= false;\nc ^= true;\nc ^= b;");
        libExec("short-circuit && and || with variables", "boolean t = true; boolean f = false;\nboolean r1 = f && t; boolean r2 = t && f; boolean r3 = t || f; boolean r4 = f || f; boolean r5 = (f || t) && (t && !f);");
        libExec("precedence: & ^ | and ==", "boolean t = true; boolean f = false; boolean r = f | t ^ t & f; boolean s = f == f & t; boolean u = t == t == f;");
        libExec("deeply nested with spills (push/pop heavy)",
                "boolean a = true; boolean b = false;\nboolean r = ((a ^ (b | !a)) & (!(a == b) || (a != !b))) == !(b && (a || b));\nboolean q = ((((a & b) | (a ^ b)) & ((a | b) ^ (a & !b))) == (!(a != b) | (b ^ a)));");
        libExec("tricky variable names", "boolean al = true; boolean rax = false; boolean mov = al ^ rax; boolean $x = !mov; boolean _y = $x == al; boolean bool_main = !_y; boolean rkcc_test_call = bool_main | al;");
        libExec("empty program (no variables)", "");
        libExecExpect("declared but never assigned reads as false", "boolean a; boolean b = true;", "a=0\nb=1\n");

        for (String op : new String[]{"&&", "||", "&", "|", "^", "==", "!="}) {
            libExec("truth table for '" + op + "' (all four input combinations)",
                    "boolean t = true; boolean f = false;\n"
                            + "boolean tt = t " + op + " t; boolean tf = t " + op + " f; boolean ft = f " + op + " t; boolean ff = f " + op + " f;\n"
                            + "boolean nested = (tt " + op + " tf) " + op + " !ff; boolean lit = true " + op + " false;");
        }

        // The C compiler's contract: bool_main must preserve callee-saved registers. Each run above already
        // fails (host exits 3) if rbx/rbp/r12-r15 are clobbered; this names the guarantee explicitly.
        LibRun first = null;
        try {
            first = runLibrary("boolean a = true; boolean b = (a ^ (a & !a)) == (a | !a);");
        } catch (Exception e) {
            System.out.println("      " + e.getMessage());
        }
        check("library: bool_main preserves callee-saved registers (rbx, rbp, r12-r15) when called from C",
                first != null && first.stdout().equals("a=1\nb=1\n"));
        check("library: gcc/ld link emits no executable-stack warning",
                first != null && !first.linkOutput().contains("GNU-stack") && !first.linkOutput().contains("executable stack"));
        if (first != null && first.stackHeader() == null) { skipped++; System.out.println("SKIP stack-flag check (readelf not available)"); }
        else check("library: linked program has a non-executable stack (PT_GNU_STACK is RW, not RWE)",
                first != null && !first.stackHeader().contains("RWE") && first.stackHeader().contains("RW"));

        Random rnd = new Random(4242L);
        int bad = 0;
        String firstFailure = null;
        int programs = 100;
        for (int i = 0; i < programs; i++) {
            String src = randomProgram(rnd);
            try {
                String expected = javaReference(src, variablesOf(src));
                String actual = runLibrary(src).stdout();
                if (!expected.equals(actual)) { bad++; if (firstFailure == null) firstFailure = src + "\nexpected:\n" + expected + "actual:\n" + actual; }
            } catch (Exception e) {
                bad++; if (firstFailure == null) firstFailure = src + "\n" + e;
            }
        }
        check("library: " + programs + " random programs, C-host output == Java output", bad == 0);
        if (firstFailure != null) System.out.println("      first failure:\n" + firstFailure);
    }

    private static void libExec(String name, String src) {
        try {
            String expected = javaReference(src, variablesOf(src));
            String actual = runLibrary(src).stdout();
            boolean ok = expected.equals(actual);
            check("library: " + name, ok);
            if (!ok) System.out.println("      expected:\n" + expected + "      actual:\n" + actual);
        } catch (Exception e) {
            check("library: " + name + " (" + e + ")", false);
        }
    }

    private static void libExecExpect(String name, String src, String expected) {
        try {
            check("library: " + name, expected.equals(runLibrary(src).stdout()));
        } catch (Exception e) {
            check("library: " + name + " (" + e + ")", false);
        }
    }

    /**
     * Assembles the generated LIBRARY output (plus a test-only shim) with nasm, links it with a C host
     * through gcc, and runs it. The shim lives in the test because library mode does not export its
     * variables: it (1) calls bool_main after loading rbx/rbp/r12-r15 with magic values and reports whether
     * any changed, and (2) copies every variable byte into a buffer for the C host to print.
     */
    private static LibRun runLibrary(String src) throws Exception {
        List<String> vars = variablesOf(src);
        String asm = BoolCompiler.compile(src) + librarySupportAsm(vars);
        Path dir = Files.createTempDirectory("rkcc-lib");
        Files.writeString(dir.resolve("p.asm"), asm);
        Files.writeString(dir.resolve("host.c"), hostSource(vars));
        sh(dir, "nasm", "-f", "elf64", "p.asm", "-o", "p.o");
        sh(dir, "gcc", "-O2", "-c", "host.c", "-o", "host.o");
        String linkOutput = sh(dir, "gcc", "host.o", "p.o", "-o", "host");   // no -z noexecstack: the object must carry the note itself
        String stdout = sh(dir, dir.resolve("host").toString());
        String stackHeader = null;
        String headers = tryTool(dir, "readelf", "-lW", "host");
        if (headers != null) {
            for (String line : headers.split("\n")) if (line.contains("GNU_STACK")) stackHeader = line.trim();
        }
        return new LibRun(stdout, linkOutput, stackHeader);
    }

    private static String librarySupportAsm(List<String> vars) {
        StringBuilder sb = new StringBuilder("\n; ---- test-only shim (not part of the module output) ----\nsection .text\n");
        String[] magic = {"rbx", "rbp", "r12", "r13", "r14", "r15"};
        sb.append("global rkcc_test_call\nrkcc_test_call:\n");
        for (int i = magic.length - 1; i >= 0; i--) sb.append("    push ").append(magic[i]).append('\n');
        sb.append("    sub rsp, 8\n");                                  // keep rsp 16-byte aligned at the call
        for (int i = 0; i < magic.length; i++) sb.append("    mov ").append(magic[i]).append(", 0x").append(String.valueOf(i + 1).repeat(16)).append('\n');
        sb.append("    call bool_main\n    xor eax, eax\n");
        for (int i = 0; i < magic.length; i++) {
            sb.append("    mov rcx, 0x").append(String.valueOf(i + 1).repeat(16)).append('\n');
            sb.append("    cmp ").append(magic[i]).append(", rcx\n    jne .clobbered\n");
        }
        sb.append("    jmp .done\n.clobbered:\n    mov eax, 1\n.done:\n    add rsp, 8\n");
        for (String r : magic) sb.append("    pop ").append(r).append('\n');
        sb.append("    ret\n\n");
        sb.append("global rkcc_test_dump\nrkcc_test_dump:               ; rdi = unsigned char out[]\n");
        for (int i = 0; i < vars.size(); i++) {
            sb.append("    mov al, [").append(BoolCodegen.symbol(vars.get(i))).append("]\n    mov [rdi + ").append(i).append("], al\n");
        }
        sb.append("    ret\n");
        return sb.toString();
    }

    private static String hostSource(List<String> vars) {
        StringBuilder names = new StringBuilder();
        for (String v : vars) names.append('"').append(v).append("\", ");
        return "#include <stdio.h>\n"
                + "extern int rkcc_test_call(void);\n"
                + "extern void rkcc_test_dump(unsigned char *out);\n"
                + "static const char *names[] = { " + names + "0 };\n"
                + "int main(void) {\n"
                + "    unsigned char v[" + (vars.size() + 1) + "];\n"
                + "    if (rkcc_test_call() != 0) { fputs(\"callee-saved register clobbered\\n\", stderr); return 3; }\n"
                + "    rkcc_test_dump(v);\n"
                + "    for (int i = 0; i < " + vars.size() + "; i++) printf(\"%s=%d\\n\", names[i], v[i]);\n"
                + "    return 0;\n"
                + "}\n";
    }

    /** Runs a tool and returns its output, or null if it is missing or fails. */
    private static String tryTool(Path dir, String... cmd) {
        try { return sh(dir, cmd); } catch (Exception e) { return null; }
    }

    private static boolean detectTool(String... cmd) {
        try { return new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }
}
