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
                java.time.LocalDate.parse(data), categoria, "teste", "Fornecedor", valor, true);
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(despesa));
        return new MotorReembolso().processar(entrada).get(0);
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
                "transporte_urbano", "teste", "TaxiApp", "100.00", false);
        Entrada.DespesaIn b = new Entrada.DespesaIn("x-2", java.time.LocalDate.of(2026, 7, 6),
                "transporte_urbano", "teste", "TaxiApp", "100.01", false);
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
                "teste", fornecedor, valor, true);
    }

    private List<ResultadoDespesa> processar(Entrada.DespesaIn... despesas) {
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(despesas));
        return new MotorReembolso().processar(entrada);
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
        assertTrue(d003.motivos().stream().noneMatch(m -> m.equals(MotorReembolso.MOTIVO_SEM_NOTA_FISCAL)));
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
                java.time.LocalDate.of(2026, 7, 10), "hospedagem", "teste", "Hotel", "500.00", true);
        List<ResultadoDespesa> rs = processar(comNota);
        org.junit.jupiter.api.Assertions.assertNotEquals(ResultadoDespesa.Status.RECUSADA, rs.get(0).status(),
                "com nota, 500,00 nao e recusada por RN-005");
        assertEquals(new BigDecimal("250.00"), rs.get(0).valorReembolsado().saida(),
                "o corte posterior e o teto por lancamento da RN-007, nao falta de nota");
        org.junit.jupiter.api.Assertions.assertTrue(
                rs.get(0).motivos().stream().noneMatch(m -> m.equals(MotorReembolso.MOTIVO_SEM_NOTA_FISCAL)));
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
                java.time.LocalDate.parse(data), categoria, "teste", "Fornecedor", valor, false);
        Entrada entrada = new Entrada(
                new Entrada.Colaborador("c-1", "X", "CC"),
                new Entrada.Periodo("2026-07",
                        java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 31), false),
                List.of(despesa));
        return new MotorReembolso().processar(entrada).get(0);
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
