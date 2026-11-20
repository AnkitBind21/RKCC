# Proposal: common compiler architecture (for discussion with Integer and String owners)

Status: **PROPOSAL ONLY. Nothing here is implemented, nothing was pushed to the RKCC repo.**
The Boolean module (`boolean-module/`) is standalone and has its own private lexer/AST. This document
describes the smallest shared contract that would let it, the Integer module and the String module be
combined later, and what each owner would have to agree to.

## Why a contract is needed
The assignment ("an ABSTRACT class to parse Java tokens ... and output general x86 assembly") needs one
driver that walks a source file and hands each construct to the module that owns its type. The hard part is
**mixed expressions**, which no single module can handle alone:

| Expression | Operator owned by | Result type |
|---|---|---|
| `boolean b = x < 3;` | Integer | boolean |
| `boolean b = s1 == s2;` | String | boolean |
| `boolean b = (x < 3) && flag;` | Boolean (consuming an Integer result) | boolean |
| `if`/loop conditions (future) | Boolean | boolean |

## Proposed pieces

1. **One shared token type and lexer** (`Token`, `TokenType`) covering all Java tokens. Modules do not lex.
2. **One shared symbol table** mapping name -> (`JavaType`, storage label, definitely-assigned flag).
3. **One abstract class per type module**, minimal and four-method:

```java
public abstract class TypeModule {
    /** The Java type this module owns, e.g. BOOLEAN, INT, STRING. */
    public abstract JavaType type();

    /** Can this module parse a statement starting at the cursor? (declaration/assignment of its type) */
    public abstract boolean startsStatement(TokenStream in, SymbolTable symbols);

    /** Parse one statement or expression into the shared AST. Throw CompileException with position on error. */
    public abstract Stmt parseStatement(TokenStream in, ParseContext ctx) throws CompileException;

    /** Validate types / definite assignment; may call ctx.typeOf(expr) for sub-expressions of other types. */
    public abstract void check(Stmt s, SymbolTable symbols) throws CompileException;

    /** Emit NASM x86-64 (Intel). Value convention below. */
    public abstract void emit(Stmt s, AsmWriter out, EmitContext ctx);
}
```

4. **One driver** (`Compiler`) that owns the loop: lex once, ask each module `startsStatement`, delegate,
   collect `.data`, emit `.text`, hand out unique labels via `AsmWriter.newLabel()`.
5. **Expression delegation** through `ParseContext.parseExpression()`: the driver's precedence parser asks
   the module that owns each operator. Integer owns `< > <= >= + - * / %` and `==`/`!=` between ints; String
   owns `==`/`!=`/`+` between Strings; Boolean owns `! && || & | ^` and `==`/`!=` between booleans.

## Calling convention for generated code (must be identical across modules)
- Target: **NASM, x86-64, Intel syntax**, `bits 64`, `default rel`.
- Expression result in **`rax`** (booleans: **`al`**, always exactly 0/1; integers: `rax`; strings: pointer in `rax`).
- Scratch: `rcx`, `rdx`. Saving a value across a sub-expression: `push rax` / `pop`; stack balanced.
- Variables: `.data`, symbol `<prefix>_<javaName>` (Boolean uses `b_`, so Integer could use `i_`, String `s_`).
- Local labels `.L<hint>_<n>` from one shared counter so modules cannot collide.
- Each emitted assembly file ends with `section .note.GNU-stack noalloc noexec nowrite progbits` (ELF; assemble with `-f elf64`), otherwise
  linking produces an executable-stack warning and an RWE stack. The Boolean module already does this; the final merged output must emit it once.
- Comparisons that yield a boolean end with `setcc al` (or equivalent) so Boolean can consume them directly.

## What the Boolean module will adapt
Its current classes map 1:1: `BoolLexer`->shared lexer (deleted), `Ast.*`->shared AST nodes (kept as the
Boolean node set), `BoolParser`->`BooleanModule.parseStatement`, `BoolChecker`->`check`, `BoolCodegen`->`emit`.
No behaviour change is expected; only constructor/signature changes.

## Questions for the team
1. Is a shared lexer acceptable, or must each module lex its own subset?
2. Who owns `==`/`!=` when operand types differ (error) - proposal: the driver reports "incomparable types".
3. Register convention above: acceptable to Integer and String owners?
4. Source layout: `src/java/compiler/...` (matching the existing `src/java/editor/`) with packages `compiler`,
   `compiler.bool`, `compiler.integer`, `compiler.string`?
5. Output mode: library routine (`bool_main`, `ret`) or full program with `_start`? Boolean supports both.
