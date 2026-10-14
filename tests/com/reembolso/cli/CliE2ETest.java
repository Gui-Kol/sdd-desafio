package com.reembolso.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-015 — CLI `calcular --input --output` (args a mao, DT-005).
 * Criterios de aceite (ponta a ponta, processo real):
 *  - roda o wrapper com `--input exemplos/despesas-exemplo.json --output out.json`
 *    -> arquivo criado, codigo de saida 0;
 *  - uso errado -> codigo de saida 2 com ajuda.
 */
class CliE2ETest {

    private record ResultadoCli(int codigo, String stdout, String stderr) {
    }

    private ResultadoCli rodarCli(String... args) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> comando = new java.util.ArrayList<>();
        comando.add(windows ? "cmd" : "sh");
        comando.addAll(windows
                ? List.of("/c", "reembolso.cmd")
                : List.of("reembolso.sh"));
        comando.addAll(List.of(args));

        Process processo = new ProcessBuilder(comando).directory(Path.of(".").toFile()).start();
        String stdout = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(processo.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(processo.waitFor(180, TimeUnit.SECONDS), "CLI nao terminou em 180s");
        return new ResultadoCli(processo.exitValue(), stdout, stderr);
    }

    @Test
    @DisplayName("cli_calcularComExemplo_saiZeroEEscreveJson")
    void cli_calcularComExemplo_saiZeroEEscreveJson(@TempDir Path tempDir) throws Exception {
        Path saida = tempDir.resolve("out.json");

        ResultadoCli r = rodarCli("calcular",
                "--input", "exemplos/despesas-exemplo.json",
                "--output", saida.toString());

        assertEquals(0, r.codigo(), "sucesso deve sair 0. stderr: " + r.stderr());
        assertTrue(Files.exists(saida), "arquivo de saida deve ser criado: " + saida);
        String json = Files.readString(saida, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"colaborador_id\""), "saida no formato da spec §4: " + json);
        assertTrue(json.contains("\"total_reembolsado\""));
        assertTrue(json.contains("\"despesas\""));
    }

    @Test
    @DisplayName("cli_argumentosEmQualquerOrdem_saiZero")
    void cli_argumentosEmQualquerOrdem_saiZero(@TempDir Path tempDir) throws Exception {
        Path saida = tempDir.resolve("out2.json");

        ResultadoCli r = rodarCli("calcular",
                "--output", saida.toString(),
                "--input", "exemplos/despesas-exemplo.json");

        assertEquals(0, r.codigo(), "stderr: " + r.stderr());
        assertTrue(Files.exists(saida));
    }

    @Test
    @DisplayName("cli_usoErrado_saiDoisComAjuda")
    void cli_usoErrado_saiDoisComAjuda(@TempDir Path tempDir) throws Exception {
        // sem subcomando
        ResultadoCli semArgs = rodarCli();
        assertEquals(2, semArgs.codigo(), "erro de uso deve sair 2");
        assertTrue(semArgs.stderr().contains("Uso: reembolso calcular --input"),
                "ajuda obrigatoria no erro de uso: " + semArgs.stderr());

        // subcomando desconhecido
        ResultadoCli subErrado = rodarCli("listar");
        assertEquals(2, subErrado.codigo());
        assertTrue(subErrado.stderr().contains("Uso:"));

        // falta --output
        ResultadoCli faltaOutput = rodarCli("calcular", "--input", "exemplos/despesas-exemplo.json");
        assertEquals(2, faltaOutput.codigo());
        assertTrue(faltaOutput.stderr().contains("Uso:"));

        // flag desconhecida
        ResultadoCli flagDesconhecida = rodarCli("calcular", "--input", "a.json", "--output", "b.json", "--x");
        assertEquals(2, flagDesconhecida.codigo());
        assertTrue(flagDesconhecida.stderr().contains("Uso:"));
    }

    @Test
    @DisplayName("cli_entradaInexistente_saiUmComMensagemLegivel")
    void cli_entradaInexistente_saiUmComMensagemLegivel(@TempDir Path tempDir) throws Exception {
        ResultadoCli r = rodarCli("calcular",
                "--input", "nao-existe.json",
                "--output", tempDir.resolve("x.json").toString());

        assertEquals(1, r.codigo(), "erro de processamento deve sair 1. stderr: " + r.stderr());
        assertTrue(r.stderr().contains("Erro:"), "mensagem legivel para o operador: " + r.stderr());
    }
}
