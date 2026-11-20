# RKCC Boolean module - design and technical report

Author: Ankit (Boolean parsing + x86-64 generation). Standalone; not part of the RKCC GitHub repo.
Layout follows the repo's `src/java/` convention: `src/java/compiler/bool/`, tests in `test/java/compiler/bool/`.
Requires JDK 21 (sealed types, records, pattern `switch`). No third-party dependencies.

## 1. Pipeline

```
source text -> BoolLexer -> tokens -> BoolParser -> AST -> BoolChecker -> BoolCodegen -> NASM text
              (lexical)             (syntax)       (semantic)              (x86-64)
```
Facade: `BoolCompiler.compile(source[, options])`. Each stage is its own class; errors carry phase (`LEXICAL`, `SYNTAX`, `SEMANTIC`, `LIMIT`), line, column
(`BoolCompileException`). Compilation stops at the first error.

## 2. Supported Java syntax

| Construct | Example |
|---|---|
| Literals | `true`, `false` |
| Declaration (with/without initialiser) | `boolean a = true;` / `boolean a;` |
| Assignment | `a = false;` |
| Compound assignment | `a &= b;` `a \|= b;` `a ^= b;` (expands to `a = a op b`) |
| NOT | `!a` (nestable: `!!a`) |
| Short-circuit AND / OR | `a && b`, `a \|\| b` |
| Non-short-circuit AND / OR / XOR | `a & b`, `a \| b`, `a ^ b` |
| Equality | `a == b`, `a != b` (left-associative, chainable) |
| Parentheses, nesting, variables | `(a && !b) \|\| c` |
| Comments | `// line`, `/* block */` |

