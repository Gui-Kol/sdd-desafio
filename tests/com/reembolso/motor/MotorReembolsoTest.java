package com.reembolso.motor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.dominio.Categoria;
import com.reembolso.io.Entrada;
import com.reembolso.io.Ingestao;

/**
 * T-004 — RN-001 + RN-002: normalizacao de categoria e recusa de categoria
 * fora da politica.
 * Criterios de aceite:
 *  - rn001_caseInsensitive_alimentacaoReconhecida: "ALIMENTACAO" ≡ alimentacao
 *  - rn002_coworking_recusada: d-005 recusada com motivo "Categoria fora da política"
 */
class MotorReembolsoTest {

    private Map<String, ResultadoDespesa> processarExemplo() {
        Entrada entrada = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));
        List<ResultadoDespesa> resultados = new MotorReembolso().processar(entrada);
        return resultados.stream().collect(Collectors.toMap(ResultadoDespesa::id, Function.identity()));
    }

    @Test
    @DisplayName("rn001_caseInsensitive_alimentacaoReconhecida")
    void rn001_caseInsensitive_alimentacaoReconhecida() {
        // RN-001 / AMB-009: d-014 usa "ALIMENTACAO" e deve ser tratada como alimentacao,
        // ou seja, NAO pode ser recusada por categoria fora da politica.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d014 = resultado.get("d-014");

        assertEquals(ResultadoDespesa.Status.APROVADA, d014.status(),
                "categoria 'ALIMENTACAO' deve equivaler a 'alimentacao' e ser reconhecida");
        assertEquals(new BigDecimal("61.00"), d014.valorReembolsado().saida());
        assertTrue(d014.motivos().stream().noneMatch(m -> m.equals(MotorReembolso.MOTIVO_CATEGORIA_FORA_DA_POLITICA)),
                "nao pode ser recusada por categoria fora da politica");
    }

    @Test
    @DisplayName("rn002_coworking_recusada")
    void rn002_coworking_recusada() {
        // RN-002: d-005 (coworking, R$ 89,00) -> recusada, R$ 0,00, motivo fixo da spec
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d005 = resultado.get("d-005");

        assertEquals(ResultadoDespesa.Status.RECUSADA, d005.status());
        assertEquals(new BigDecimal("0.00"), d005.valorReembolsado().saida());
        assertEquals(new BigDecimal("89.00"), d005.valorSolicitado().saida(),
                "valor solicitado preserva o original da entrada");
        assertTrue(d005.motivos().contains("Categoria fora da política"),
                "motivo obrigatorio da RN-002: " + d005.motivos());
    }

    @Test
    @DisplayName("rn001_categoriasDoCatalogo_reconhecidas")
    void rn001_categoriasDoCatalogo_reconhecidas() {
        // RN-001: o catalogo e exatamente alimentacao, transporte_urbano, hospedagem
        Map<String, ResultadoDespesa> resultado = processarExemplo();

        assertEquals(ResultadoDespesa.Status.APROVADA, resultado.get("d-001").status()); // alimentacao
        assertEquals(ResultadoDespesa.Status.APROVADA, resultado.get("d-003").status()); // transporte_urbano
        assertEquals(ResultadoDespesa.Status.APROVADA, resultado.get("d-010").status()); // hospedagem
    }

    @Test
    @DisplayName("rn001_trimECase_variacoesNormalizam")
    void rn001_trimECase_variacoesNormalizam() {
        // RN-001: trim + case-insensitive em qualquer variacao
        assertEquals(Categoria.ALIMENTACAO, Categoria.normalizar("  ALIMENTACAO ").orElseThrow());
        assertEquals(Categoria.ALIMENTACAO, Categoria.normalizar("AlImEnTaCaO").orElseThrow());
        assertEquals(Categoria.TRANSPORTE_URBANO, Categoria.normalizar(" Transporte_Urbano").orElseThrow());
        assertEquals(Categoria.HOSPEDAGEM, Categoria.normalizar("HOSPEDAGEM").orElseThrow());
        assertTrue(Categoria.normalizar("coworking").isEmpty());
        assertTrue(Categoria.normalizar("").isEmpty());
        assertTrue(Categoria.normalizar(null).isEmpty());
    }

    @Test
    @DisplayName("rn002_todaCategoriaForaDoCatalogo_recusaIntegral")
    void rn002_todaCategoriaForaDoCatalogo_recusaIntegral() {
        // RN-002: recusa e integral (reembolso zero), qualquer que seja o valor
        Entrada.DespesaIn coworking = new Entrada.DespesaIn("x-1",
                java.time.LocalDate.of(2026, 7, 10), "coworking", "teste", "HubOffice", "1500.99", true);
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07", java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(coworking));

        ResultadoDespesa r = new MotorReembolso().processar(entrada).get(0);

        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(new BigDecimal("0.00"), r.valorReembolsado().saida());
        assertEquals(List.of("Categoria fora da política"), r.motivos(),
                "RN-011: recusa carrega motivo legivel");
    }
}
