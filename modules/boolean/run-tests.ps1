# Compile the Boolean module + tests and run them (Windows). Needs JDK 21 on PATH.
# nasm/ld/gcc execution and library-integration tests are skipped automatically on Windows; compile/parse/semantic/structure tests still run.
Set-Location $PSScriptRoot
if (Test-Path out) { Remove-Item out -Recurse -Force }
New-Item -ItemType Directory out | Out-Null
$files = Get-ChildItem -Recurse -Filter *.java src, test | ForEach-Object { $_.FullName }
javac -d out $files
if ($LASTEXITCODE -ne 0) { exit 1 }
java -cp out compiler.bool.BoolTests
exit $LASTEXITCODE
