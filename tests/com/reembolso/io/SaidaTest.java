package com.reembolso.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reembolso.PoliticaV3;
import com.reembolso.materializacao.Saida;
import com.reembolso.motor.MotorReembolso;

/**
 * T-014 — Materializacao de `Saida` e escrita do JSON de saida (io/saida).
 * T-021 — Saida multi-moeda (D-003): spec §4 — `moeda`, `valor_original`,
 * `valor_solicitado` em BRL, `taxa_cambio`/`data_taxa` (somente quando moeda
 * ≠ BRL), totais em BRL (item sem cotacao contribui com 0); RN-010, RN-011.
 *
 * Criterios de aceite T-021:
 *  - saida_despesaEmMoedaEstrangeira_expoeTaxaEData: e-004 (30 EUR no sabado)
 *  - saida_despesaBrl_semCamposDeTaxa: moeda base nao carrega campos de taxa
 *  - saida_despesaSemCotacao_semValorBrl: valor_solicitado nulo, contribui 0
 *  - rn010_totaisEmBrlDuasCasas: totais somam valores em BRL com 2 casas
 */
class SaidaTest {

    /** Le o JSON gerado preservando a escala decimal (sem stripTrailingZeros), para conferir as 2 casas. */
    private static final ObjectMapper LEITOR = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));

    private static final String ENTRADA_PEQUENA = """
            {
              "colaborador": {"id": "c-0417", "nome": "Ana", "centro_custo": "CC-1"},
              "periodo": {"competencia": "2026-07", "inicio": "2026-07-01", "fim": "2026-07-31"},
              "despesas": [
                {"id": "d-a", "data": "2026-07-10", "categoria": "alimentacao",
                 "descricao": "almoco", "fornecedor": "Restaurante X",
                 "valor": 72.50, "tem_nota_fiscal": true},
                {"id": "d-b", "data": "2026-07-11", "categoria": "coworking",
                 "descricao": "sala", "fornecedor": "Cowork Y",
                 "valor": 89.00, "tem_nota_fiscal": true}
              ]
            }
            """;

    private JsonNode processar(String jsonEntrada) throws Exception {
        return processar(jsonEntrada, null);
    }

    private JsonNode processar(String jsonEntrada, Cambio cambio) throws Exception {
        Entrada entrada = Ingestao.lerDeString(jsonEntrada);
        Saida saida = Saida.de(entrada,
                new MotorReembolso().processar(entrada, PoliticaV3.politica(), cambio));
        return LEITOR.readTree(EscritorSaida.escreverJson(saida));
    }

    @Test
    @DisplayName("saida_jsonCompleto_comTodosOsCamposDaSpec")
    void saida_jsonCompleto_comTodosOsCamposDaSpec() throws Exception {
        JsonNode raiz = processar(ENTRADA_PEQUENA);

        assertEquals("c-0417", raiz.get("colaborador_id").asText());
        assertEquals("2026-07", raiz.get("competencia").asText());
        assertEquals(new BigDecimal("161.50"), raiz.get("total_solicitado").decimalValue(),
                "total_solicitado = soma de TODOS os valores informados (72,50 + 89,00)");
        assertEquals(new BigDecimal("60.00"), raiz.get("total_reembolsado").decimalValue(),
                "apenas d-a reembolsa, cortada no teto diario de alimentacao (RN-006)");

        JsonNode despesas = raiz.get("despesas");
        assertEquals(2, despesas.size());
        for (JsonNode d : despesas) {
            List<String> campos = new java.util.ArrayList<>();
            d.fieldNames().forEachRemaining(campos::add);
            assertIterableEquals(
                    List.of("id", "status", "moeda", "valor_original", "valor_solicitado",
                            "valor_reembolsado", "motivos"),
                    campos,
                    "despesa em moeda base carrega os campos do contrato SEM taxa_cambio/data_taxa (spec §4)");
        }
    }

    @Test
    @DisplayName("saida_despesas_comStatusEMotivosCoerentes")
    void saida_despesas_comStatusEMotivosCoerentes() throws Exception {
        JsonNode despesas = processar(ENTRADA_PEQUENA).get("despesas");

        JsonNode dA = despesas.get(0);
        assertEquals("d-a", dA.get("id").asText());
        assertEquals("parcial", dA.get("status").asText());
        assertEquals(new BigDecimal("72.50"), dA.get("valor_solicitado").decimalValue());
        assertEquals(new BigDecimal("60.00"), dA.get("valor_reembolsado").decimalValue());
        assertTrue(dA.get("motivos").size() >= 1, "RN-011: motivo obrigatorio");

        JsonNode dB = despesas.get(1);
        assertEquals("d-b", dB.get("id").asText());
        assertEquals("recusada", dB.get("status").asText());
        assertEquals(new BigDecimal("89.00"), dB.get("valor_solicitado").decimalValue());
        assertEquals(new BigDecimal("0.00"), dB.get("valor_reembolsado").decimalValue());
        assertEquals("Categoria fora da política", dB.get("motivos").get(0).asText());
    }

    @Test
    @DisplayName("rn010_numeros_comExatamenteDuasCasasDecimais")
    void rn010_numeros_comExatamenteDuasCasasDecimais() throws Exception {
        JsonNode raiz = processar(ENTRADA_PEQUENA);

        List<JsonNode> numeros = new java.util.ArrayList<>();
        numeros.add(raiz.get("total_solicitado"));
        numeros.add(raiz.get("total_reembolsado"));
        raiz.get("despesas").forEach(d -> {
            numeros.add(d.get("valor_original"));
            numeros.add(d.get("valor_solicitado"));
            numeros.add(d.get("valor_reembolsado"));
        });
        assertEquals(8, numeros.size());
        for (JsonNode n : numeros) {
            assertTrue(n.isNumber(), n + " deve ser numero JSON");
            assertEquals(2, n.decimalValue().scale(),
                    "RN-010: toda saida monetaria com exatamente 2 casas: " + n);
        }
        // zero tambem sai com 2 casas: valor_reembolsado da recusada d-b
        assertEquals("0.00", raiz.get("despesas").get(1).get("valor_reembolsado").decimalValue().toPlainString());
    }

    // ---------------------------------------------------------------- T-021 (D-003): saida multi-moeda

    /** A tabela de cambio real do envelope — PTAX publicada apenas em dias uteis (spec §4). */
    private Cambio cambioReal() {
        return Ingestao.lerCambio(java.nio.file.Path.of("exemplos/cambio.json"));
    }

    @Test
    @DisplayName("saida_despesaEmMoedaEstrangeira_expoeTaxaEData")
    void saida_despesaEmMoedaEstrangeira_expoeTaxaEData() throws Exception {
        // e-004: 30 EUR num sabado — a saida expoe a conversao completa (spec §4):
        // moeda original, valor original, valor em BRL, taxa aplicada e sua data.
        String json = """
            {
              "colaborador": {"id": "c-0912", "nome": "Rafael", "centro_custo": "CC"},
              "periodo": {"competencia": "2026-07", "inicio": "2026-07-01", "fim": "2026-07-31"},
              "despesas": [
                {"id": "e-004", "data": "2026-07-18", "categoria": "alimentacao",
                 "descricao": "Jantar de sabado", "fornecedor": "Cervejaria Ramiro",
                 "valor": 30.00, "moeda": "EUR", "tem_nota_fiscal": true}
              ]
            }
            """;
        JsonNode d = processar(json, cambioReal()).get("despesas").get(0);

        assertEquals("EUR", d.get("moeda").asText());
        assertEquals(new BigDecimal("30.00"), d.get("valor_original").decimalValue());
        assertEquals(new BigDecimal("178.80"), d.get("valor_solicitado").decimalValue(),
                "30 × 5,96 (ultima publicada, 2026-07-17) = 178,80");
        assertEquals(new BigDecimal("5.96"), d.get("taxa_cambio").decimalValue());
        assertEquals("2026-07-17", d.get("data_taxa").asText(),
                "data da publicacao da taxa usada, anterior a data da despesa");
        assertEquals(new BigDecimal("60.00"), d.get("valor_reembolsado").decimalValue(),
                "corte no teto diario do padrao sobre o valor em BRL (178,80 > 60,00)");
    }

    @Test
    @DisplayName("saida_despesaBrl_semCamposDeTaxa")
    void saida_despesaBrl_semCamposDeTaxa() throws Exception {
        JsonNode d = processar(ENTRADA_PEQUENA).get("despesas").get(0);

        assertEquals("BRL", d.get("moeda").asText(),
                "moeda original do lancamento (BRL quando ausente na entrada, spec §4)");
        assertEquals(d.get("valor_solicitado").decimalValue(), d.get("valor_original").decimalValue(),
                "moeda base: valor_original = valor_solicitado (sem conversao)");
        assertFalse(d.has("taxa_cambio"), "campos de taxa nao existem para moeda base (spec §4)");
        assertFalse(d.has("data_taxa"), "campos de taxa nao existem para moeda base (spec §4)");
    }

    @Test
    @DisplayName("saida_despesaSemCotacao_semValorBrl")
    void saida_despesaSemCotacao_semValorBrl() throws Exception {
        // AMB-017: GBP sem cotacao — valor_solicitado nulo, contribui 0 nos totais
        String json = """
            {
              "colaborador": {"id": "c-1", "nome": "X", "centro_custo": "CC"},
              "periodo": {"competencia": "2026-07", "inicio": "2026-07-01", "fim": "2026-07-31"},
              "despesas": [
                {"id": "d-gbp", "data": "2026-07-21", "categoria": "alimentacao",
                 "descricao": "jantar", "fornecedor": "The Ivy",
                 "valor": 55.00, "moeda": "GBP", "tem_nota_fiscal": true},
                {"id": "d-brl", "data": "2026-07-10", "categoria": "alimentacao",
                 "descricao": "almoco", "fornecedor": "Rest",
                 "valor": 50.00, "tem_nota_fiscal": true}
              ]
            }
            """;
        JsonNode raiz = processar(json, cambioReal());
        JsonNode gbp = raiz.get("despesas").get(0);

        assertTrue(gbp.get("valor_solicitado").isNull(),
                "sem cotacao nao ha valor em BRL (spec §4: nulo)");
        assertEquals(new BigDecimal("0.00"), gbp.get("valor_reembolsado").decimalValue());
        assertEquals("Moeda sem cotação disponível", gbp.get("motivos").get(0).asText());
        assertTrue(gbp.get("taxa_cambio").isNull(),
                "moeda ≠ BRL carrega os campos de taxa — nulos, nenhuma taxa foi aplicada");
        assertTrue(gbp.get("data_taxa").isNull());

        assertEquals(new BigDecimal("50.00"), raiz.get("total_solicitado").decimalValue(),
                "item sem cotacao contribui com 0 no total (so o BRL entra, spec §4)");
        assertEquals(new BigDecimal("50.00"), raiz.get("total_reembolsado").decimalValue());
    }

    @Test
    @DisplayName("rn010_totaisEmBrlDuasCasas")
    void rn010_totaisEmBrlDuasCasas() throws Exception {
        // RN-010: totais somam os valores em BRL (convertidos inclusos) com 2 casas
        String json = """
            {
              "colaborador": {"id": "c-1", "nome": "X", "centro_custo": "CC"},
              "periodo": {"competencia": "2026-07", "inicio": "2026-07-01", "fim": "2026-07-31"},
              "despesas": [
                {"id": "d-eur", "data": "2026-07-14", "categoria": "hospedagem",
                 "descricao": "hotel", "fornecedor": "Premier Inn",
                 "valor": 22.00, "moeda": "EUR", "tem_nota_fiscal": true},
                {"id": "d-brl", "data": "2026-07-10", "categoria": "alimentacao",
                 "descricao": "almoco", "fornecedor": "Rest",
                 "valor": 50.00, "tem_nota_fiscal": true}
              ]
            }
            """;
        JsonNode raiz = processar(json, cambioReal());

        assertEquals(new BigDecimal("180.46"), raiz.get("total_solicitado").decimalValue(),
                "130,46 (22 EUR × 5,93) + 50,00 — soma em BRL dos valores convertidos");
        assertEquals(2, raiz.get("total_solicitado").decimalValue().scale());
        assertEquals(new BigDecimal("180.46"), raiz.get("total_reembolsado").decimalValue());
        assertEquals(2, raiz.get("total_reembolsado").decimalValue().scale());
    }
}
