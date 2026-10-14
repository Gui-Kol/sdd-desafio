#!/usr/bin/env sh
# Wrapper da CLI (DT-005): ./reembolso.sh calcular --input X --output Y
# Compila e gera o classpath na 1a execucao; roda java direto para propagar o codigo de saida (0/1/2).
[ -d target/classes ] || mvn -q compile -DskipTests
[ -f target/cp.txt ] || mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
exec java -cp "target/classes:$(cat target/cp.txt)" com.reembolso.cli.Main "$@"