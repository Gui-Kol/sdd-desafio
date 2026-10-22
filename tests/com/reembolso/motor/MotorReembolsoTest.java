package com.reembolso.motor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.PoliticaV3;
import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Cambio;
import com.reembolso.io.Entrada;
import com.reembolso.io.Ingestao;
import com.reembolso.io.PoliticaV4;

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
        List<ResultadoDespesa> resultados = new MotorReembolso().processar(entrada, PoliticaV3.politica());
        return resultados.stream().collect(Collectors.toMap(ResultadoDespesa::id, Function.identity()));
    }

    @Test
    @DisplayName("rn001_caseInsensitive_alimentacaoReconhecida")
    void rn001_caseInsensitive_alimentacaoReconhecida() {
        // RN-001 / AMB-009: d-014 usa "ALIMENTACAO" e deve ser tratada como alimentacao,
        // ou seja, NAO pode ser recusada por categoria fora da politica.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d014 = resultado.get("d-014");

        assertEquals(ResultadoDespesa.Status.PARCIAL, d014.status(),
                "categoria 'ALIMENTACAO' deve equivaler a 'alimentacao' e ser reconhecida "
                        + "(parcial, pois 61,00 excede o teto diario de 60,00 da RN-006)");
        assertEquals(new BigDecimal("60.00"), d014.valorReembolsado().saida());
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

        assertEquals(ResultadoDespesa.Status.PARCIAL, resultado.get("d-001").status()); // alimentacao (corta na RN-006)
        assertEquals(ResultadoDespesa.Status.PARCIAL, resultado.get("d-003").status()); // transporte_urbano (corta na RN-006)
        assertEquals(ResultadoDespesa.Status.PARCIAL, resultado.get("d-010").status()); // hospedagem (corta no teto por lancamento, RN-007)
    }

    @Test
    @DisplayName("rn001_trimECase_variacoesNormalizam")
    void rn001_trimECase_variacoesNormalizam() {
        // RN-001 / AMB-009: trim + case-insensitive em qualquer variacao
        assertEquals(Categoria.ALIMENTACAO, Categoria.normalizar("  ALIMENTACAO ").orElseThrow());
        assertEquals(Categoria.ALIMENTACAO, Categoria.normalizar("AlImEnTaCaO").orElseThrow());
        assertEquals(Categoria.TRANSPORTE_URBANO, Categoria.normalizar(" Transporte_Urbano").orElseThrow());
        assertEquals(Categoria.HOSPEDAGEM, Categoria.normalizar("HOSPEDAGEM").orElseThrow());
        // T-019 / D-003: o catalogo virou dado da politica (RN-012) — a
        // normalizacao aceita qualquer nome; estar NA politica e decisao da
        // RN-012, nao da normalizacao
        assertEquals("representacao", Categoria.normalizar("REPRESENTACAO").orElseThrow().chave());
        assertEquals("coworking", Categoria.normalizar("coworking").orElseThrow().chave());
        assertTrue(Categoria.normalizar("").isEmpty());
        assertTrue(Categoria.normalizar(null).isEmpty());
    }

    @Test
    @DisplayName("rn003_despesaAbrilEmJulho_recusada")
    void rn003_despesaAbrilEmJulho_recusada() {
        // RN-003 / AMB-007: d-008 (2026-04-15) num periodo 2026-07-01..2026-07-31
        // e recusada integralmente, sem realocacao de competencia.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d008 = resultado.get("d-008");

        assertEquals(ResultadoDespesa.Status.RECUSADA, d008.status());
        assertEquals(new BigDecimal("0.00"), d008.valorReembolsado().saida());
        assertEquals(new BigDecimal("41.00"), d008.valorSolicitado().saida());
        assertTrue(d008.motivos().contains("Despesa fora do período de competência"),
                "motivo obrigatorio da RN-003: " + d008.motivos());
    }

    @Test
    @DisplayName("rn003_dataNoUltimoDia_elegivel")
    void rn003_dataNoUltimoDia_elegivel() {
        // RN-003: o intervalo e inclusive nas pontas — 2026-07-31 (periodo.fim) passa.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d014 = resultado.get("d-014"); // data 2026-07-31

        org.junit.jupiter.api.Assertions.assertNotEquals(ResultadoDespesa.Status.RECUSADA, d014.status(),
                "despesa no ultimo dia do periodo deve ser elegivel");
        assertTrue(d014.motivos().stream().noneMatch(m -> m.equals("Despesa fora do período de competência")));
    }

    @Test
    @DisplayName("rn003_dataNoPrimeiroDia_elegivel")
    void rn003_dataNoPrimeiroDia_elegivel() {
        // RN-003: fronteira inferior tambem e inclusiva (periodo.inicio = 2026-07-01)
        ResultadoDespesa r = processarUnica("x-1", "2026-07-01", "alimentacao", "50.00");
        assertEquals(ResultadoDespesa.Status.APROVADA, r.status());
    }

    @Test
    @DisplayName("rn003_dataAnteriorAoInicio_recusada")
    void rn003_dataAnteriorAoInicio_recusada() {
        ResultadoDespesa r = processarUnica("x-2", "2026-06-30", "transporte_urbano", "50.00");
        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(new BigDecimal("0.00"), r.valorReembolsado().saida());
        assertEquals(List.of("Despesa fora do período de competência"), r.motivos());
    }

    @Test
    @DisplayName("rn003_dataPosteriorAoFim_recusada")
    void rn003_dataPosteriorAoFim_recusada() {
        ResultadoDespesa r = processarUnica("x-3", "2026-08-01", "hospedagem", "200.00");
        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(List.of("Despesa fora do período de competência"), r.motivos());
    }

    @Test
    @DisplayName("rn003_categoriaForaDaPolitica_temPrecedenciaSobrePeriodo")
    void rn003_categoriaForaDaPolitica_temPrecedenciaSobrePeriodo() {
        // Ordem §8: RN-002 e o passo 1 — despesa fora da politica E fora do periodo
        // morre por categoria, nunca chega ao teste de data.
        ResultadoDespesa r = processarUnica("x-4", "2026-04-15", "coworking", "89.00");
        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(List.of("Categoria fora da política"), r.motivos(),
                "RN-002 (passo 1) tem precedencia sobre RN-003 (passo 2)");
    }

    private ResultadoDespesa processarUnica(String id, String data, String categoria, String valor) {
        Entrada.DespesaIn despesa = new Entrada.DespesaIn(id,
                java.time.LocalDate.parse(data), categoria, "teste", "Fornecedor", valor, "BRL", true);
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(despesa));
        return new MotorReembolso().processar(entrada, PoliticaV3.politica()).get(0);
    }

    @Test
    @DisplayName("rn009_estornoSozinho_diaZeradoSemCredito")
    void rn009_estornoSozinho_diaZeradoSemCredito() {
        // RN-009 / AMB-008: d-009 (-45,00 transporte_urbano em 2026-07-11) e a unica
        // despesa da categoria no dia: soma diaria negativa -> nada e reembolsado
        // naquele dia/categoria, sem gerar credito ao colaborador.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d009 = resultado.get("d-009");

        assertEquals(ResultadoDespesa.Status.RECUSADA, d009.status(),
                "o item estorno em si nunca e reembolsavel por si so");
        assertEquals(new BigDecimal("0.00"), d009.valorReembolsado().saida());
        assertEquals(new BigDecimal("-45.00"), d009.valorSolicitado().saida());
        assertTrue(d009.motivos().contains("Estorno: abatido do total da categoria no dia"),
                "motivo obrigatorio do estorno (AMB-008): " + d009.motivos());
    }

    @Test
    @DisplayName("rn009_estornoAbateSomaDoDia")
    void rn009_estornoAbateSomaDoDia() {
        // RN-009: estorno de -40 abate a soma elegivel do dia: 90 - 40 = 50 reembolsado
        Entrada.DespesaIn despesa = despesa("x-1", "2026-07-10", "alimentacao", "Rest", "90.00");
        Entrada.DespesaIn estorno = despesa("x-2", "2026-07-10", "alimentacao", "Rest", "-40.00");
        List<ResultadoDespesa> rs = processar(despesa, estorno);

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status());
        assertEquals(new BigDecimal("50.00"), rs.get(0).valorReembolsado().saida(),
                "soma elegivel do dia = 90 - 40 = 50");
        assertFalseVazio(rs.get(0));

        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(1).status());
        assertEquals(new BigDecimal("0.00"), rs.get(1).valorReembolsado().saida());
        assertTrue(rs.get(1).motivos().contains("Estorno: abatido do total da categoria no dia"));
    }

    @Test
    @DisplayName("rn009_estornoMaiorQueSoma_naoGeraCredito")
    void rn009_estornoMaiorQueSoma_naoGeraCredito() {
        // RN-009: soma diaria negativa nunca vira devolucao — o colaborador nao paga de volta
        Entrada.DespesaIn despesa = despesa("x-1", "2026-07-10", "transporte_urbano", "TaxiApp", "30.00");
        Entrada.DespesaIn estorno = despesa("x-2", "2026-07-10", "transporte_urbano", "TaxiExt", "-80.00");
        List<ResultadoDespesa> rs = processar(despesa, estorno);

        assertEquals(new BigDecimal("0.00"), rs.get(0).valorReembolsado().saida(),
                "reembolso zero, nunca negativo");
        assertEquals(new BigDecimal("0.00"), rs.get(1).valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn009_estornoNaoAtravessaDiaNemCategoria")
    void rn009_estornoNaoAtravessaDiaNemCategoria() {
        // RN-009: o abatimento e restrito ao (dia, categoria) do estorno
        Entrada.DespesaIn estorno = despesa("x-1", "2026-07-10", "alimentacao", "Rest", "-40.00");
        Entrada.DespesaIn outraCategoria = despesa("x-2", "2026-07-10", "transporte_urbano", "TaxiApp", "50.00");
        Entrada.DespesaIn outroDia = despesa("x-3", "2026-07-11", "alimentacao", "Rest", "50.00");
        List<ResultadoDespesa> rs = processar(estorno, outraCategoria, outroDia);

        assertEquals(new BigDecimal("50.00"), rs.get(1).valorReembolsado().saida(),
                "categoria diferente no mesmo dia nao e abatida");
        assertEquals(new BigDecimal("50.00"), rs.get(2).valorReembolsado().saida(),
                "mesma categoria em outro dia nao e abatida");
    }

    @Test
    @DisplayName("rn009_estornoConsomeEmOrdemDeEntrada")
    void rn009_estornoConsomeEmOrdemDeEntrada() {
        // RN-009 + AMB-012: a soma elegivel do dia e consumida em ordem de entrada
        Entrada.DespesaIn primeira = despesa("x-1", "2026-07-10", "alimentacao", "Rest A", "30.00");
        Entrada.DespesaIn estorno = despesa("x-2", "2026-07-10", "alimentacao", "Rest B", "-40.00");
        Entrada.DespesaIn segunda = despesa("x-3", "2026-07-10", "alimentacao", "Rest C", "50.00");
        List<ResultadoDespesa> rs = processar(primeira, estorno, segunda);

        // soma elegivel do dia = 30 + 50 - 40 = 40; primeira consome 30; segunda recebe 10
        assertEquals(new BigDecimal("30.00"), rs.get(0).valorReembolsado().saida());
        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(2).status());
        assertEquals(new BigDecimal("10.00"), rs.get(2).valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn009_estornoDuplicado_recusadoComoDuplicata")
    void rn009_estornoDuplicado_recusadoComoDuplicata() {
        // Ordem §8: RN-004 (passo 3) vem antes de RN-009 (passo 4); estorno duplicado
        // morre como duplicata e so abate uma vez a soma do dia.
        Entrada.DespesaIn despesa = despesa("x-1", "2026-07-10", "alimentacao", "Rest", "90.00");
        Entrada.DespesaIn estorno1 = despesa("x-2", "2026-07-10", "alimentacao", "Rest", "-40.00");
        Entrada.DespesaIn estorno2 = despesa("x-3", "2026-07-10", "alimentacao", "Rest", "-40.00");
        List<ResultadoDespesa> rs = processar(despesa, estorno1, estorno2);

        assertTrue(rs.get(2).motivos().contains("Duplicata da despesa x-2"));
        assertEquals(new BigDecimal("50.00"), rs.get(0).valorReembolsado().saida(),
                "somente um estorno abate: 90 - 40 = 50");
    }

    private void assertFalseVazio(ResultadoDespesa r) {
        org.junit.jupiter.api.Assertions.assertFalse(r.motivos().isEmpty(),
                "RN-011: toda decisao carrega ao menos um motivo");
    }

    @Test
    @DisplayName("rn004_duplicataExata_segundaRecusada")
    void rn004_duplicataExata_segundaRecusada() {
        // RN-004 / AMB-006: d-006 e d-007 coincidem em data, categoria, fornecedor e valor.
        // A primeira (d-006) permanece elegivel; d-007 e recusada citando d-006.
        Map<String, ResultadoDespesa> resultado = processarExemplo();

        ResultadoDespesa d006 = resultado.get("d-006");
        assertEquals(ResultadoDespesa.Status.APROVADA, d006.status());
        assertEquals(new BigDecimal("54.90"), d006.valorReembolsado().saida());

        ResultadoDespesa d007 = resultado.get("d-007");
        assertEquals(ResultadoDespesa.Status.RECUSADA, d007.status());
        assertEquals(new BigDecimal("0.00"), d007.valorReembolsado().saida());
        assertEquals(new BigDecimal("54.90"), d007.valorSolicitado().saida());
        assertTrue(d007.motivos().contains("Duplicata da despesa d-006"),
                "motivo da RN-004 deve citar a original: " + d007.motivos());
    }

    @Test
    @DisplayName("rn004_valorDiferente_naoDuplica")
    void rn004_valorDiferente_naoDuplica() {
        // RN-004: mesma data/categoria/fornecedor, mas valores a mais de R$ 0,01 -> nao duplica
        ResultadoDespesa[] rs = processarParDuplicata("54.90", "55.00");
        assertEquals(ResultadoDespesa.Status.APROVADA, rs[0].status());
        assertEquals(ResultadoDespesa.Status.APROVADA, rs[1].status(),
                "diferenca acima de R$ 0,01 nao configura duplicata");
    }

    @Test
    @DisplayName("rn004_chaveUsaValorArredondado")
    void rn004_chaveUsaValorArredondado() {
        // RN-004 + RN-010: 33,333 arredonda para 33,33 na entrada; portanto
        // 33,333 e 33,33 colidem na chave de duplicata (DT-002).
        ResultadoDespesa[] rs = processarParDuplicata("33.333", "33.33");
        assertEquals(ResultadoDespesa.Status.APROVADA, rs[0].status());
        assertEquals(ResultadoDespesa.Status.RECUSADA, rs[1].status());
        assertTrue(rs[1].motivos().contains("Duplicata da despesa x-1"),
                "segundo item recusado citando o primeiro: " + rs[1].motivos());
    }

    @Test
    @DisplayName("rn004_diferencaDeUmCentavo_naoDuplica")
    void rn004_diferencaDeUmCentavo_naoDuplica() {
        // RN-004 + §7 (linha d-004): diferenca visivel de 1 centavo NAO e "o mesmo
        // valor" — 100,00 e 100,01 sao despesas distintas (d-004 morre por nota).
        Entrada.DespesaIn a = new Entrada.DespesaIn("x-1", java.time.LocalDate.of(2026, 7, 6),
                "transporte_urbano", "teste", "TaxiApp", "100.00", "BRL", false);
        Entrada.DespesaIn b = new Entrada.DespesaIn("x-2", java.time.LocalDate.of(2026, 7, 6),
                "transporte_urbano", "teste", "TaxiApp", "100.01", "BRL", false);
        List<ResultadoDespesa> rs = processar(a, b);
        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status(),
                "100,00 sem nota passa na RN-005 e e cortada no teto diario de 80,00 (RN-006)");
        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(1).status());
        assertTrue(rs.get(1).motivos().contains("Despesa acima de R$ 100,00 sem nota fiscal"),
                "recusada por nota (RN-005), nao por duplicata: " + rs.get(1).motivos());
    }

    @Test
    @DisplayName("rn004_fornecedorDiferente_naoDuplica")
    void rn004_fornecedorDiferente_naoDuplica() {
        // RN-004: fornecedor faz parte da chave
        Entrada.DespesaIn a = despesa("x-1", "2026-07-09", "hospedagem", "Bistro A", "54.90");
        Entrada.DespesaIn b = despesa("x-2", "2026-07-09", "hospedagem", "Bistro B", "54.90");
        List<ResultadoDespesa> rs = processar(a, b);
        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(1).status());
    }

    @Test
    @DisplayName("rn004_categoriaNormalizadaNaChave")
    void rn004_categoriaNormalizadaNaChave() {
        // RN-004 + RN-001: a chave usa a categoria normalizada; "ALIMENTACAO" duplica "alimentacao"
        Entrada.DespesaIn a = despesa("x-1", "2026-07-09", "alimentacao", "Bistro", "54.90");
        Entrada.DespesaIn b = despesa("x-2", "2026-07-09", "ALIMENTACAO", "Bistro", "54.90");
        List<ResultadoDespesa> rs = processar(a, b);
        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(1).status());
        assertTrue(rs.get(1).motivos().contains("Duplicata da despesa x-1"));
    }

    @Test
    @DisplayName("rn004_datasDiferentes_naoDuplica")
    void rn004_datasDiferentes_naoDuplica() {
        // RN-004: data faz parte da chave
        Entrada.DespesaIn a = despesa("x-1", "2026-07-09", "alimentacao", "Bistro", "54.90");
        Entrada.DespesaIn b = despesa("x-2", "2026-07-10", "alimentacao", "Bistro", "54.90");
        List<ResultadoDespesa> rs = processar(a, b);
        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(1).status());
    }

    @Test
    @DisplayName("rn004_originalJaRecusada_naoGeraDuplicata")
    void rn004_originalJaRecusada_naoGeraDuplicata() {
        // §8: duplicata so e detectada contra itens que sobreviveram aos passos anteriores.
        // Primeiro item fora do periodo (RN-003) nao pode ser "a original" de uma duplicata.
        Entrada.DespesaIn foraDoPeriodo = despesa("x-1", "2026-04-09", "alimentacao", "Bistro", "54.90");
        Entrada.DespesaIn dentroDoPeriodo = despesa("x-2", "2026-07-09", "alimentacao", "Bistro", "54.90");
        List<ResultadoDespesa> rs = processar(foraDoPeriodo, dentroDoPeriodo);
        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(0).status());
        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(1).status(),
                "item elegivel nao e duplicata de item ja recusado");
    }

    @Test
    @DisplayName("rn004_terceiraOcorrencia_citaAPrimeira")
    void rn004_terceiraOcorrencia_citaAPrimeira() {
        // RN-004: todas exceto a primeira sao recusadas; a citacao aponta a primeira da entrada
        Entrada.DespesaIn a = despesa("x-1", "2026-07-09", "alimentacao", "Bistro", "54.90");
        Entrada.DespesaIn b = despesa("x-2", "2026-07-09", "alimentacao", "Bistro", "54.90");
        Entrada.DespesaIn c = despesa("x-3", "2026-07-09", "alimentacao", "Bistro", "54.90");
        List<ResultadoDespesa> rs = processar(a, b, c);
        assertTrue(rs.get(2).motivos().contains("Duplicata da despesa x-1"),
                "a original permanece sendo a primeira da ordem de entrada: " + rs.get(2).motivos());
    }

    // hospedagem: isenta da agregacao diaria da RN-006 (limite e por lancamento, RN-007),
    // o que isola estes testes do efeito do teto diario — o foco aqui e a chave de duplicata
    private ResultadoDespesa[] processarParDuplicata(String valor1, String valor2) {
        Entrada.DespesaIn a = despesa("x-1", "2026-07-09", "hospedagem", "Bistro", valor1);
        Entrada.DespesaIn b = despesa("x-2", "2026-07-09", "hospedagem", "Bistro", valor2);
        List<ResultadoDespesa> rs = processar(a, b);
        return new ResultadoDespesa[]{rs.get(0), rs.get(1)};
    }

    private Entrada.DespesaIn despesa(String id, String data, String categoria,
                                      String fornecedor, String valor) {
        return new Entrada.DespesaIn(id, java.time.LocalDate.parse(data), categoria,
                "teste", fornecedor, valor, "BRL", true);
    }

    private List<ResultadoDespesa> processar(Entrada.DespesaIn... despesas) {
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(despesas));
        return new MotorReembolso().processar(entrada, PoliticaV3.politica());
    }

    @Test
    @DisplayName("rn005_fronteiraCemReaisSemNota_naoExige")
    void rn005_fronteiraCemReaisSemNota_naoExige() {
        // RN-005 / AMB-003: fronteira estrita — exatamente R$ 100,00 sem nota NAO exige.
        // d-003 (100,00, tem_nota_fiscal=false) permanece elegivel.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d003 = resultado.get("d-003");

        // RN-006: a soma elegivel do dia (100,00) e cortada no teto de 80,00 — mas
        // a despesa NAO foi recusada por falta de nota, que e o ponto da fronteira
        assertEquals(ResultadoDespesa.Status.PARCIAL, d003.status());
        assertEquals(new BigDecimal("80.00"), d003.valorReembolsado().saida());
        assertTrue(d003.motivos().stream().noneMatch(m -> m.equals("Despesa acima de R$ 100,00 sem nota fiscal")));
    }

    @Test
    @DisplayName("rn005_cemVirgulaUmSemNota_recusa")
    void rn005_cemVirgulaUmSemNota_recusa() {
        // RN-005 / AMB-004: R$ 100,01 sem nota -> recusa integral, mesmo com outra
        // corrida no mesmo dia (a exigencia e por despesa, nao por total do dia).
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d004 = resultado.get("d-004");

        assertEquals(ResultadoDespesa.Status.RECUSADA, d004.status());
        assertEquals(new BigDecimal("0.00"), d004.valorReembolsado().saida());
        assertEquals(new BigDecimal("100.01"), d004.valorSolicitado().saida());
        assertTrue(d004.motivos().contains("Despesa acima de R$ 100,00 sem nota fiscal"),
                "motivo obrigatorio da RN-005: " + d004.motivos());
    }

    @Test
    @DisplayName("rn005_hospedagem690SemNota_recusaPorNota")
    void rn005_hospedagem690SemNota_recusaPorNota() {
        // RN-005 + ordem §8: d-013 (hospedagem 690,00 sem nota) morre POR NOTA no
        // passo 5, antes de qualquer limite (passo 7) — motivo de nota, nao de teto.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d013 = resultado.get("d-013");

        assertEquals(ResultadoDespesa.Status.RECUSADA, d013.status());
        assertEquals(new BigDecimal("0.00"), d013.valorReembolsado().saida());
        assertEquals(List.of("Despesa acima de R$ 100,00 sem nota fiscal"), d013.motivos(),
                "a ordem do §8 garante motivo de nota, nunca de limite: " + d013.motivos());
    }

    @Test
    @DisplayName("rn005_acimaDeCemComNota_elegivel")
    void rn005_acimaDeCemComNota_elegivel() {
        // RN-005: nota presente desarma a exigencia, qualquer que seja o valor
        Entrada.DespesaIn comNota = new Entrada.DespesaIn("x-1",
                java.time.LocalDate.of(2026, 7, 10), "hospedagem", "teste", "Hotel", "500.00", "BRL", true);
        List<ResultadoDespesa> rs = processar(comNota);
        org.junit.jupiter.api.Assertions.assertNotEquals(ResultadoDespesa.Status.RECUSADA, rs.get(0).status(),
                "com nota, 500,00 nao e recusada por RN-005");
        assertEquals(new BigDecimal("250.00"), rs.get(0).valorReembolsado().saida(),
                "o corte posterior e o teto por lancamento da RN-007, nao falta de nota");
        org.junit.jupiter.api.Assertions.assertTrue(
                rs.get(0).motivos().stream().noneMatch(m -> m.equals("Despesa acima de R$ 100,00 sem nota fiscal")));
    }

    @Test
    @DisplayName("rn005_cemComUmCentavoAbaixo_semNota_elegivel")
    void rn005_cemComUmCentavoAbaixo_semNota_elegivel() {
        // AMB-003: estritamente maior — 99,99 sem nota e elegivel
        // (hospedagem: isenta da RN-006 para o teste ficar focado so na fronteira de nota)
        ResultadoDespesa r = processarUnicaSemNota("x-2", "2026-07-10", "hospedagem", "99.99");
        assertEquals(ResultadoDespesa.Status.APROVADA, r.status());
        assertEquals(new BigDecimal("99.99"), r.valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn005_EstornoNegativoSemNota_naoExigeNota")
    void rn005_EstornoNegativoSemNota_naoExigeNota() {
        // RN-009 + RN-005 (ordem §8): estorno nao exige nota fiscal — o passo 4
        // recusa por estorno antes do passo 5 testar o gatilho de nota.
        ResultadoDespesa r = processarUnicaSemNota("x-3", "2026-07-10", "transporte_urbano", "-150.00");
        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(List.of("Estorno: abatido do total da categoria no dia"), r.motivos(),
                "estorno morre por estorno, nunca por falta de nota");
    }

    private ResultadoDespesa processarUnicaSemNota(String id, String data, String categoria, String valor) {
        Entrada.DespesaIn despesa = new Entrada.DespesaIn(id,
                java.time.LocalDate.parse(data), categoria, "teste", "Fornecedor", valor, "BRL", false);
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(despesa));
        return new MotorReembolso().processar(entrada, PoliticaV3.politica()).get(0);
    }

    @Test
    @DisplayName("rn006_duasAlimentacoesMesmoDia_cortaExcedenteEmOrdem")
    void rn006_duasAlimentacoesMesmoDia_cortaExcedenteEmOrdem() {
        // RN-006 / AMB-001 / AMB-002: limite diario de alimentacao (60,00) aplica-se
        // a soma do dia (72,50 + 38,00 = 110,50 > 60). A cota e consumida em ordem
        // de entrada: a primeira recebe 60,00 (o que resta ate o teto); a segunda 0,00.
        Entrada.DespesaIn primeira = despesa("x-1", "2026-07-10", "alimentacao", "Rest A", "72.50");
        Entrada.DespesaIn segunda = despesa("x-2", "2026-07-10", "alimentacao", "Rest B", "38.00");
        List<ResultadoDespesa> rs = processar(primeira, segunda);

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status());
        assertEquals(new BigDecimal("60.00"), rs.get(0).valorReembolsado().saida(),
                "primeira despesa recebe o limite diario restante");

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(1).status());
        assertEquals(new BigDecimal("0.00"), rs.get(1).valorReembolsado().saida(),
                "segunda despesa do dia recebe zero — cota esgotada pela primeira");
    }

    @Test
    @DisplayName("rn006_limiteDiarioTransporte_80porDia")
    void rn006_limiteDiarioTransporte_80porDia() {
        // RN-006: transporte_urbano tem teto diario proprio de R$ 80,00
        Entrada.DespesaIn corrida = despesa("x-1", "2026-07-10", "transporte_urbano", "TaxiApp", "100.00");
        List<ResultadoDespesa> rs = processar(corrida);

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status());
        assertEquals(new BigDecimal("80.00"), rs.get(0).valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn006_diasDiferentes_naoSomam")
    void rn006_diasDiferentes_naoSomam() {
        // RN-006: a agregacao e por dia — 50 de alimentacao em dias distintos
        // nao dividem a mesma cota
        Entrada.DespesaIn dia1 = despesa("x-1", "2026-07-10", "alimentacao", "Rest", "50.00");
        Entrada.DespesaIn dia2 = despesa("x-2", "2026-07-11", "alimentacao", "Rest", "50.00");
        List<ResultadoDespesa> rs = processar(dia1, dia2);

        assertEquals(new BigDecimal("50.00"), rs.get(0).valorReembolsado().saida());
        assertEquals(new BigDecimal("50.00"), rs.get(1).valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn006_recusadaNaoGastaCota")
    void rn006_recusadaNaoGastaCota() {
        // RN-006 / AMB-012: despesa recusada (d-004, 100,01 sem nota) NAO entra na
        // soma elegivel do dia — a cota de transporte do d-003 depende so dele:
        // 100,00 elegiveis cortados no teto diario de 80,00.
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d003 = resultado.get("d-003");
        ResultadoDespesa d004 = resultado.get("d-004");

        assertEquals(ResultadoDespesa.Status.RECUSADA, d004.status());
        assertEquals(ResultadoDespesa.Status.PARCIAL, d003.status());
        assertEquals(new BigDecimal("80.00"), d003.valorReembolsado().saida(),
                "unica despesa elegivel do dia: 100,00 cortada no teto de 80,00");
        assertTrue(d003.motivos().contains(MotorReembolso.MOTIVO_CORTE_LIMITE_DIARIO),
                "o corte e por limite, nao por estorno: " + d003.motivos());
    }

    @Test
    @DisplayName("rn006_statusParcialQuandoCortado")
    void rn006_statusParcialQuandoCortado() {
        // RN-006 / AMB-002: toda despesa cortada pelo limite sai com status parcial.
        // No exemplo: d-001 (72,50) cortado em 60,00 e d-002 (38,00) cortado em 0,00,
        // ambos no dia 2026-07-03 (soma 110,50 > 60).
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d001 = resultado.get("d-001");
        ResultadoDespesa d002 = resultado.get("d-002");

        assertEquals(ResultadoDespesa.Status.PARCIAL, d001.status());
        assertEquals(new BigDecimal("60.00"), d001.valorReembolsado().saida());
        assertEquals(ResultadoDespesa.Status.PARCIAL, d002.status());
        assertEquals(new BigDecimal("0.00"), d002.valorReembolsado().saida());
        assertTrue(d001.motivos().contains(MotorReembolso.MOTIVO_CORTE_LIMITE_DIARIO));
        assertTrue(d002.motivos().contains(MotorReembolso.MOTIVO_CORTE_LIMITE_DIARIO));
    }

    @Test
    @DisplayName("rn006_estornoAbatAntesDoLimite")
    void rn006_estornoAbatAntesDoLimite() {
        // RN-009 + RN-006 (ordem §8): o estorno abate a soma elegivel antes do
        // teto — 90 - 40 = 50 fica abaixo de 60 e nao ha corte adicional
        Entrada.DespesaIn despesa = despesa("x-1", "2026-07-10", "alimentacao", "Rest A", "90.00");
        Entrada.DespesaIn estorno = despesa("x-2", "2026-07-10", "alimentacao", "Rest B", "-40.00");
        List<ResultadoDespesa> rs = processar(despesa, estorno);

        assertEquals(new BigDecimal("50.00"), rs.get(0).valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn007_hospedagem480_cortaEm250")
    void rn007_hospedagem480_cortaEm250() {
        // RN-007 / AMB-010: d-010 (hospedagem 480,00 com nota) e cortada no limite
        // por lancamento de R$ 250,00 -> parcial 250,00
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d010 = resultado.get("d-010");

        assertEquals(ResultadoDespesa.Status.PARCIAL, d010.status());
        assertEquals(new BigDecimal("480.00"), d010.valorSolicitado().saida());
        assertEquals(new BigDecimal("250.00"), d010.valorReembolsado().saida());
        assertTrue(d010.motivos().contains(MotorReembolso.MOTIVO_CORTE_LIMITE_LANCAMENTO),
                "motivo obrigatorio do corte RN-007: " + d010.motivos());
    }

    @Test
    @DisplayName("rn007_hospedagemNaoAgregaPorDia")
    void rn007_hospedagemNaoAgregaPorDia() {
        // RN-007: o limite NAO e diario agregado — dois lancamentos de hospedagem no
        // mesmo dia nao dividem cota; cada um e cortado no seu proprio teto de 250,00
        Entrada.DespesaIn primeira = despesa("x-1", "2026-07-10", "hospedagem", "Hotel A", "200.00");
        Entrada.DespesaIn segunda = despesa("x-2", "2026-07-10", "hospedagem", "Hotel B", "200.00");
        List<ResultadoDespesa> rs = processar(primeira, segunda);

        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(0).status());
        assertEquals(new BigDecimal("200.00"), rs.get(0).valorReembolsado().saida());
        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(1).status());
        assertEquals(new BigDecimal("200.00"), rs.get(1).valorReembolsado().saida(),
                "segundo lancamento reembolsado integralmente — hospedagem nao agrega por dia");
    }

    @Test
    @DisplayName("rn007_cadaLancamentoCortadoNoProprioTeto")
    void rn007_cadaLancamentoCortadoNoProprioTeto() {
        // RN-007: lancamentos acima e abaixo do teto no mesmo dia — so o excedente
        // de cada um e cortado; o abaixo do teto passa integral
        Entrada.DespesaIn acima = despesa("x-1", "2026-07-10", "hospedagem", "Hotel A", "300.00");
        Entrada.DespesaIn abaixo = despesa("x-2", "2026-07-10", "hospedagem", "Hotel B", "100.00");
        List<ResultadoDespesa> rs = processar(acima, abaixo);

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status());
        assertEquals(new BigDecimal("250.00"), rs.get(0).valorReembolsado().saida());
        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(1).status());
        assertEquals(new BigDecimal("100.00"), rs.get(1).valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn007_hospedagemExataNoLimite_integral")
    void rn007_hospedagemExataNoLimite_integral() {
        // RN-007: fronteira — exatamente 250,00 nao e cortada
        ResultadoDespesa r = processarUnica("x-1", "2026-07-10", "hospedagem", "250.00");
        assertEquals(ResultadoDespesa.Status.APROVADA, r.status());
        assertEquals(new BigDecimal("250.00"), r.valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn011_nenhumMotivoVazio")
    void rn011_nenhumMotivoVazio() {
        // RN-011: todas as 14 despesas do exemplo saem com ao menos um motivo
        // legivel — aprovacao integral, corte parcial ou recusa, nunca vazio
        Map<String, ResultadoDespesa> resultado = processarExemplo();

        assertEquals(14, resultado.size(), "o exemplo tem 14 despesas");
        for (ResultadoDespesa r : resultado.values()) {
            org.junit.jupiter.api.Assertions.assertFalse(r.motivos().isEmpty(),
                    "RN-011 violada: despesa " + r.id() + " sem motivo");
            for (String motivo : r.motivos()) {
                assertTrue(!motivo.isBlank(),
                        "motivo ilegivel em " + r.id() + ": [" + motivo + "]");
            }
        }
    }

    @Test
    @DisplayName("rn011_motivoVazio_proibidoNoModelo")
    void rn011_motivoVazio_proibidoNoModelo() {
        // RN-011: a garantia e estrutural — nem o modelo aceita decisao sem motivo
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ResultadoDespesa("x-1", ResultadoDespesa.Status.APROVADA,
                        com.reembolso.dominio.Dinheiro.de("10.00"),
                        com.reembolso.dominio.Dinheiro.de("10.00"),
                        java.util.List.of(),
                        null));
    }

    @Test
    @DisplayName("rn010_saidaDuasCasasDecimais")
    void rn010_saidaDuasCasasDecimais() {
        // RN-010 / AMB-011: d-011 entra como 33,333 e sai 33,33; TODO campo
        // monetario da saida do exemplo tem exatamente 2 casas decimais
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d011 = resultado.get("d-011");

        assertEquals(new BigDecimal("33.33"), d011.valorReembolsado().saida(),
                "33,333 arredonda HALF_UP para 33,33 na saida");
        assertEquals(2, d011.valorReembolsado().saida().scale());

        for (ResultadoDespesa r : resultado.values()) {
            assertEquals(2, r.valorSolicitado().saida().scale(),
                    "valor_solicitado com 2 casas em " + r.id());
            assertEquals(2, r.valorReembolsado().saida().scale(),
                    "valor_reembolsado com 2 casas em " + r.id());
        }

        // totais: somar campos da saida nunca perde a escala de 2 casas
        com.reembolso.dominio.Dinheiro totalSolicitado = com.reembolso.dominio.Dinheiro.ZERO;
        com.reembolso.dominio.Dinheiro totalReembolsado = com.reembolso.dominio.Dinheiro.ZERO;
        for (ResultadoDespesa r : resultado.values()) {
            totalSolicitado = totalSolicitado.adicionar(r.valorSolicitado());
            totalReembolsado = totalReembolsado.adicionar(r.valorReembolsado());
        }
        assertEquals(2, totalSolicitado.saida().scale(), "total_solicitado com 2 casas");
        assertEquals(2, totalReembolsado.saida().scale(), "total_reembolsado com 2 casas");
    }

    @Test
    @DisplayName("amb013_sabadoPlantao_elegivel")
    void amb013_sabadoPlantao_elegivel() {
        // AMB-013: a politica nao distingue dia da semana — d-012 (sabado, plantao,
        // 47,20 alimentacao) e elegivel normalmente dentro do periodo
        Map<String, ResultadoDespesa> resultado = processarExemplo();
        ResultadoDespesa d012 = resultado.get("d-012");

        assertEquals(java.time.DayOfWeek.SATURDAY,
                java.time.LocalDate.of(2026, 7, 18).getDayOfWeek(),
                "2026-07-18 e sabado");
        assertEquals(ResultadoDespesa.Status.APROVADA, d012.status());
        assertEquals(new BigDecimal("47.20"), d012.valorReembolsado().saida());
        assertTrue(d012.motivos().contains("Aprovada integralmente"));
    }

    @Test
    @DisplayName("rn009_rn006_estornoSobreCotaJaCortada_semCorteNemCredito")
    void rn006_rn009_estornoSobreCotaJaCortada_semCorteNemCredito() {
        // Tabela §7 "Estorno sobre cota ja cortada": alimentacao 90 + estorno -40
        // no mesmo dia -> soma elegivel = 50 (< teto de 60), portanto SEM corte
        // por limite e SEM credito residual ao colaborador.
        Entrada.DespesaIn despesa = despesa("x-1", "2026-07-10", "alimentacao", "Rest", "90.00");
        Entrada.DespesaIn estorno = despesa("x-2", "2026-07-10", "alimentacao", "Rest", "-40.00");
        List<ResultadoDespesa> rs = processar(despesa, estorno);

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status());
        assertEquals(new BigDecimal("50.00"), rs.get(0).valorReembolsado().saida(),
                "soma elegivel = 90 - 40 = 50, abaixo do teto: o unico efeito e o abatimento");
        assertTrue(rs.get(0).motivos().contains(MotorReembolso.MOTIVO_REDUZIDA_POR_ESTORNO),
                "motivo de estorno, nunca de teto: " + rs.get(0).motivos());
        assertTrue(rs.get(0).motivos().stream()
                        .noneMatch(m -> m.equals(MotorReembolso.MOTIVO_CORTE_LIMITE_DIARIO)),
                "sem corte por limite — a soma (50) nao ultrapassou o teto (60)");
        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(1).status());
        assertEquals(new BigDecimal("0.00"), rs.get(1).valorReembolsado().saida(),
                "sem credito residual: nada volta ao colaborador");
    }

    @Test
    @DisplayName("rn008_multiplosLimitesEmViagem_hospedagem400cortaEm375")
    void rn007_rn008_multiplosLimitesEmViagem_hospedagem400cortaEm375() {
        // Tabela §7 "Multiplos limites ampliados": em_viagem = true, hospedagem
        // 400,00 -> parcial em 375,00 (teto por lancamento +50%, RN-007 + RN-008);
        // e alimentacao 90,00 no dia passa integral (teto diario 60 -> 90)
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), true),
                List.of(
                        despesa("x-1", "2026-07-10", "hospedagem", "Hotel", "400.00"),
                        despesa("x-2", "2026-07-10", "alimentacao", "Rest", "90.00")));
        List<ResultadoDespesa> rs = new MotorReembolso().processar(entrada, PoliticaV3.politica());

        assertEquals(ResultadoDespesa.Status.PARCIAL, rs.get(0).status());
        assertEquals(new BigDecimal("375.00"), rs.get(0).valorReembolsado().saida(),
                "RN-007 + RN-008: teto por lancamento ampliado 250 -> 375");
        assertTrue(rs.get(0).motivos().contains(MotorReembolso.MOTIVO_CORTE_LIMITE_LANCAMENTO));

        assertEquals(ResultadoDespesa.Status.APROVADA, rs.get(1).status());
        assertEquals(new BigDecimal("90.00"), rs.get(1).valorReembolsado().saida(),
                "RN-006 + RN-008: teto diario ampliado 60 -> 90 — sem viagem seria cortado em 60");
    }

    @Test
    @DisplayName("rn002_todaCategoriaForaDoCatalogo_recusaIntegral")
    void rn002_todaCategoriaForaDoCatalogo_recusaIntegral() {
        // RN-002: recusa e integral (reembolso zero), qualquer que seja o valor
        Entrada.DespesaIn coworking = new Entrada.DespesaIn("x-1",
                java.time.LocalDate.of(2026, 7, 10), "coworking", "teste", "HubOffice", "1500.99", "BRL", true);
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07", java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(coworking));

        ResultadoDespesa r = new MotorReembolso().processar(entrada, PoliticaV3.politica()).get(0);

        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(new BigDecimal("0.00"), r.valorReembolsado().saida());
        assertEquals(List.of("Categoria fora da política"), r.motivos(),
                "RN-011: recusa carrega motivo legivel");
    }

    // ---------------------------------------------------------------- T-019 (D-003): RN-012

    /** A política v4 real do envelope — overrides de centro de custo e categoria `representacao`. */
    private Politica politicaV4Real() {
        return Politica.de(Ingestao.lerPolitica(java.nio.file.Path.of("exemplos/politica-v4.json")));
    }

    /** Política alternativa (padrão 60/80/250) com gatilho de nota e acréscimo de viagem parametrizáveis. */
    private Politica politicaAlternativa(String gatilho, int acrescimoPercentual) {
        Map<String, PoliticaV4.LimiteCategoria> padrao = Map.of(
                "alimentacao", new PoliticaV4.LimiteCategoria(Dinheiro.de("60.00"), "dia", null),
                "transporte_urbano", new PoliticaV4.LimiteCategoria(Dinheiro.de("80.00"), "dia", null),
                "hospedagem", new PoliticaV4.LimiteCategoria(Dinheiro.de("250.00"), "diaria", null));
        return Politica.de(new PoliticaV4("alternativa", java.time.LocalDate.of(2026, 1, 1), "BRL",
                padrao, Map.of(), Dinheiro.de(gatilho), BigDecimal.valueOf(acrescimoPercentual)));
    }

    /** Processa sob a política informada, com centro de custo e viagem explícitos (sem câmbio). */
    private List<ResultadoDespesa> processarCom(Politica politica, String centroCusto,
                                                 boolean emViagem, Entrada.DespesaIn... despesas) {
        return processarCom(politica, null, centroCusto, emViagem, despesas);
    }

    @Test
    @DisplayName("rn012_categoriaAusenteNoPadrao_recusada")
    void rn012_categoriaAusenteNoPadrao_recusada() {
        // AMB-014 / f-003: CC-SUPORTE-N2 não tem bloco na política v4 — resolve
        // pelo padrao; representacao só existe no bloco do CC-COMERCIAL, portanto
        // é recusada como categoria fora da política (RN-002).
        ResultadoDespesa r = processarCom(politicaV4Real(), "CC-SUPORTE-N2", false,
                despesa("x-1", "2026-07-16", "representacao", "Casa Trindade", "190.00")).get(0);

        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(new BigDecimal("0.00"), r.valorReembolsado().saida());
        assertEquals(new BigDecimal("190.00"), r.valorSolicitado().saida());
        assertEquals(List.of("Categoria fora da política"), r.motivos());
    }

    @Test
    @DisplayName("rn012_limiteZero_naoReembolsavel")
    void rn012_limiteZero_naoReembolsavel() {
        // AMB-015: CC-ENG-PLATAFORMA declara hospedagem com limite 0,00
        // ("nao reembolsavel") — recusa com motivo próprio, e a viagem NÃO
        // transforma proibição em permissão (0 × 1,5 = 0).
        ResultadoDespesa r = processarCom(politicaV4Real(), "CC-ENG-PLATAFORMA", true,
                despesa("x-1", "2026-07-10", "hospedagem", "Pousada do Vale", "310.00")).get(0);

        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status());
        assertEquals(new BigDecimal("0.00"), r.valorReembolsado().saida());
        assertTrue(r.motivos().contains(MotorReembolso.MOTIVO_CATEGORIA_NAO_REEMBOLSAVEL),
                "motivo próprio da proibição (igualdade de elemento — List.contains não é String.contains): "
                        + r.motivos());
    }

    @Test
    @DisplayName("amb020_semEmViagem_semAcrescimo")
    void amb020_semEmViagem_semAcrescimo() {
        // AMB-020 / D-003: sem `em_viagem`, o limite do CC-COMERCIAL vale como
        // está — alimentação de 100,00 é cortada em 90,00, não em 135,00.
        ResultadoDespesa r = processarCom(politicaV4Real(), "CC-COMERCIAL", false,
                despesa("x-1", "2026-07-10", "alimentacao", "Rest", "100.00")).get(0);

        assertEquals(ResultadoDespesa.Status.PARCIAL, r.status());
        assertEquals(new BigDecimal("90.00"), r.valorReembolsado().saida(),
                "limite do CC (90,00), não o ampliado em viagem (135,00)");
    }

    @Test
    @DisplayName("rn005_gatilhoLidoDaPolitica")
    void rn005_gatilhoLidoDaPolitica() {
        // D-003 / RN-005: o gatilho da nota é dado da política — com gatilho
        // 80,00: R$ 80,00 sem nota é elegível (fronteira estrita, AMB-003) e
        // R$ 80,01 é recusada com motivo citando o gatilho vigente.
        Politica gatilho80 = politicaAlternativa("80.00", 50);

        Entrada.DespesaIn oitenta = new Entrada.DespesaIn("x-1", java.time.LocalDate.parse("2026-07-10"),
                "hospedagem", "teste", "Hotel", "80.00", "BRL", false);
        ResultadoDespesa r80 = processarCom(gatilho80, "CC", false, oitenta).get(0);
        assertEquals(ResultadoDespesa.Status.APROVADA, r80.status(),
                "exatamente o gatilho não exige nota (AMB-003)");
        assertEquals(new BigDecimal("80.00"), r80.valorReembolsado().saida());

        Entrada.DespesaIn oitentaUm = new Entrada.DespesaIn("x-2", java.time.LocalDate.parse("2026-07-10"),
                "hospedagem", "teste", "Hotel", "80.01", "BRL", false);
        ResultadoDespesa r81 = processarCom(gatilho80, "CC", false, oitentaUm).get(0);
        assertEquals(ResultadoDespesa.Status.RECUSADA, r81.status());
        assertTrue(r81.motivos().contains("Despesa acima de R$ 80,00 sem nota fiscal"),
                "motivo dinâmico com o gatilho da política: " + r81.motivos());
    }

    @Test
    @DisplayName("rn008_percentualLidoDaPolitica")
    void rn008_percentualLidoDaPolitica() {
        // D-003 / RN-008: o acréscimo de viagem é dado da política — com
        // acréscimo 100%, alimentação de 110,00 em viagem é aprovada integral
        // (teto 60 -> 120); sem viagem seria cortada em 60,00.
        Politica acrescimo100 = politicaAlternativa("100.00", 100);

        ResultadoDespesa emViagem = processarCom(acrescimo100, "CC", true,
                despesa("x-1", "2026-07-10", "alimentacao", "Rest", "110.00")).get(0);
        assertEquals(ResultadoDespesa.Status.APROVADA, emViagem.status());
        assertEquals(new BigDecimal("110.00"), emViagem.valorReembolsado().saida(),
                "60 × 2,0 = 120 — 110,00 cabe no teto ampliado");

        ResultadoDespesa semViagem = processarCom(acrescimo100, "CC", false,
                despesa("x-2", "2026-07-10", "alimentacao", "Rest", "110.00")).get(0);
        assertEquals(ResultadoDespesa.Status.PARCIAL, semViagem.status());
        assertEquals(new BigDecimal("60.00"), semViagem.valorReembolsado().saida());
    }

    // ---------------------------------------------------------------- T-020 (D-003): RN-013

    /** A tabela de câmbio real do envelope — PTAX publicada apenas em dias úteis (spec §4). */
    private Cambio cambioReal() {
        return Ingestao.lerCambio(java.nio.file.Path.of("exemplos/cambio.json"));
    }

    /** Processa com política, câmbio, centro de custo e viagem explícitos (RN-013). */
    private List<ResultadoDespesa> processarCom(Politica politica, Cambio cambio,
                                                String centroCusto, boolean emViagem,
                                                Entrada.DespesaIn... despesas) {
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", centroCusto),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), emViagem),
                List.of(despesas));
        return new MotorReembolso().processar(entrada, politica, cambio);
    }

    /** Despesa em moeda específica (com nota) — o helper `despesa` é BRL fixo. */
    private Entrada.DespesaIn despesaEmMoeda(String id, String data, String categoria,
                                             String fornecedor, String valor, String moeda) {
        return new Entrada.DespesaIn(id, java.time.LocalDate.parse(data), categoria,
                "teste", fornecedor, valor, moeda, true);
    }

    @Test
    @DisplayName("rn013_moedaAusente_tratadaComoBrl")
    void rn013_moedaAusente_tratadaComoBrl() {
        // AMB-018: moeda ausente na entrada vira "BRL" (ingestão, T-018) — no motor
        // é moeda base: sem conversão e sem consulta de taxa, mesmo com câmbio presente.
        String json = """
            {
              "colaborador": {"id": "c-1", "nome": "X", "centro_custo": "CC"},
              "periodo": {"competencia": "2026-07", "inicio": "2026-07-01", "fim": "2026-07-31"},
              "despesas": [
                {"id": "x-1", "data": "2026-07-10", "categoria": "alimentacao",
                 "descricao": "almoco", "fornecedor": "Rest", "valor": 50.00,
                 "tem_nota_fiscal": true}
              ]
            }
            """;
        Entrada entrada = Ingestao.lerDeString(json);
        List<ResultadoDespesa> rs = new MotorReembolso()
                .processar(entrada, PoliticaV3.politica(), cambioReal());

        ResultadoDespesa r = rs.get(0);
        assertEquals(ResultadoDespesa.Status.APROVADA, r.status());
        assertEquals(new BigDecimal("50.00"), r.valorSolicitado().saida(),
                "sem conversão: o valor em BRL é o informado");
        assertNull(r.conversao(), "moeda base não gera conversão nem consulta de taxa");
    }

    @Test
    @DisplayName("rn013_convertePelaTaxaDataDaDespesa")
    void rn013_convertePelaTaxaDataDaDespesa() {
        // AMB-016: taxa da própria data da despesa — 22 EUR em 2026-07-14 × 5,93
        ResultadoDespesa r = processarCom(PoliticaV3.politica(), cambioReal(), "CC", false,
                despesaEmMoeda("x-1", "2026-07-14", "alimentacao", "Taberna", "22.00", "EUR")).get(0);

        assertEquals(new BigDecimal("130.46"), r.valorSolicitado().saida(),
                "22 × 5,93 = 130,46 — valor_solicitado é o valor em BRL (RN-013/AMB-019)");
        assertEquals(ResultadoDespesa.Status.PARCIAL, r.status());
        assertEquals(new BigDecimal("60.00"), r.valorReembolsado().saida(),
                "teto diário do padrão aplicado sobre o valor convertido (130,46 > 60,00)");

        assertEquals("EUR", r.conversao().moeda());
        assertEquals(new BigDecimal("22.00"), r.conversao().valorOriginal().saida());
        assertEquals(new BigDecimal("5.93"), r.conversao().taxa());
        assertEquals(java.time.LocalDate.of(2026, 7, 14), r.conversao().dataTaxa());
    }

    @Test
    @DisplayName("rn013_diaSemCotacao_usaUltimaPublicada")
    void rn013_diaSemCotacao_usaUltimaPublicada() {
        // AMB-016: 2026-07-18 é sábado sem cotação — vale a última publicada
        // (2026-07-17, EUR 5,96); nunca a taxa futura (a de 2026-07-22 daria 178,50).
        ResultadoDespesa r = processarCom(PoliticaV3.politica(), cambioReal(), "CC", false,
                despesaEmMoeda("x-1", "2026-07-18", "alimentacao", "Cervejaria", "30.00", "EUR")).get(0);

        assertEquals(new BigDecimal("178.80"), r.valorSolicitado().saida(),
                "30 × 5,96 = 178,80 — taxa de 2026-07-17, não a futura");
        assertEquals(new BigDecimal("5.96"), r.conversao().taxa());
        assertEquals(java.time.LocalDate.of(2026, 7, 17), r.conversao().dataTaxa(),
                "data da taxa é a da publicação usada, anterior à data da despesa");
    }

    @Test
    @DisplayName("rn013_moedaSemCotacao_recusaItemLoteSegue")
    void rn013_moedaSemCotacao_recusaItemLoteSegue() {
        // AMB-017: GBP não está na tabela — recusa do item com motivo próprio;
        // o lote segue e a despesa BRL do mesmo lote é processada normalmente.
        List<ResultadoDespesa> rs = processarCom(PoliticaV3.politica(), cambioReal(), "CC", false,
                despesaEmMoeda("x-1", "2026-07-21", "alimentacao", "The Ivy", "55.00", "GBP"),
                despesa("x-2", "2026-07-10", "alimentacao", "Rest", "50.00"));

        ResultadoDespesa gbp = rs.get(0);
        assertEquals(ResultadoDespesa.Status.RECUSADA, gbp.status());
        assertEquals(List.of("Moeda sem cotação disponível"), gbp.motivos());
        assertEquals(new BigDecimal("0.00"), gbp.valorReembolsado().saida());
        assertNull(gbp.valorSolicitado(), "sem cotação não há valor em BRL (spec §4/§8)");
        assertEquals("GBP", gbp.conversao().moeda());
        assertEquals(new BigDecimal("55.00"), gbp.conversao().valorOriginal().saida());
        assertNull(gbp.conversao().taxa());

        ResultadoDespesa brl = rs.get(1);
        assertEquals(ResultadoDespesa.Status.APROVADA, brl.status(), "o lote continua");
        assertEquals(new BigDecimal("50.00"), brl.valorReembolsado().saida());
    }

    @Test
    @DisplayName("rn013_semCambioFornecido_recusaMoedaEstrangeira")
    void rn013_semCambioFornecido_recusaMoedaEstrangeira() {
        // Spec §4: câmbio é entrada opcional — sem a tabela, despesa em moeda
        // estrangeira é recusada como sem cotação (mesma regra da AMB-017).
        List<ResultadoDespesa> rs = processarCom(PoliticaV3.politica(), null, "CC", false,
                despesaEmMoeda("x-1", "2026-07-14", "alimentacao", "Taberna", "22.00", "EUR"));

        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(0).status());
        assertEquals(List.of("Moeda sem cotação disponível"), rs.get(0).motivos());
    }

    @Test
    @DisplayName("amb019_gatilhoNotaSobreValorConvertido")
    void amb019_gatilhoNotaSobreValorConvertido() {
        // AMB-019 / e-005: 40 USD "parecem" R$ 40, mas viram R$ 220,00 (× 5,50 de
        // 2026-07-20) — o gatilho de nota dispara sobre o valor convertido.
        Entrada.DespesaIn corrida = new Entrada.DespesaIn("x-1",
                java.time.LocalDate.parse("2026-07-20"), "transporte_urbano",
                "teste", "Bolt", "40.00", "USD", false);

        ResultadoDespesa r = processarCom(PoliticaV3.politica(), cambioReal(), "CC", false, corrida).get(0);

        assertEquals(new BigDecimal("220.00"), r.valorSolicitado().saida(),
                "40 × 5,50 = 220,00 — converte primeiro, decide depois");
        assertEquals(ResultadoDespesa.Status.RECUSADA, r.status(),
                "220,00 > 100,00 sem nota → recusada; 40 na moeda original passaria");
        assertTrue(r.motivos().contains("Despesa acima de R$ 100,00 sem nota fiscal"),
                "motivo de nota sobre o valor em BRL: " + r.motivos());
    }

    @Test
    @DisplayName("amb022_duplicataComparaValorEmBrl")
    void amb022_duplicataComparaValorEmBrl() {
        // AMB-022: a chave de duplicata compara o valor em BRL — 100 USD em
        // 2026-07-21 (× 5,48 = 548,00) e um lançamento de 548,00 BRL no mesmo
        // dia/fornecedor/categoria são a mesma despesa para a RN-004.
        List<ResultadoDespesa> rs = processarCom(PoliticaV3.politica(), cambioReal(), "CC", false,
                despesaEmMoeda("x-1", "2026-07-21", "hospedagem", "Hotel", "100.00", "USD"),
                despesa("x-2", "2026-07-21", "hospedagem", "Hotel", "548.00"));

        org.junit.jupiter.api.Assertions.assertNotEquals(ResultadoDespesa.Status.RECUSADA, rs.get(0).status(),
                "a original (USD convertida) segue elegível — cortada no teto por lançamento");
        assertEquals(ResultadoDespesa.Status.RECUSADA, rs.get(1).status());
        assertTrue(rs.get(1).motivos().contains("Duplicata da despesa x-1"),
                "segundo item (BRL) é duplicata do primeiro (USD convertida): " + rs.get(1).motivos());
    }
}
