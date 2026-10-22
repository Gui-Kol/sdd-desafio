package com.reembolso.cli;

import java.io.PrintStream;
import java.nio.file.Path;

import com.reembolso.io.Cambio;
import com.reembolso.io.Entrada;
import com.reembolso.io.EscritorSaida;
import com.reembolso.io.Ingestao;
import com.reembolso.materializacao.Saida;
import com.reembolso.motor.MotorReembolso;
import com.reembolso.motor.Politica;

/**
 * CLI do motor de reembolso (T-015; flags de política e câmbio na T-022, D-003).
 *
 * <p>Interface: {@code calcular --input <despesas.json> --politica <politica.json>
 * --output <resultado.json> [--cambio <cambio.json>]}. Parser feito à mão
 * (DT-005): 4 flags — três obrigatórias e o câmbio opcional (spec §4: sem a
 * tabela, despesa em moeda estrangeira é recusada como "Moeda sem cotação
 * disponível", RN-013). A T-022 substituiu a ponte v3-como-dado da T-019 pela
 * política informada no arquivo — a política é dado, nunca código.
 *
 * <p>Códigos de saída: 0 = sucesso; 1 = erro de processamento (arquivo
 * ilegível, JSON malformado, vigência da política, periodicidade inválida);
 * 2 = erro de uso (imprime ajuda).
 */
public final class Main {

    static final String AJUDA = """
            Uso: reembolso calcular --input <despesas.json> --politica <politica.json> --output <resultado.json> [--cambio <cambio.json>]
              calcular    subcomando que processa o arquivo de despesas
              --input     caminho do JSON de despesas (formato da spec §4)
              --politica  caminho do JSON da política v4 (spec §4; obrigatório)
              --cambio    caminho do JSON da tabela de câmbio (spec §4; opcional —
                          sem ele, despesa em moeda estrangeira é recusada como
                          "Moeda sem cotação disponível", RN-013)
              --output    caminho do JSON de saída (criado ou sobrescrito)
            Códigos de saída: 0 = sucesso; 1 = erro de processamento; 2 = erro de uso.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(executar(args, System.out, System.err));
    }

    /** Núcleo testável: executa e devolve o código de saída, sem encerrar a JVM. */
    static int executar(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || !"calcular".equals(args[0])
                || "--help".equals(args[0]) || "-h".equals(args[0])) {
            err.print(AJUDA);
            return 2;
        }
        String input = null;
        String output = null;
        String politica = null; // T-022: obrigatória (spec §4)
        String cambio = null;   // T-022: opcional (RN-013)
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> {
                    if (input != null || ++i >= args.length) {
                        err.print(AJUDA);
                        return 2;
                    }
                    input = args[i];
                }
                case "--politica" -> {
                    if (politica != null || ++i >= args.length) {
                        err.print(AJUDA);
                        return 2;
                    }
                    politica = args[i];
                }
                case "--cambio" -> {
                    if (cambio != null || ++i >= args.length) {
                        err.print(AJUDA);
                        return 2;
                    }
                    cambio = args[i];
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
        if (input == null || output == null || politica == null) {
            err.print(AJUDA);
            return 2;
        }
        try {
            Entrada entrada = Ingestao.ler(Path.of(input));
            Politica politicaVigente = Politica.de(Ingestao.lerPolitica(Path.of(politica)));
            Cambio tabelaCambio = cambio == null ? null : Ingestao.lerCambio(Path.of(cambio));
            Saida saida = Saida.de(entrada,
                    new MotorReembolso().processar(entrada, politicaVigente, tabelaCambio));
            EscritorSaida.escrever(saida, Path.of(output));
            return 0;
        } catch (RuntimeException e) {
            err.println("Erro: " + e.getMessage());
            return 1;
        }
    }
}
