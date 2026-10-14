@echo off
rem Wrapper da CLI (DT-005): reembolso calcular --input X --output Y
rem Compila e gera o classpath na 1a execucao; roda java direto para propagar o codigo de saida (0/1/2).
if not exist target\classes call mvn -q compile -DskipTests
if not exist target\cp.txt call mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
set /p CP=<target\cp.txt
java -cp "target\classes;%CP%" com.reembolso.cli.Main %*
exit /b %ERRORLEVEL%