package com.reembolso.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.io.Ingestao.IngestaoException;

/**
 * T-003 — Modelos de entrada + leitura de JSON.
 * Criterios de aceite:
 *  - le exemplos/despesas-exemplo.json em memoria
 *  - periodo.em_viagem ausente -> false (AMB-005)
 *  - valor preservado como string no record (DT-001)
 *  - erro legivel em JSON malformado
 */
class IngestaoTest {

    @Test
    @DisplayName("t003_leExemploCompleto_emMemoria")
    void t003_leExemploCompleto_emMemoria() {
        Entrada e = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));

        assertEquals("c-0417", e.colaborador().id());
        assertEquals("Marina Volpi", e.colaborador().nome());
        assertEquals("CC-ENG-PLATAFORMA", e.colaborador().centroCusto());
        assertEquals("2026-07", e.periodo().competencia());
        assertEquals(LocalDate.of(2026, 7, 1), e.periodo().inicio());
        assertEquals(LocalDate.of(2026, 7, 31), e.periodo().fim());
        assertEquals(14, e.despesas().size());
        assertEquals("d-001", e.despesas().get(0).id());
        assertEquals("d-014", e.despesas().get(13).id());
    }

    @Test
    @DisplayName("emViagem_ausente_defaultFalse")
    void emViagem_ausente_defaultFalse() {
        // AMB-005: o exemplo nao tem o campo em_viagem; default deve ser false
        Entrada e = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));
        assertFalse(e.periodo().emViagem());
    }

    @Test
    @DisplayName("emViagem_presente_respeitaValor")
    void emViagem_presente_respeitaValor() {
        String json = """
            {
              "colaborador": {"id": "c-1", "nome": "X", "centro_custo": "CC"},
              "periodo": {"competencia": "2026-07", "inicio": "2026-07-01", "fim": "2026-07-31", "em_viagem": true},
              "despesas": []
            }
            """;
        assertTrue(Ingestao.lerDeString(json).periodo().emViagem());
    }

    @Test
    @DisplayName("valor_preservadoComoStringSemArredondamento")
    void valor_preservadoComoStringSemArredondamento() {
        // DT-001: parser nao pode transformar 33.333 em float antes do Dinheiro
        Entrada e = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));
        Entrada.DespesaIn d011 = e.despesas().stream()
                .filter(d -> d.id().equals("d-011")).findFirst().orElseThrow();
        assertEquals("33.333", d011.valor());
    }

    @Test
    @DisplayName("valor_comDuasCasas_tambemEviveString")
    void valor_comDuasCasas_tambemEviveString() {
        Entrada e = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));
        Entrada.DespesaIn d001 = e.despesas().get(0);
        assertEquals("72.5", d001.valor(), "Jackson normaliza 72.50 para '72.5' sem perder precisao");
    }

    @Test
    @DisplayName("jsonMalformado_erroLegivel")
    void jsonMalformado_erroLegivel() {
        IngestaoException ex = assertThrows(IngestaoException.class,
                () -> Ingestao.lerDeString("{ isto nao e json "));
        assertTrue(ex.getMessage().contains("JSON"), "mensagem deve dizer que e erro de JSON: " + ex.getMessage());
    }

    @Test
    @DisplayName("campoObrigatorioAusente_erroNomenaCampo")
    void campoObrigatorioAusente_erroNomenaCampo() {
        String json = """
            {
              "colaborador": {"id": "c-1", "nome": "X", "centro_custo": "CC"},
              "despesas": []
            }
            """;
        IngestaoException ex = assertThrows(IngestaoException.class, () -> Ingestao.lerDeString(json));
        assertTrue(ex.getMessage().contains("periodo"), "mensagem deve citar o campo ausente: " + ex.getMessage());
    }

    @Test
    @DisplayName("dataInvalida_erroLegivelComOCampo")
    void dataInvalida_erroLegivelComOCampo() {
        String json = """
            {
              "colaborador": {"id": "c-1", "nome": "X", "centro_custo": "CC"},
              "periodo": {"competencia": "2026-07", "inicio": "15/07/2026", "fim": "2026-07-31"},
              "despesas": []
            }
            """;
        IngestaoException ex = assertThrows(IngestaoException.class, () -> Ingestao.lerDeString(json));
        assertTrue(ex.getMessage().contains("inicio"), "mensagem deve citar o campo: " + ex.getMessage());
    }

    @Test
    @DisplayName("arquivoInexistente_erroLegivel")
    void arquivoInexistente_erroLegivel() {
        IngestaoException ex = assertThrows(IngestaoException.class,
                () -> Ingestao.ler(java.nio.file.Path.of("exemplos/nao-existe.json")));
        assertTrue(ex.getMessage().contains("nao-existe"), "mensagem deve citar o arquivo: " + ex.getMessage());
    }

    @Test
    @DisplayName("categoriaECase_doExemploPreservadosParaNormalizacao")
    void categoriaECase_doExemploPreservadosParaNormalizacao() {
        // d-014 chega como "ALIMENTACAO"; a normalizacao e decisao da T-004, aqui so preserva
        Entrada e = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-exemplo.json"));
        assertEquals("ALIMENTACAO", e.despesas().get(13).categoria());
    }
}
