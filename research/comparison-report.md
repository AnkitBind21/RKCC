# RKCC Reference Projects – Comparative Architecture Report

## 1. Introduction

The purpose of this report is to compare the existing Texty Java editor, Neovim, LLVM compiler infrastructure, and JDB Java debugger.

These projects provide different architectural ideas that can guide the development of RKCC (Red King Compiler Collection).

The comparison focuses on editor architecture, file management, extensibility, compiler integration, debugging, and implementation suitability.

## 2. Reference Overview

| Project | Technology | Primary Purpose |
|---|---|---|
| Texty | Java Swing | Basic desktop text editor |
| Neovim | C, Lua | Extensible modal text editor |
| LLVM | C++ | Compiler infrastructure |
| JDB | Java / JDI | Java debugging |

These projects are not direct alternatives. Each provides a reference for a different part of RKCC.

## 3. Feature Comparison

| Feature | Texty | Neovim | LLVM | JDB |
|---|---|---|---|---|
| Text editing | Yes | Yes | No | No |
| File management | Basic structure | Yes | No | No |
| Multiple buffers | No | Yes | Not applicable | Not applicable |
| Window management | Basic Swing window | Yes | Not applicable | Not applicable |
| Plugin architecture | No | Yes | Modular libraries | Java API |
| Syntax highlighting | Not implemented | Yes | Not applicable | Not applicable |
| Compiler integration | Not implemented | External tools/plugins | Core functionality | No |
| Debugging | No | Via integrations | No | Yes |
| External API | No | RPC/API | Libraries and tools | JDI |
| Extensibility | Limited | High | High | High through JDI |

## 4. Texty Analysis

Texty is a simple Java Swing-based text editor.

### Strengths
- Simple Java desktop application structure.
- Uses standard Swing components.
- Provides a basic editor interface.
- Easy to understand and use as a beginner reference.

### Limitations
- File operations are not fully implemented.
- Cut, copy, and paste actions are incomplete.
- No buffer management.
- No tab system.
- No syntax highlighting.
- No compiler or debugger integration.
- Limited separation of responsibilities.

### Lessons for RKCC

Texty provides a basic reference for constructing a Java desktop editor, but its implementation is not sufficient for RKCC's intended functionality.

RKCC should use a more modular architecture.

## 5. Neovim Analysis

Neovim is the primary architectural reference for RKCC's editor.

Its architecture separates buffers, windows, and tabpages.

### Important Concepts

| Neovim Concept | Proposed RKCC Equivalent |
|---|---|
| Buffer | EditorBuffer |
| Window | EditorView |
| Tabpage | WorkspaceManager |
| Commands | CommandManager |
| Key mappings | ShortcutManager |
| Plugins | ExtensionManager |
| API / RPC | IntegrationManager |
| Event loop | TaskExecutor |

### Lessons for RKCC

- Separate document content from editor views.
- Allow multiple files to remain open.
- Support multiple views of the same document.
- Use commands and keyboard shortcuts consistently.
- Design an extension system.
- Keep editor functionality independent from the UI.

Neovim should guide the editor's architecture and feature organization. RKCC does not need to reproduce Neovim's complete implementation or use its C code.

## 6. LLVM Analysis

LLVM provides the compiler infrastructure reference.

Its modular architecture separates frontend processing, intermediate representation, optimization, and code generation.

### Lessons for RKCC

- Keep compiler functionality separate from the editor.
- Use a dedicated Compiler Manager.
- Support multiple compiler tools.
- Capture compiler output and errors.
- Display diagnostics inside the editor.
- Make compiler integrations independently replaceable.

LLVM itself is not a desktop editor. RKCC can invoke suitable LLVM tools externally.

## 7. JDB and JDI Analysis

JDB provides the Java debugging workflow reference, while JDI provides the API for building an integrated debugger.

### Lessons for RKCC

- Implement breakpoint management.
- Support step into, step over, and step out.
- Display local variables.
- Display call stacks.
- Handle debugging events.
- Keep debugger functionality separate from the editor UI.

