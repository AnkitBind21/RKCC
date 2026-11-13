# JDB and Java Debugger Architecture Analysis

## 1. Introduction

JDB (Java Debugger) is a command-line debugging tool included with the Java Development Kit (JDK). It allows developers to inspect and control the execution of Java applications.

JDB supports essential debugging operations such as:
- Setting and removing breakpoints.
- Executing programs step by step.
- Inspecting local variables.
- Viewing call stacks.
- Continuing and suspending execution.
- Investigating exceptions.

For RKCC, JDB is a useful reference for understanding Java debugging workflows. However, a graphical editor should ideally integrate with the Java Debug Interface (JDI) rather than simply embedding the JDB terminal.

## 2. Java Platform Debugger Architecture (JPDA)

Java debugging is based on the Java Platform Debugger Architecture (JPDA).

JPDA consists of three main layers:

| Layer | Responsibility |
|---|---|
| JVM TI | Provides debugging services inside the Java Virtual Machine |
| JDWP | Defines communication between the debugger and the target JVM |
| JDI | Provides a high-level Java API for implementing debugger applications |

### 2.1 Architecture Diagram

```text
          RKCC Debugger UI
                  |
                  v
           Debugger Manager
                  |
                  v
          JDI (Java API)
                  |
                  v
          JDWP Communication
                  |
                  v
           JVM TI Backend
                  |
                  v
          Target Java Program
                  |
                  v
                 JVM
```

The debugger frontend sends requests to the target JVM and receives events and execution information.

JDI is the recommended high-level interface for developing Java debugger applications.

## 3. JDB Command-Line Debugger

JDB provides an interactive command-line environment for debugging Java programs.

Common commands include:

| Command | Description |
|---|---|
| `run` | Starts the application |
| `stop at ClassName:Line` | Sets a breakpoint at a line |
| `stop in ClassName.method` | Sets a method breakpoint |
| `clear` | Removes a breakpoint |
| `step` | Executes the next source line, entering method calls |
| `next` | Executes the next source line without stepping into method calls |
| `cont` | Continues execution |
| `locals` | Displays local variables |
| `print variable` | Displays a variable's value |
| `where` | Displays the current call stack |
| `threads` | Lists application threads |
| `suspend` | Suspends execution |
| `resume` | Resumes execution |
| `exit` | Exits the debugger |

These commands demonstrate the basic operations that an integrated debugger should expose through a graphical interface.

## 4. Debugging Workflow

A typical Java debugging workflow is:

```text
Open Java Project
       |
       v
Compile Java Source
       |
       v
Start Debug Session
       |
       v
Set Breakpoints
       |
       v
Launch Target JVM
       |
       v
Execution Reaches Breakpoint
       |
       v
Inspect Variables and Stack
       |
       v
Step / Next / Continue
       |
       v
Terminate Debug Session
```

### 4.1 Breakpoint Management

A breakpoint pauses execution when the program reaches a specified location or method.

An integrated editor should support:
- Adding breakpoints by clicking the editor gutter.
- Removing existing breakpoints.
- Enabling and disabling breakpoints.
- Displaying breakpoint markers.
- Navigating to the source line where execution stopped.

### 4.2 Step Execution

Three important operations are:

- **Step Into:** Enter the method being called.
- **Step Over:** Execute the current line without entering the called method.
- **Step Out:** Continue until the current method returns.

The editor should visually indicate the current execution line.

### 4.3 Variable Inspection

When execution is suspended, the debugger can inspect:
- Local variables.
- Method arguments.
- Object fields.
- Primitive values.
- Object references.

RKCC can display this information in a Variables panel.

### 4.4 Call Stack

The call stack represents the sequence of method calls leading to the current execution point.

A graphical debugger should display stack frames and allow the developer to navigate between them where supported.

## 5. Java Debug Interface (JDI)

JDI is a high-level Java API for building debugger applications.

It provides programmatic access to a running JVM and supports:
- Launching or connecting to target JVMs.
- Setting breakpoints.
- Inspecting variables and objects.
- Suspending and resuming threads.
- Reading stack frames.
- Receiving debugging events.
- Controlling execution.

Important JDI packages include:

| Package | Purpose |
|---|---|
| `com.sun.jdi` | Core debugger interfaces and virtual machine access |
| `com.sun.jdi.connect` | Connecting to and launching target JVMs |
| `com.sun.jdi.event` | Debugging events |
| `com.sun.jdi.request` | Creating event and breakpoint requests |

The `jdk.jdi` module is included in the JDK and provides the Java Debug Interface.

## 6. Debugging Events

JDI uses events to notify the debugger about changes in the target application's execution.

Examples include:
- Breakpoint events.
- Step events.
- Exception events.
- Thread start and termination events.
- Class preparation events.
- VM start and termination events.

A debugger can use an event loop to process these notifications and update its interface.

For example, when a breakpoint event is received, RKCC can:
1. Suspend or inspect the relevant execution thread.
2. Retrieve the current stack frame.
3. Read available local variables.
4. Highlight the corresponding source line.
5. Update the debugger panels.

## 7. RKCC Debugger Architecture

RKCC can implement a dedicated Debugger Manager responsible for coordinating debugging operations.

A proposed architecture is:

