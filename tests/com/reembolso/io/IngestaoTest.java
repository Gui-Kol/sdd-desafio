package com.reembolso.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Ingestao.IngestaoException;

/**
 * T-003 — Modelos de entrada + leitura de JSON.
 * Criterios de aceite:
 *  - le exemplos/despesas-exemplo.json em memoria
 *  - periodo.em_viagem ausente -> false (AMB-005)
 *  - valor preservado como string no record (DT-001)
 *  - erro legivel em JSON malformado
 *
 * T-018 — Ingestao da politica v4, do cambio e do campo `moeda` (D-003).
 * Criterios de aceite:
 *  - politicaV4_lidaEmMemoria: exemplos/politica-v4.json inteiro em memoria
 *  - cambio_lidoEmMemoria: exemplos/cambio.json inteiro em memoria
 *  - moeda_ausente_defaultBrl: ausente ≡ "BRL" explicito (AMB-018)
 *  - politicaSemCampoObrigatorio_erroLegivel: sem padrao/vigencia -> erro
 *    legivel, nao excecao crua
 *  - JSON malformado de politica/cambio -> erro legivel (padrao da T-003)
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

    // ---------------------------------------------------------------- T-018 (D-003)

    @Test
    @DisplayName("politicaV4_lidaEmMemoria")
    void politicaV4_lidaEmMemoria() {
        PoliticaV4 p = Ingestao.lerPolitica(java.nio.file.Path.of("exemplos/politica-v4.json"));

        assertEquals("v4", p.versao());
        assertEquals(LocalDate.of(2026, 7, 1), p.vigencia());
        assertEquals("BRL", p.moedaBase());

        // bloco padrao (fallback de qualquer CC sem override — RN-012, T-019)
        assertEquals(3, p.padrao().size());
        assertEquals(Dinheiro.de("60.00"), p.padrao().get("alimentacao").limite());
        assertEquals("dia", p.padrao().get("alimentacao").periodicidade());
        assertEquals(Dinheiro.de("80.00"), p.padrao().get("transporte_urbano").limite());
        assertEquals(Dinheiro.de("250.00"), p.padrao().get("hospedagem").limite());
        assertEquals("diaria", p.padrao().get("hospedagem").periodicidade());

        // overrides por centro de custo
        assertEquals(3, p.centrosCusto().size());

        var comercial = p.centrosCusto().get("CC-COMERCIAL");
        assertEquals(Dinheiro.de("90.00"), comercial.get("alimentacao").limite());
        assertEquals(Dinheiro.de("150.00"), comercial.get("transporte_urbano").limite());
        assertEquals(Dinheiro.de("400.00"), comercial.get("hospedagem").limite());
        assertEquals(Dinheiro.de("300.00"), comercial.get("representacao").limite());
        assertEquals("dia", comercial.get("representacao").periodicidade());

        var eng = p.centrosCusto().get("CC-ENG-PLATAFORMA");
        assertEquals(Dinheiro.de("0.00"), eng.get("hospedagem").limite());
        assertEquals("nao reembolsavel", eng.get("hospedagem").observacao());

        var adm = p.centrosCusto().get("CC-ADM");
        assertFalse(adm.containsKey("hospedagem"),
                "CC-ADM nao lista hospedagem — heranca do padrao e decisao da RN-012 (T-019), aqui so se registra a ausencia");

        assertEquals(Dinheiro.de("100.00"), p.notaFiscalObrigatoriaAcimaDe());
        assertEquals(0, p.acrescimoEmViagemPercentual().compareTo(BigDecimal.valueOf(50)),
                "acrescimo de viagem: 50%");
    }

    @Test
    @DisplayName("cambio_lidoEmMemoria")
    void cambio_lidoEmMemoria() {
        Cambio c = Ingestao.lerCambio(java.nio.file.Path.of("exemplos/cambio.json"));

        assertEquals("BRL", c.moedaBase());
        assertEquals(12, c.taxas().size());
        assertEquals(0, c.taxas().get(LocalDate.of(2026, 7, 13)).get("USD").compareTo(new BigDecimal("5.42")));
        assertEquals(0, c.taxas().get(LocalDate.of(2026, 7, 27)).get("EUR").compareTo(new BigDecimal("6.03")));
        assertFalse(c.taxas().containsKey(LocalDate.of(2026, 7, 18)),
                "a tabela publica so em dias uteis — sabado 2026-07-18 nao tem cotação "
                        + "(RN-013 aplicara a ultima publicada, T-020)");
    }

    @Test
    @DisplayName("moeda_ausente_defaultBrl")
    void moeda_ausente_defaultBrl() {
        // AMB-018: ausente = "BRL" (moeda base). e-010 vem sem moeda; e-001 com "BRL" explicito.
        Entrada e = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-envelope.json"));
        String ausente = e.despesas().stream()
                .filter(d -> d.id().equals("e-010")).findFirst().orElseThrow().moeda();
        String explicito = e.despesas().stream()
                .filter(d -> d.id().equals("e-001")).findFirst().orElseThrow().moeda();
        String estrangeira = e.despesas().stream()
                .filter(d -> d.id().equals("e-002")).findFirst().orElseThrow().moeda();

        assertEquals("BRL", ausente, "moeda ausente vira BRL");
        assertEquals(ausente, explicito, "ausente ≡ explicito (AMB-018)");
        assertEquals("EUR", estrangeira, "moeda informada e preservada como veio — normalizacao e do motor");

        Entrada f = Ingestao.ler(java.nio.file.Path.of("exemplos/despesas-envelope-cc-desconhecido.json"));
        assertEquals("BRL", f.despesas().stream()
                .filter(d -> d.id().equals("f-001")).findFirst().orElseThrow().moeda());
        assertEquals("USD", f.despesas().stream()
                .filter(d -> d.id().equals("f-004")).findFirst().orElseThrow().moeda());
    }

    @Test
    @DisplayName("politicaSemCampoObrigatorio_erroLegivel")
    void politicaSemCampoObrigatorio_erroLegivel() {
        // spec §4: padrao e vigencia sao obrigatorios. assertThrows(IngestaoException) garante
        // erro legivel — se fosse NPE/excecao crua, o teste falharia.
        String semPadrao = """
            {
              "versao": "v4",
              "vigencia": "2026-07-01",
              "moeda_base": "BRL",
              "nota_fiscal_obrigatoria_acima_de": 100.00,
              "acrescimo_em_viagem_percentual": 50
            }
            """;
        IngestaoException exPadrao = assertThrows(IngestaoException.class,
                () -> Ingestao.lerPoliticaDeString(semPadrao));
        assertTrue(exPadrao.getMessage().contains("padrao"),
                "mensagem deve citar o campo ausente: " + exPadrao.getMessage());

        String semVigencia = """
            {
              "versao": "v4",
              "moeda_base": "BRL",
              "padrao": {},
              "nota_fiscal_obrigatoria_acima_de": 100.00,
              "acrescimo_em_viagem_percentual": 50
            }
            """;
        IngestaoException exVigencia = assertThrows(IngestaoException.class,
                () -> Ingestao.lerPoliticaDeString(semVigencia));
        assertTrue(exVigencia.getMessage().contains("vigencia"),
                "mensagem deve citar o campo ausente: " + exVigencia.getMessage());
    }

    @Test
    @DisplayName("politicaMalformada_erroLegivel")
    void politicaMalformada_erroLegivel() {
        // Padrao da T-003: JSON quebrado -> IngestaoException com "JSON" na mensagem
        IngestaoException ex = assertThrows(IngestaoException.class,
                () -> Ingestao.lerPoliticaDeString("{ isto nao e json"));
        assertTrue(ex.getMessage().contains("JSON"),
                "mensagem deve dizer que e erro de JSON: " + ex.getMessage());
    }

    @Test
    @DisplayName("cambioMalformado_erroLegivel")
    void cambioMalformado_erroLegivel() {
        IngestaoException ex = assertThrows(IngestaoException.class,
                () -> Ingestao.lerCambioDeString("{ isto nao e json"));
        assertTrue(ex.getMessage().contains("JSON"),
                "mensagem deve dizer que e erro de JSON: " + ex.getMessage());
    }
}
