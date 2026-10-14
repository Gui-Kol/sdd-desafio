package com.reembolso.cli;

import java.io.PrintStream;
import java.nio.file.Path;

import com.reembolso.io.Entrada;
import com.reembolso.io.EscritorSaida;
import com.reembolso.io.Ingestao;
import com.reembolso.materializacao.Saida;
import com.reembolso.motor.MotorReembolso;

/**
 * CLI do motor de reembolso (T-015, DT-005).
 *
 * <p>Interface fixa do enunciado: {@code calcular --input X --output Y}.
 * Parser feito a mao (20 linhas): a assinatura e fixa e tem exatos 2 flags —
 * um parser de CLI inteiro seria solucao procurando problema (plan.md).
 *
 * <p>Codigos de saida: 0 = sucesso; 1 = erro de processamento (arquivo ilegivel,
 * JSON malformado); 2 = erro de uso (imprime ajuda).
 */
public final class Main {

    static final String AJUDA = """
            Uso: reembolso calcular --input <arquivo.json> --output <arquivo.json>
              calcular   subcomando que processa o arquivo de despesas
              --input    caminho do JSON de entrada (formato da spec §4)
              --output   caminho do JSON de saida (criado ou sobrescrito)
            Codigos de saida: 0 = sucesso; 1 = erro de processamento; 2 = erro de uso.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(executar(args, System.out, System.err));
    }

    /** Nucleo testavel: executa e devolve o codigo de saida, sem encerrar a JVM. */
    static int executar(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !"calcular".equals(args[0])
                || "--help".equals(args[0]) || "-h".equals(args[0])) {
            err.print(AJUDA);
            return 2;
        }
        String input = null;
        String output = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> {
                    if (input != null || ++i >= args.length) {
                        err.print(AJUDA);
                        return 2;
                    }
                    input = args[i];
                }
                case "--output" -> {
                    if (output != null || ++i >= args.length) {
                        err.print(AJUDA);
                        return 2;
                    }
                    output = args[i];
                }
                default -> {
                    err.print(AJUDA);
                    return 2;
                }
            }
        }
        if (input == null || output == null) {
            err.print(AJUDA);
            return 2;
        }
        try {
            Entrada entrada = Ingestao.ler(Path.of(input));
            Saida saida = Saida.de(entrada, new MotorReembolso().processar(entrada));
            EscritorSaida.escrever(saida, Path.of(output));
            return 0;
        } catch (RuntimeException e) {
            err.println("Erro: " + e.getMessage());
            return 1;
        }
    }
}
