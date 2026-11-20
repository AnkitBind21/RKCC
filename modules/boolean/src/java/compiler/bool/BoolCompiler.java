package compiler.bool;

import compiler.bool.BoolCompileException.Phase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Facade: source text in, NASM text out. Also a tiny command-line entry point. */
public final class BoolCompiler {
    private BoolCompiler() {}

    public static String compile(String source) throws BoolCompileException {
        return compile(source, BoolCodegen.Options.library());
    }

    public static String compile(String source, BoolCodegen.Options options) throws BoolCompileException {
        try {
            Ast.Program program = BoolParser.parse(source);               // lexical + syntax (enforces the depth limit)
            BoolChecker.Result checked = BoolChecker.check(program);      // semantic
            return BoolCodegen.generate(checked.program(), checked.variables(), options);
        } catch (StackOverflowError e) {
            // Safety net only: the parser's depth limit should make this unreachable, but a thread with an unusually
            // small stack could still run out. All compiler state is per call, so the compiler stays usable.
            throw new BoolCompileException(Phase.LIMIT, "program is nested too deeply for the available stack space "
                    + "(normal limit: " + BoolParser.MAX_EXPRESSION_DEPTH + " levels)", 0, 0);
        }
    }

    /** Usage: java compiler.bool.BoolCompiler File.txt [--standalone] */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: BoolCompiler <file> [--standalone]");
            System.exit(2);
        }
        boolean standalone = args.length > 1 && args[1].equals("--standalone");
        try {
            String src = Files.readString(Path.of(args[0]));
            System.out.print(compile(src, new BoolCodegen.Options(standalone, true)));
        } catch (BoolCompileException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
