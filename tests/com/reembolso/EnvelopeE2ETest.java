package com.reembolso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.io.Cambio;
import com.reembolso.io.Entrada;
import com.reembolso.io.Ingestao;
import com.reembolso.materializacao.Saida;
import com.reembolso.motor.MotorReembolso;
import com.reembolso.motor.Politica;

/**
 * T-022 — E2E dourado dos envelopes do Dia 2 (spec §9 critérios de aceite).
 *
 * <p>Processa os arquivos REAIS do envelope (`despesas-envelope.json`,
 * `despesas-envelope-cc-desconhecido.json`) com a política v4 e o câmbio
 * reais, pelo pipeline completo (Ingestao -> Motor -> Saida), e afirma o
 * estado de CADA despesa e os totais. Base de regressão do Dia 2, paralela
 * ao E2E dourado do Dia 1 (que roda sob a v3 como dado).
 */
class EnvelopeE2ETest {

    private Saida processar(String despesas) {
        Entrada entrada = Ingestao.ler(java.nio.file.Path.of(despesas));
        Politica politica = Politica.de(
                Ingestao.lerPolitica(java.nio.file.Path.of("exemplos/politica-v4.json")));
        Cambio cambio = Ingestao.lerCambio(java.nio.file.Path.of("exemplos/cambio.json"));
        return Saida.de(entrada, new MotorReembolso().processar(entrada, politica, cambio));
    }

    private Map<String, Saida.DespesaOut> porId(Saida saida) {
        return saida.despesas().stream()
                .collect(Collectors.toMap(Saida.DespesaOut::id, Function.identity()));
    }

    @Test
    @DisplayName("e2e_envelope_estadoDeCadaDespesaConformeSpec9")
    void e2e_envelope_estadoDeCadaDespesaConformeSpec9() {
        Saida saida = processar("exemplos/despesas-envelope.json");
        Map<String, Saida.DespesaOut> porId = porId(saida);

        assertEquals(10, porId.size(), "o envelope tem 10 despesas");

        // Expectativas fixas da spec §9 / tarefa T-022
        assertDespesa(porId, "e-001", "parcial", "340.00", "300.00");   // representacao CC-COMERCIAL, limite diario 300,00 (RN-012)
        assertDespesa(porId, "e-002", "parcial", "130.46", "90.00");    // 22 EUR × 5,93 = 130,46 — cortada no limite do CC
        assertDespesa(porId, "e-003", "aprovada", "85.26", "85.26");    // 14,50 EUR × 5,88 = 85,26, dentro do limite
        assertDespesa(porId, "e-004", "parcial", "178.80", "90.00");    // sabado: taxa 5,96 de 2026-07-17 (AMB-016)
        assertDespesa(porId, "e-005", "recusada", "220.00", "0.00");    // 40 USD = 220,00 sem nota (AMB-019)
        assertDespesa(porId, "e-006", "recusada", null, "0.00");        // GBP sem cotacao (AMB-017)
        assertDespesa(porId, "e-007", "parcial", "1200.00", "400.00");  // hospedagem CC 400,00/lancamento; "3 noites" ignorado (D-002)
        assertDespesa(porId, "e-008", "parcial", "95.00", "90.00");     // alimentacao 95,00 > limite do CC 90,00
        assertDespesa(porId, "e-009", "recusada", "120.00", "0.00");    // coworking fora da politica (RN-002)
        assertDespesa(porId, "e-010", "aprovada", "88.00", "88.00");    // moeda ausente = BRL; dentro do limite do CC

        // conversões expostas na saída (spec §4): e-002 e e-004
        Saida.DespesaOut e002 = porId.get("e-002");
        assertEquals("EUR", e002.moeda());
        assertEquals(new BigDecimal("22.00"), e002.valorOriginal().saida());
        assertEquals(new BigDecimal("5.93"), e002.conversao().taxa());
        assertEquals(java.time.LocalDate.of(2026, 7, 14), e002.conversao().dataTaxa());

        Saida.DespesaOut e004 = porId.get("e-004");
        assertEquals(new BigDecimal("5.96"), e004.conversao().taxa(),
                "sabado 2026-07-18 usa a ultima publicada (2026-07-17)");
        assertEquals(java.time.LocalDate.of(2026, 7, 17), e004.conversao().dataTaxa());

        // moeda ausente = BRL, sem conversão (AMB-018); sem cotação = sem valor em BRL (§8)
        assertEquals("BRL", porId.get("e-010").moeda());
        assertNull(porId.get("e-010").conversao(), "moeda base nao gera conversao");
        assertNull(porId.get("e-006").valorSolicitado(), "sem cotacao nao ha valor em BRL");

        // motivos-chave (RN-011)
        assertTrue(porId.get("e-005").motivos().contains("Despesa acima de R$ 100,00 sem nota fiscal"));
        assertTrue(porId.get("e-006").motivos().contains("Moeda sem cotação disponível"));
        assertTrue(porId.get("e-009").motivos().contains("Categoria fora da política"));

        // totais dourados da spec §9 (item sem cotação contribui com 0)
        assertEquals(new BigDecimal("2457.52"), saida.totalSolicitado().saida());
        assertEquals(new BigDecimal("1143.26"), saida.totalReembolsado().saida());
    }

    @Test
    @DisplayName("e2e_envelopeCcDesconhecido_resolvePadrao")
    void e2e_envelopeCcDesconhecido_resolvePadrao() {
        // AMB-014: CC-SUPORTE-N2 sem bloco na política v4 → tudo pelo padrao
        Saida saida = processar("exemplos/despesas-envelope-cc-desconhecido.json");
        Map<String, Saida.DespesaOut> porId = porId(saida);

        assertEquals(4, porId.size());

        assertDespesa(porId, "f-001", "aprovada", "58.00", "58.00");     // alimentacao padrao 60,00
        assertDespesa(porId, "f-002", "parcial", "310.00", "250.00");    // hospedagem padrao 250,00/lancamento
        assertDespesa(porId, "f-003", "recusada", "190.00", "0.00");     // representacao so existe em outro CC (AMB-014)
        assertDespesa(porId, "f-004", "aprovada", "65.76", "65.76");     // 12 USD × 5,48 = 65,76 <= 80,00

        assertTrue(porId.get("f-003").motivos().contains("Categoria fora da política"),
                "recusada porque o padrao nao cobre representacao: " + porId.get("f-003").motivos());
        assertNull(porId.get("f-001").conversao(), "BRL: sem conversao");
        assertEquals("USD", porId.get("f-004").moeda());
        assertEquals(new BigDecimal("5.48"), porId.get("f-004").conversao().taxa());

        assertEquals(new BigDecimal("623.76"), saida.totalSolicitado().saida());
        assertEquals(new BigDecimal("373.76"), saida.totalReembolsado().saida());
    }

    private void assertDespesa(Map<String, Saida.DespesaOut> porId, String id,
                               String status, String solicitado, String reembolsado) {
        Saida.DespesaOut d = porId.get(id);
        assertNotNull(d, "despesa ausente na saida: " + id);
        assertEquals(status, d.status(), id + " status");
        if (solicitado == null) {
            assertNull(d.valorSolicitado(), id + " sem valor em BRL (sem cotacao)");
        } else {
            assertEquals(new BigDecimal(solicitado), d.valorSolicitado().saida(),
                    id + " valor_solicitado (em BRL)");
        }
        assertEquals(new BigDecimal(reembolsado), d.valorReembolsado().saida(), id + " valor_reembolsado");
    }
}
