package com.reembolso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.io.Entrada;
import com.reembolso.io.Ingestao;
import com.reembolso.materializacao.Saida;
import com.reembolso.motor.MotorReembolso;
import com.reembolso.motor.ResultadoDespesa;

/**
 * T-016 — E2E dourado: totais esperados do exemplo (spec §9 criterios de aceite).
 *
 * <p>Processa `exemplos/despesas-exemplo.json` pelo pipeline completo
 * (Ingestao -> Motor -> Saida) e afirma o estado de CADA despesa e o
 * total_reembolsado = 585,43. Base fixa de regressao: qualquer mudanca de
 * regra que altere estes numeros invalida a entrega.
 */
class ExemploE2ETest {

    private Saida processarExemplo() {
        Entrada entrada = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));
        return Saida.de(entrada, new MotorReembolso().processar(entrada));
    }

    @Test
    @DisplayName("e2e_exemplo_estadoDeCadaDespesaConformeSpec9")
    void e2e_exemplo_estadoDeCadaDespesaConformeSpec9() {
        Saida saida = processarExemplo();
        Map<String, Saida.DespesaOut> porId = saida.despesas().stream()
                .collect(Collectors.toMap(Saida.DespesaOut::id, Function.identity()));

        assertEquals(14, porId.size(), "o exemplo tem 14 despesas");

        // Expectativas fixas da spec §9 / tarefa T-016
        assertDespesa(porId, "d-001", "parcial", "72.50", "60.00");
        assertDespesa(porId, "d-002", "parcial", "38.00", "0.00");
        assertDespesa(porId, "d-003", "parcial", "100.00", "80.00");
        assertDespesa(porId, "d-004", "recusada", "100.01", "0.00");
        assertDespesa(porId, "d-005", "recusada", "89.00", "0.00");
        assertDespesa(porId, "d-006", "aprovada", "54.90", "54.90");
        assertDespesa(porId, "d-007", "recusada", "54.90", "0.00");
        assertDespesa(porId, "d-008", "recusada", "41.00", "0.00");
        assertDespesa(porId, "d-009", "recusada", "-45.00", "0.00");
        assertDespesa(porId, "d-010", "parcial", "480.00", "250.00");
        assertDespesa(porId, "d-011", "aprovada", "33.33", "33.33"); // 33,333 -> 33,33 (RN-010)
        assertDespesa(porId, "d-012", "aprovada", "47.20", "47.20");
        assertDespesa(porId, "d-013", "recusada", "690.00", "0.00");
        assertDespesa(porId, "d-014", "parcial", "61.00", "60.00");
    }

    @Test
    @DisplayName("e2e_exemplo_totalReembolsado_585virgula43")
    void e2e_exemplo_totalReembolsado_585virgula43() {
        Saida saida = processarExemplo();
        assertEquals(new BigDecimal("585.43"), saida.totalReembolsado().saida(),
                "total_reembolsado dourado da spec §9");

        // conferencia cruzada: o total bate com a soma dos reembolsos por despesa
        BigDecimal soma = BigDecimal.ZERO.setScale(2);
        for (Saida.DespesaOut d : saida.despesas()) {
            soma = soma.add(d.valorReembolsado().saida());
        }
        assertEquals(new BigDecimal("585.43"), soma,
                "soma dos valor_reembolsado individuais == total_reembolsado");

        // total_solicitado inclui ate estornos e recusadas (spec §4)
        assertEquals(new BigDecimal("1816.84"), saida.totalSolicitado().saida(),
                "soma de TODOS os valores informados (inclui estorno -45,00 e recusadas)");
    }

    private void assertDespesa(Map<String, Saida.DespesaOut> porId, String id,
                               String status, String solicitado, String reembolsado) {
        Saida.DespesaOut d = porId.get(id);
        org.junit.jupiter.api.Assertions.assertNotNull(d, "despesa ausente na saida: " + id);
        assertEquals(status, d.status(), id + " status");
        assertEquals(new BigDecimal(solicitado), d.valorSolicitado().saida(), id + " valor_solicitado");
        assertEquals(new BigDecimal(reembolsado), d.valorReembolsado().saida(), id + " valor_reembolsado");
    }
}
