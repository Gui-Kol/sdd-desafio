package com.reembolso.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Criterio de aceite (teste de integracao):
 *  - entrada pequena -> JSON com `colaborador_id`, `competencia`,
 *    `total_solicitado`, `total_reembolsado`, `despesas[]` com os 5 campos
 *    (id, status, valor_solicitado, valor_reembolsado, motivos);
 *  - numeros serializados com 2 casas decimais (RN-010).
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
        Entrada entrada = Ingestao.lerDeString(jsonEntrada);
        Saida saida = Saida.de(entrada,
                new MotorReembolso().processar(entrada, PoliticaV3.politica()));
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
                    List.of("id", "status", "valor_solicitado", "valor_reembolsado", "motivos"),
                    campos,
                    "cada despesa carrega exatamente os 5 campos do contrato (spec §4)");
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
            numeros.add(d.get("valor_solicitado"));
            numeros.add(d.get("valor_reembolsado"));
        });
        assertEquals(6, numeros.size());
        for (JsonNode n : numeros) {
            assertTrue(n.isNumber(), n + " deve ser numero JSON");
            assertEquals(2, n.decimalValue().scale(),
                    "RN-010: toda saida monetaria com exatamente 2 casas: " + n);
        }
        // zero tambem sai com 2 casas: valor_reembolsado da recusada d-b
        assertEquals("0.00", raiz.get("despesas").get(1).get("valor_reembolsado").decimalValue().toPlainString());
    }
}
