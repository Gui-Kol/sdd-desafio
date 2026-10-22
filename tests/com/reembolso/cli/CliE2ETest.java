package com.reembolso.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * T-015 — CLI `calcular --input --output` (args a mao, DT-005).
 * T-022 — CLI com `--politica` (obrigatoria) e `--cambio` (opcional), D-003.
 * Criterios de aceite (ponta a ponta, processo real):
 *  - envelope com --politica/--cambio -> codigo 0, arquivo com o dourado da spec §9;
 *  - sem --politica -> codigo 2 com ajuda;
 *  - sem --cambio com moeda estrangeira -> lote processado, itens recusados
 *    por cotacao (RN-013);
 *  - uso errado -> codigo de saida 2 com ajuda.
 */
class CliE2ETest {

    /** Le a saida preservando a escala decimal ("178.80" nao vira 178.8 — RN-010). */
    private static final ObjectMapper LEITOR = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));

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
                "--politica", "exemplos/politica-v4.json",
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
                "--politica", "exemplos/politica-v4.json",
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
        ResultadoCli faltaOutput = rodarCli("calcular",
                "--input", "exemplos/despesas-exemplo.json",
                "--politica", "exemplos/politica-v4.json");
        assertEquals(2, faltaOutput.codigo());
        assertTrue(faltaOutput.stderr().contains("Uso:"));

        // falta --politica (obrigatória desde a T-022, D-003)
        ResultadoCli faltaPolitica = rodarCli("calcular",
                "--input", "exemplos/despesas-exemplo.json",
                "--output", tempDir.resolve("p.json").toString());
        assertEquals(2, faltaPolitica.codigo());
        assertTrue(faltaPolitica.stderr().contains("Uso:"));

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
                "--politica", "exemplos/politica-v4.json",
                "--output", tempDir.resolve("x.json").toString());

        assertEquals(1, r.codigo(), "erro de processamento deve sair 1. stderr: " + r.stderr());
        assertTrue(r.stderr().contains("Erro:"), "mensagem legivel para o operador: " + r.stderr());
    }

    @Test
    @DisplayName("cli_envelopeComPoliticaECambio_saiZeroEEscreveDourado")
    void cli_envelopeComPoliticaECambio_saiZeroEEscreveDourado(@TempDir Path tempDir) throws Exception {
        // T-022 / spec §9: os tres arquivos do envelope pelo processo real
        Path saida = tempDir.resolve("envelope.json");

        ResultadoCli r = rodarCli("calcular",
                "--input", "exemplos/despesas-envelope.json",
                "--politica", "exemplos/politica-v4.json",
                "--cambio", "exemplos/cambio.json",
                "--output", saida.toString());

        assertEquals(0, r.codigo(), "sucesso deve sair 0. stderr: " + r.stderr());
        assertTrue(Files.exists(saida));

        JsonNode raiz = LEITOR.readTree(Files.readString(saida, StandardCharsets.UTF_8));
        assertEquals(new BigDecimal("2457.52"), raiz.get("total_solicitado").decimalValue(),
                "total_solicitado dourado do envelope (spec §9)");
        assertEquals(new BigDecimal("1143.26"), raiz.get("total_reembolsado").decimalValue(),
                "total_reembolsado dourado do envelope (spec §9)");

        JsonNode e004 = despesa(raiz, "e-004");
        assertEquals("parcial", e004.get("status").asText());
        assertEquals(new BigDecimal("178.80"), e004.get("valor_solicitado").decimalValue());
        assertEquals(new BigDecimal("5.96"), e004.get("taxa_cambio").decimalValue(),
                "sabado sem cotacao: taxa da ultima publicada (2026-07-17)");
        assertEquals("2026-07-17", e004.get("data_taxa").asText());
    }

    @Test
    @DisplayName("cli_semCambio_comMoedaEstrangeira_recusaPorCotacao")
    void cli_semCambio_comMoedaEstrangeira_recusaPorCotacao(@TempDir Path tempDir) throws Exception {
        // spec §4: cambio e entrada opcional — sem a tabela o lote corre, e as
        // despesas em moeda estrangeira sao recusadas por cotacao (RN-013/AMB-017)
        Path saida = tempDir.resolve("sem-cambio.json");

        ResultadoCli r = rodarCli("calcular",
                "--input", "exemplos/despesas-envelope.json",
                "--politica", "exemplos/politica-v4.json",
                "--output", saida.toString());

        assertEquals(0, r.codigo(), "sem --cambio o lote e processado. stderr: " + r.stderr());
        JsonNode raiz = LEITOR.readTree(Files.readString(saida, StandardCharsets.UTF_8));

        JsonNode e002 = despesa(raiz, "e-002"); // 22 EUR
        assertEquals("recusada", e002.get("status").asText());
        assertTrue(e002.get("valor_solicitado").isNull(), "sem cotacao nao ha valor em BRL");
        assertEquals("Moeda sem cotação disponível", e002.get("motivos").get(0).asText());

        JsonNode e010 = despesa(raiz, "e-010"); // BRL
        assertEquals("aprovada", e010.get("status").asText(),
                "o lote segue: despesa em BRL aprovada normalmente");
    }

    private JsonNode despesa(JsonNode raiz, String id) {
        for (JsonNode d : raiz.get("despesas")) {
            if (id.equals(d.get("id").asText())) {
                return d;
            }
        }
        throw new AssertionError("despesa ausente na saida: " + id);
    }
}