Precedence (Java's, high to low): `!`, `== !=`, `&`, `^`, `|`, `&&`, `||`.

Grammar:
```
program    := statement* EOF
statement  := 'boolean' IDENT ('=' expr)? ';'  |  IDENT ('=' | '&=' | '|=' | '^=') expr ';'
expr       := logicalOr
logicalOr  := logicalAnd ('||' logicalAnd)*      logicalAnd := bitOr ('&&' bitOr)*
bitOr      := bitXor ('|' bitXor)*               bitXor     := bitAnd ('^' bitAnd)*
bitAnd     := equality ('&' equality)*           equality   := unary (('=='|'!=') unary)*
unary      := '!' unary | primary
primary    := 'true' | 'false' | IDENT | NUMBER | STRING | '(' expr ')'
```
`NUMBER`/`STRING` are parsed only to report precise type errors; they never reach code generation.

## 3. Token recognition
`Token.Type`: `BOOLEAN_KW TRUE FALSE IDENT NUMBER_LIT STRING_LIT NOT AND_AND OR_OR AMP PIPE CARET EQ_EQ NOT_EQ
ASSIGN AND_ASSIGN OR_ASSIGN XOR_ASSIGN LPAREN RPAREN SEMI UNSUPPORTED_OP RESERVED_WORD EOF`.
Maximal munch (`&&` is one token, never two `&`). `True`/`TRUE` are ordinary identifiers (Java is case-sensitive);
the checker adds a "literals are lowercase" hint. Any other Java keyword is `RESERVED_WORD`; `< > + - * / ? :` etc. are
`UNSUPPORTED_OP`. Identifiers are ASCII `[A-Za-z_$][A-Za-z0-9_$]*`.

## 4. AST (`Ast.java`, all records)
`Expr` = `BoolLit | NumberLit | StringLit | VarRef | Not | Binary(BinOp)`; `Stmt` = `Decl(name, init?) | Assign(name, AssignOp, value)`;
`Program(List<Stmt>)`. Every node has a source position; statements keep their source text for assembly comments.

## 5. Semantic rules (`BoolChecker`)
1. Variable names unique ("already defined").
2. A variable must be declared before use ("cannot find symbol").
3. A variable must be definitely assigned before it is read, including `x &= e`, `x |= e`, `x ^= e` and a self-reference
   in its own initialiser (exact for straight-line code; same wording style as javac).
4. Every initialiser/assigned value must be `boolean` ("incompatible types: number cannot be converted to boolean").
5. `!` needs a boolean operand; `&& || & | ^` need two booleans; `== !=` need two booleans
   (boolean vs non-boolean: "incomparable types"; number==number is rejected as belonging to the Integer module).
Invalid Java is never accepted silently.

### Expression depth limit (implementation limit: 150)
Valid Java may nest expressions arbitrarily deep, but the parser, checker and code generator recurse over the expression tree, so
unbounded depth would exhaust the JVM stack. The module therefore rejects expressions deeper than **150 levels** with a normal error.

*Depth* is the length of the longest path from the expression root to a leaf, counting the leaf operand and every parenthesis pair, `!`
and binary operator on the way: `true` is depth 1, `!true` depth 2, `(true)` depth 2, `a && b` depth 2.

| Shape | Accepted (depth <= 150) | Rejected (depth > 150) |
|---|---|---|
| `((( ... true ... )))` | 149 parentheses (depth 150) | 150 |
| `!!! ... true` | 149 `!` (depth 150) | 150 |
| `t op f op t op ...` (left-deep chain, any of `&& \|\| & \| ^ == !=`) | 150 operands (149 operators) | 151 operands |
| `t ^ (t ^ ( ... f))` (2 levels per step) | 74 steps (depth 149) | 75 (depth 151) |
| `!(t ^ !(t ^ ( ... f)))` (3 levels per step) | 49 steps (depth 148) | 50 (depth 151) |

- **Error:** a `BoolCompileException` with `phase() == LIMIT` whose line and column point at the token that crosses the limit
  (the 150th `(` or `!`, or the operator that makes the depth 151) and the message
  `expression is nested too deeply (limit is 150 levels; each '(' pair, '!' and binary operator adds one level)`.
  `LIMIT` means "possibly valid Java, but beyond an implementation limit"; it is distinct from `SYNTAX` and `SEMANTIC`.
- **How it is enforced:** `(` and `!` are the parser's only unbounded recursion, so the parser counts open levels top-down and stops
  descending at the limit (a 100,000-deep input is rejected after 150 levels). Operator chains are parsed in a loop but recurse later in the
  checker and code generator, so the parser records each node's depth bottom-up and rejects the operator that makes it exceed 150.
- **Why 150:** measured worst case (JIT disabled, largest frames) is about 440 nested parentheses on a 512 KB stack and about 990 on the JVM
  default 1 MB stack, so 150 leaves a safety margin of roughly 3x on a 512 KB stack. The test suite compiles the at-limit shapes (parentheses, `!`, an `&&` chain, right-nest, mixed nest) on a 512 KB stack.
- **Safety net:** `BoolCompiler.compile` also catches `StackOverflowError` and converts it into a `LIMIT` error. It is only reachable on threads
  with unusually small stacks (some JVM/JIT states overflow below roughly 256 KB even within the limit). The position is unknown, so such an
  error has line and column 0 and its message has no `at line:column` part (`limit error: program is nested too deeply for the available stack space ...`).
  All compiler state is per call, so the compiler remains fully usable afterwards (tested).

## 6. x86-64 generation (`BoolCodegen`)
- **Target:** NASM, x86-64, Intel syntax, `bits 64`, `default rel` (RIP-relative data access, works PIE or static).
- **Representation:** true=1, false=0, one byte per variable in `.data`, symbol `b_<name>` (prefix prevents clashes with
  registers/mnemonics such as `al`, `mov`, `_start`, `bool_main`).
- **Value convention:** every expression leaves exactly 0 or 1 in `al`. `rcx`/`cl` is scratch. Nothing else is touched.
- **Strategy:** literals `mov al, 0/1`; variables `mov al, [b_x]`; NOT `xor al, 1` (valid because values are 0/1);
  `& | ^` -> `and/or/xor al, <operand>`; `== !=` -> `cmp al, <operand>` + `sete/setne al`. A literal or variable on the right is
  used directly as the operand (`and al, [b_b]`); a complex right side is evaluated after `push rax`, moved to `cl`, then `pop rax`.
- **Short-circuit:** `a && b`: evaluate a; `test al, al`; `jz .Lsc_end_N`; evaluate b; label. `a || b` is the same with `jnz`.
  The right operand's code is skipped when the left decides. `&`, `|`, `^` always evaluate both sides and contain no jumps.
- **Labels:** `.L<hint>_<counter>`, NASM local labels (scoped to `bool_main`), counter guarantees uniqueness.
- **Output modes:** library (`global bool_main` ... `ret`; no libc, no syscalls) or `linuxStandalone` (adds `_start`, prints
  `name=0/1` per variable via `write`, exits via `exit`).
- **Non-executable stack marker:** both modes end with `section .note.GNU-stack noalloc noexec nowrite progbits`. Without it, `ld`/`gcc`
  warn "missing .note.GNU-stack section implies executable stack" and the linked program gets an RWE stack. This is an ELF section:
  assemble with `nasm -f elf64` (`nasm -f macho64` rejects it).
- **Library-mode variables are not exported.** They are local `.data` symbols, so a separate host cannot read results; code that
  needs them must be assembled in the same file (the library integration tests do exactly that with a test-only shim).

### Example
```java
boolean a = true;
boolean b = false;
boolean c = a && b;
boolean r = (a ^ b) == !c;
```
```asm
bits 64
default rel
section .data
b_a:         db 0
b_b:         db 0
b_c:         db 0
b_r:         db 0
section .text
global bool_main
bool_main:
    ; boolean a = true;
    mov al, 1
    mov [b_a], al
    ; boolean b = false;
    mov al, 0
    mov [b_b], al
    ; boolean c = a && b;
    mov al, [b_a]
    test al, al
    jz .Lsc_end_1            ; &&: left is false, result stays 0
    mov al, [b_b]
.Lsc_end_1:
    mov [b_c], al
    ; boolean r = (a ^ b) == !c;
    mov al, [b_a]
    xor al, [b_b]
    push rax
    mov al, [b_c]
    xor al, 1               ; logical NOT (value is 0/1)
    mov cl, al
    pop rax
    cmp al, cl
    sete al
    mov [b_r], al
    ret

; Non-executable stack marker (ELF); prevents the linker's executable-stack warning.
section .note.GNU-stack noalloc noexec nowrite progbits
```
(The two header comment lines and blank lines of the real output are omitted here. Run `java -cp out compiler.bool.BoolCompiler file.txt [--standalone]` to generate your own.)

## 7. Limitations (all deliberate; all rejected with an error, never mis-compiled)
- Only `boolean` variables; no `int`, `String`, `char`, arrays, fields, methods, classes or `main` wrapper. Input is a bare statement list.
- Not supported: ternary `?:`, assignment as an expression (`a = b = c`), multiple declarators (`boolean a, b;`), `final`/`var`,
  empty statements (`;;`), `if`/loops, method calls, unary `~`, `++`, `Boolean` wrapper type, bit operations on non-booleans.
- Relational and numeric comparison (`<`, `1 == 1`) belong to the Integer module; String `==` to the String module.
- First error only (no error recovery). ASCII identifiers only.
- Expressions deeper than 150 levels are rejected with a `LIMIT` error even though Java would accept them (see "Expression depth limit").
- No optimisation: no constant folding (`!true` still emits two instructions).
- Short-circuit behaviour is verified structurally (conditional jumps present/absent), not observationally, because the language
  subset has no side-effecting expressions; skipping vs. executing the right operand cannot change a result here.
- Output targets Linux x86-64 / ELF. Standalone mode uses Linux syscalls; both modes end with the ELF-only `.note.GNU-stack` section,
  so `nasm -f macho64` rejects the output. Only Linux x86-64 has been run (standalone via `ld`, library via a C host built with gcc).
- Not integrated with the team's shared architecture (none exists yet); see `SHARED_INTERFACE_PROPOSAL.md`.

## 8. Testing
`./run-tests.sh` (or `run-tests.ps1` on Windows; execution tests auto-skip there). Dependency-free runner `BoolTests`.
Execution tests need Linux x86-64 with `nasm` and `ld`; the library-mode integration tests also need `gcc` (and `readelf` for the
stack-flag check). Anything unavailable is reported as SKIPPED, never as passed.
- Lexer (5), parser/precedence/associativity (9), **invalid programs (45, plus 1 line/column check)**: invalid literals, missing operands, bad operator usage,
  undefined variables, type mismatches, use before assignment, unsupported syntax - each asserts phase and message.
- Assembly structure (15): short-circuit jumps present for `&&`/`||`, absent for `& | ^`, unique labels, defined jump targets,
  balanced push/pop, name mangling, and the `.note.GNU-stack` section at the end of library, standalone, empty-program and
  comments-off output.
- **Nesting-depth limit and safety net (79 tests: 57 compile/reject/position/recovery/stack tests + 21 execution-vs-Java runs + 1 multi-line position check):**
  within the limit (parentheses, odd/even `!` chains, `&& \|\| ^` chains of 100 operands, right-nest, mixed nest); exactly at the limit (149
  parentheses, 149 `!`, 150-operand chains for all 7 operators) and the deepest right-nest/mixed shapes that fit, all compiled and, where nasm/ld are
  available, executed and compared with Java; beyond the limit (150 parentheses, 150 `!`, 151-operand chains for all 7 operators, right-nest 75, mixed 50)
  rejected with `LIMIT` at the exact line and column, including a multi-line program; 100,000 to 1,000,000-deep `!` chains, binary chains and
  parentheses (these used to crash with `StackOverflowError`); at-limit and beyond-limit programs on a 512 KB thread; recovery (80 rejected deep programs,
  then identical library and standalone output, a still-correct at-limit program, an executed ordinary program, normal syntax errors still reported);
  exception message format; and the safety net (programs within the limit on 16-128 KB stacks must never leak a `StackOverflowError`, and any overflow
  must surface as a position-less `LIMIT` error; reported SKIPPED, not passed, if the JVM never overflows). The depth tests run on a thread with an
  explicit 1 MB stack, so their result does not depend on the JVM's `-Xss`.
- Object/link checks (2): the assembled object contains a `.note.GNU-stack` section; a standalone program links with no executable-stack warning.
- **Execution tests (19 hand-written + 28 truth-table programs = 47 tests, plus 1 test running 200 random programs):** generated NASM is assembled with `nasm -f elf64`,
  linked with `ld`, executed, and its printed variable values compared with the **same source compiled and run by javac/JVM**. 
  All 7 binary operators x all 4 input combinations x literal/variable/nested forms are covered.
- **Library-mode integration tests (24):** the library output (`bool_main`, ending in `ret`) is assembled with `nasm -f elf64`, linked with
  a small generated C host through plain `gcc` (no `-z noexecstack`), executed, and the variable values it prints are compared with real Java.
  Because library mode does not export its variables, each run appends a test-only assembly shim that (1) loads `rbx`, `rbp`, `r12`-`r15`
  with magic values, calls `bool_main` and fails the run if any changed (System V callee-saved contract), and (2) copies the variables into a
  buffer for the host. Covered: 13 hand-written programs (incl. empty program, uninitialised variable, push/pop-heavy nesting, names such as
  `al`, `rax`, `bool_main`), a truth table for each of the 7 operators, 100 random programs, the callee-saved check, "no executable-stack
  warning from the link", and "PT_GNU_STACK is RW, not RWE".
- Mutation check (manual, not scripted): deliberately breaking `||` jump polarity, `^` encoding and `!=` encoding made 11, 6 and 7 tests fail
  respectively. Dropping the `.note.GNU-stack` line made 7 tests fail; making code generation clobber `rbx` (results still correct) made only
  library-integration tests fail (11), which confirms the callee-saved check is effective.

- Mutation check of the depth limit (manual, not scripted): `>` to `>=` in the bottom-up check, `>=` to `>` in the top-down guard, removing the top-down
  guard, removing chain-depth tracking, and removing the `StackOverflowError` catch made 26, 6, 9, 14 and 1 tests fail respectively.

Last run (Linux x86-64, NASM 2.16.01, GNU ld 2.42, gcc, OpenJDK 21): **228 passed, 0 failed, 0 skipped** (149 earlier tests + 79 new).
The suite was also run unchanged with `java -Xint`, `-XX:TieredStopAtLevel=1`, `-Xss256k` and `-Xss512k`: 228 passed, 0 failed, 0 skipped each time.