For a Java-based RKCC application, JDI is the preferred integration interface.

## 8. Proposed RKCC Architecture

Based on the four references, RKCC should use a modular architecture.

```text
                    RKCC Application
                           |
        +------------------+------------------+
        |                  |                  |
    Editor UI          Workspace         Output Panel
        |                  |                  |
        v                  v                  v
  Editor Manager      File Manager      Diagnostic Manager
        |                  |                  |
        +------------------+------------------+
                           |
                    Core Services
                           |
          +----------------+----------------+
          |                |                |
    Command Manager   Extension Manager  Task Executor
          |                |                |
          +----------------+----------------+
                           |
                 Development Services
                           |
               +-----------+-----------+
               |                       |
         Compiler Manager        Debugger Manager
               |                       |
         External Tools                JDI
               |                       |
         LLVM / Clang                Target JVM
```

This is a proposed logical architecture. The actual implementation can evolve as requirements become clearer.

## 9. Recommended Technology Stack

| Layer | Proposed Technology |
|---|---|
| Programming language | Java 21 |
| Desktop UI | JavaFX |
| Rich text editor | RichTextFX |
| Build system | Maven |
| File operations | Java NIO |
| Compiler execution | Java ProcessBuilder |
| Compiler integration | Clang / LLVM tools |
| Java debugging | JDI |
| Extension configuration | JSON |
| Version control | Git |
| Testing | JUnit |

The final technology selection should be validated against the supervisor's requirements and the available development environment.

## 10. Recommended Implementation Phases

### Phase 1 – Editor Foundation
- Create the Java desktop application.
- Build the main editor window.
- Implement file creation, opening, saving, and Save As.
- Add basic editing actions.
- Support multiple open files.

### Phase 2 – Neovim-Inspired Editor Features
- Implement buffer management.
- Implement tab management.
- Separate documents from their views.
- Add keyboard shortcuts.
- Introduce a command system.

### Phase 3 – Compiler Integration
- Implement Compiler Manager.
- Execute external compiler tools.
- Capture standard output and errors.
- Display compilation diagnostics.
- Support the required programming languages.

### Phase 4 – Debugger Integration
- Integrate JDI.
- Implement breakpoints.
- Add step execution.
- Display variables and call stacks.
- Add a debugging toolbar.

### Phase 5 – Extension and Workspace Features
- Develop the VS Code extension capture script.
- Investigate extension compatibility and metadata.
- Design RKCC's extension management approach.
- Add workspace and project configuration.

## 11. Important Design Decisions

1. Neovim is the primary reference for editor architecture.
2. Texty is a basic Java Swing reference, not the final implementation model.
3. LLVM should be integrated as compiler infrastructure, not rewritten in Java.
4. JDI should be preferred over terminal-based JDB control for a graphical debugger.
5. Editor, file management, compiler, debugger, and extension functionality should remain separate modules.
6. The editor should be developed incrementally, with working features tested before adding advanced functionality.

## 12. Conclusion

The four references provide a foundation for the RKCC architecture.

Neovim provides the primary model for editor organization and extensibility. Texty provides a simple Java desktop editor reference. LLVM provides the compiler infrastructure model, and JDB/JDI provides the Java debugging model.

The recommended direction is to build RKCC as a modular Java desktop development environment, with a Neovim-inspired editor architecture and independent compiler and debugger integration layers.

The implementation should begin with a reliable file editor before introducing compiler execution, debugging, and extension management.

## 13. References

1. Texty Java Editor: https://github.com/iamrohitsuthar/TextEditor
2. Neovim Documentation: https://neovim.io/doc/
3. Neovim Source Code: https://github.com/neovim/neovim
4. LLVM Project: https://llvm.org/
5. LLVM Source Code: https://github.com/llvm/llvm-project
6. Java Platform Debugger Architecture: https://docs.oracle.com/en/java/javase/24/docs/specs/jpda/jpda.html
7. JDI API: https://docs.oracle.com/en/java/javase/24/docs/api/jdk.jdi/module-summary.html