```text
                 RKCC Editor
                      |
                      v
               Debugger Manager
                      |
        +-------------+-------------+
        |             |             |
   Breakpoint      Execution     Variable
    Manager         Control       Inspector
        |             |             |
        +-------------+-------------+
                      |
                      v
                     JDI
                      |
                      v
                 Target JVM
                      |
                      v
               Debugging Events
                      |
                      v
               Debugger UI Update
```

### 7.1 Debugger Manager

Responsibilities:
- Start and stop debugging sessions.
- Launch or attach to a target JVM.
- Coordinate debugger operations.
- Manage the active debugging state.
- Handle debugger errors and termination.

### 7.2 Breakpoint Manager

Responsibilities:
- Store breakpoint locations.
- Create and remove breakpoint requests.
- Synchronize breakpoints with the editor gutter.
- Track breakpoint status.

### 7.3 Execution Controller

Responsibilities:
- Step into.
- Step over.
- Step out.
- Continue execution.
- Suspend and resume execution.
- Terminate debugging sessions.

### 7.4 Variable Inspector

Responsibilities:
- Retrieve local variables.
- Display variable values.
- Inspect object fields.
- Refresh values when execution stops.

### 7.5 Debugger UI

The RKCC interface should contain:

| UI Component | Purpose |
|---|---|
| Editor Gutter | Breakpoint markers |
| Execution Marker | Highlights the current line |
| Debug Toolbar | Start, stop, step and continue |
| Variables Panel | Displays variable values |
| Call Stack Panel | Displays method call hierarchy |
| Debug Console | Displays debugger messages |
| Threads Panel | Displays application threads |

## 8. Integrating JDI with a Java Application

Since RKCC is planned as a Java application, it can use JDI directly through the `jdk.jdi` module.

A simplified example of obtaining a launching connector is:

```java
import com.sun.jdi.Bootstrap;
import com.sun.jdi.connect.LaunchingConnector;

public class DebuggerExample {
    public static void main(String[] args) {
        LaunchingConnector connector =
            Bootstrap.virtualMachineManager()
                     .defaultConnector();

        System.out.println(
            "Connector: " + connector.name()
        );
    }
}
```

This example only retrieves the default launching connector. It does not yet launch or debug a program.

A complete implementation must configure the connector, launch the target JVM, create breakpoint requests, and process debugging events.

## 9. JDB versus JDI

| Feature | JDB | JDI |
|---|---|---|
| Type | Command-line debugger | Java debugging API |
| Interaction | Text commands | Programmatic interfaces |
| Breakpoints | Supported | Supported |
| Step execution | Supported | Supported |
| Variable inspection | Supported | Supported |
| GUI integration | Requires additional work | Suitable for custom debugger UIs |
| Customization | Limited compared with an API | Highly extensible |
| RKCC application | Useful for initial testing | Recommended for integration |

JDB is useful for learning and validating debugging workflows. JDI is more appropriate for developing an integrated debugger.

## 10. Implementation Recommendations for RKCC

The recommended implementation strategy is incremental.

**Phase 1 – Basic Java execution**
- Compile Java files.
- Execute Java programs.
- Capture standard output and errors.
- Display execution status.

**Phase 2 – Basic debugging**
- Launch a target JVM through JDI.
- Add and remove breakpoints.
- Support continue and stop.
- Implement step into, step over and step out.

**Phase 3 – Debugger interface**
- Add breakpoint markers in the editor.
- Highlight the current execution line.
- Add Variables and Call Stack panels.
- Display debugging events.

**Phase 4 – Advanced debugging**
- Inspect object fields.
- Support multiple threads.
- Handle exceptions.
- Support attaching to an existing JVM.
- Improve session management and error handling.

## 11. Testing Strategy

The debugger should be tested using Java programs that include:
- Simple arithmetic operations.
- Multiple method calls.
- Loops and conditional statements.
- Local and instance variables.
- Arrays and objects.
- Multiple threads.
- Handled and unhandled exceptions.

Important test cases include:
- Breakpoint reached at the expected line.
- Step Into enters a method.
- Step Over skips method internals.
- Step Out returns to the calling method.
- Variable values update correctly.
- Continue resumes execution.
- Debugger exits cleanly after program termination.

## 12. Conclusion

JDB provides a practical reference for understanding Java debugging commands and workflows. JPDA explains the underlying architecture, while JDI provides the programmatic interface required to develop integrated debugging tools.

For RKCC, a Java-based Debugger Manager using JDI is the recommended long-term approach. It can connect the editor with the target JVM and provide breakpoints, step execution, variable inspection, call-stack navigation, and debugging events.

This architecture keeps the debugger separate from the editor interface and allows future improvements without redesigning the entire application.

## 13. References

1. OpenJDK JDB Documentation: https://github.com/openjdk/jdk/blob/master/src/jdk.jdi/share/man/jdb.md
2. Java Platform Debugger Architecture: https://docs.oracle.com/en/java/javase/24/docs/specs/jpda/jpda.html
3. JPDA Architecture: https://docs.oracle.com/en/java/javase/24/docs/specs/jpda/architecture.html
4. JDI API Documentation: https://docs.oracle.com/en/java/javase/18/docs/api/jdk.jdi/module-summary.html
5. JDI Overview: https://docs.oracle.com/javase/8/docs/jdk/api/jpda/jdi/overview-summary.html