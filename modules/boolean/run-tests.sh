#!/bin/sh
# Compile the Boolean module + tests and run them. Needs a JDK 21 (javac). Execution tests
# additionally need nasm + ld on Linux x86-64, and the library-mode integration tests need gcc;
# anything unavailable is reported as SKIPPED.
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -d out $(find src test -name '*.java')
java -cp out compiler.bool.BoolTests
