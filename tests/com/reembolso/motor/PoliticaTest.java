package com.reembolso.motor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.PoliticaV3;
import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Ingestao;
import com.reembolso.io.PoliticaV4;

/**
 * T-009 — RN-008: fator de viagem lido da política (+50% na v3).
 * T-019 — RN-012: limites por centro de custo, vigência e periodicidade (D-003).
 *
 * <p>Critérios de aceite da T-019:
 * <ul>
 *   <li>rn012_ccComercial_overrides: CC-COMERCIAL resolve 90/150/400/representacao 300</li>
 *   <li>rn012_ccDesconhecido_resolvePadrao: CC-SUPORTE-N2 → 60/80/250 (AMB-014)</li>
 *   <li>rn012_categoriaAusenteNoBlocoCc_herdadoDoPadrao: CC-ADM hospedagem → 250 (AMB-015)</li>
 *   <li>rn012_vigenciaPosterior_erroDeProcessamento: política v4 + período 2026-06 → erro (AMB-021)</li>
 *   <li>rn012_periodicidadeDesconhecida_erroDeProcessamento: "mes" → erro</li>
 * </ul>
 */
class PoliticaTest {

    private Politica politicaV4Real() {
        return Politica.de(Ingestao.lerPolitica(java.nio.file.Path.of("exemplos/politica-v4.json")));
    }

    /** Limite resolvido pelo padrao (a v3 não tem overrides de centro de custo). */
    private PoliticaV4.LimiteCategoria limitePadrao(Politica politica, String categoria) {
        return politica.resolucao("CC-QUALQUER", Categoria.normalizar(categoria).orElseThrow()).orElseThrow();
    }

    // ------------------------------------------------------ T-009 rework (D-003): RN-008 lido da política

    @Test
    @DisplayName("limite_emViagem_amplia50porCento")
    void limite_emViagem_amplia50porCento() {
        // RN-008: os três limites crescem 50% quando periodo.em_viagem = true
        Politica p = PoliticaV3.politica();
        assertEquals(Dinheiro.de("90.00"), p.limiteVigente(limitePadrao(p, "alimentacao"), true),
                "alimentacao: 60 -> 90");
        assertEquals(Dinheiro.de("120.00"), p.limiteVigente(limitePadrao(p, "transporte_urbano"), true),
                "transporte_urbano: 80 -> 120");
        assertEquals(Dinheiro.de("375.00"), p.limiteVigente(limitePadrao(p, "hospedagem"), true),
                "hospedagem: 250 -> 375");
    }

    @Test
    @DisplayName("limite_semViagem_devolveBase")
    void limite_semViagem_devolveBase() {
        // AMB-005: em_viagem ausente/false = limites base da política
        Politica p = PoliticaV3.politica();
        assertEquals(Dinheiro.de("60.00"), p.limiteVigente(limitePadrao(p, "alimentacao"), false));
        assertEquals(Dinheiro.de("80.00"), p.limiteVigente(limitePadrao(p, "transporte_urbano"), false));
        assertEquals(Dinheiro.de("250.00"), p.limiteVigente(limitePadrao(p, "hospedagem"), false));
    }

    @Test
    @DisplayName("limite_ampliado_duasCasasDecimais")
    void limite_ampliado_duasCasasDecimais() {
        // RN-010: mesmo o limite calculado sai com exatamente 2 casas
        Politica p = PoliticaV3.politica();
        assertEquals(2, p.limiteVigente(limitePadrao(p, "alimentacao"), true).saida().scale());
        assertEquals(2, p.limiteVigente(limitePadrao(p, "hospedagem"), true).saida().scale());
    }

    @Test
    @DisplayName("gatilhoNota_emViagem_naoAmplia")
    void gatilhoNota_emViagem_naoAmplia() {
        // RN-008 / AMB-005: o acréscimo de viagem toca os limites de categoria,
        // NUNCA o gatilho de nota — o percentual não passa por ele.
        Politica p = PoliticaV3.politica();
        assertEquals(Dinheiro.de("100.00"), p.gatilhoNotaFiscal());
        assertEquals(Dinheiro.de("90.00"), p.limiteVigente(limitePadrao(p, "alimentacao"), true),
                "o limite ampliado não alcança o gatilho — 100,00 permanece 100,00");
    }

    // ------------------------------------------------------ T-019: RN-012 (política v4 real)

    @Test
    @DisplayName("rn012_ccComercial_overrides")
    void rn012_ccComercial_overrides() {
        Politica p = politicaV4Real();

        var alimentacao = p.resolucao("CC-COMERCIAL", Categoria.ALIMENTACAO).orElseThrow();
        assertEquals(Dinheiro.de("90.00"), alimentacao.limite());
        assertEquals("dia", alimentacao.periodicidade());

        assertEquals(Dinheiro.de("150.00"),
                p.resolucao("CC-COMERCIAL", Categoria.TRANSPORTE_URBANO).orElseThrow().limite());

        var hospedagem = p.resolucao("CC-COMERCIAL", Categoria.HOSPEDAGEM).orElseThrow();
        assertEquals(Dinheiro.de("400.00"), hospedagem.limite());
        assertEquals("diaria", hospedagem.periodicidade());

        // categoria exclusiva do CC: só existe porque a política cobre
        var representacao = p.resolucao("CC-COMERCIAL",
                Categoria.normalizar("representacao").orElseThrow()).orElseThrow();
        assertEquals(Dinheiro.de("300.00"), representacao.limite());
        assertEquals("dia", representacao.periodicidade());
    }

    @Test
    @DisplayName("rn012_ccDesconhecido_resolvePadrao")
    void rn012_ccDesconhecido_resolvePadrao() {
        // AMB-014: CC sem bloco na política resolve TUDO pelo padrao
        Politica p = politicaV4Real();
        assertEquals(Dinheiro.de("60.00"),
                p.resolucao("CC-SUPORTE-N2", Categoria.ALIMENTACAO).orElseThrow().limite());
        assertEquals(Dinheiro.de("80.00"),
                p.resolucao("CC-SUPORTE-N2", Categoria.TRANSPORTE_URBANO).orElseThrow().limite());
        assertEquals(Dinheiro.de("250.00"),
                p.resolucao("CC-SUPORTE-N2", Categoria.HOSPEDAGEM).orElseThrow().limite());
        assertTrue(p.resolucao("CC-SUPORTE-N2",
                        Categoria.normalizar("representacao").orElseThrow()).isEmpty(),
                "AMB-014: representacao só existe no bloco do CC-COMERCIAL — ausente do padrao, é recusada (RN-002)");
    }

    @Test
    @DisplayName("rn012_categoriaAusenteNoBlocoCc_herdadoDoPadrao")
    void rn012_categoriaAusenteNoBlocoCc_herdadoDoPadrao() {
        // AMB-015: omissão ≠ proibição — CC-ADM não lista hospedagem no bloco,
        // portanto herda o padrao (a proibição explícita é o 0,00 do CC-ENG-PLATAFORMA)
        Politica p = politicaV4Real();
        var hospedagem = p.resolucao("CC-ADM", Categoria.HOSPEDAGEM).orElseThrow();
        assertEquals(Dinheiro.de("250.00"), hospedagem.limite());
        assertEquals("diaria", hospedagem.periodicidade());

        // o override do próprio bloco continua valendo para as categorias listadas
        assertEquals(Dinheiro.de("45.00"),
                p.resolucao("CC-ADM", Categoria.ALIMENTACAO).orElseThrow().limite());
    }

    @Test
    @DisplayName("rn012_vigenciaPosterior_erroDeProcessamento")
    void rn012_vigenciaPosterior_erroDeProcessamento() {
        // AMB-021: a política v4 vale a partir de 2026-07-01 — não cobre um
        // período que começa em junho. Erro de processamento, nada calculado.
        Politica p = politicaV4Real();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> p.validarVigencia(LocalDate.of(2026, 6, 1)));
        assertTrue(ex.getMessage().contains("vigência"),
                "mensagem legível cita a vigência: " + ex.getMessage());

        // fronteira inclusiva: vigência == início do período é válida
        p.validarVigencia(LocalDate.of(2026, 7, 1));
    }

    @Test
    @DisplayName("rn012_periodicidadeDesconhecida_erroDeProcessamento")
    void rn012_periodicidadeDesconhecida_erroDeProcessamento() {
        // RN-012 / spec §4: periodicidade só tem dois valores com semântica
        // definida — qualquer outro é erro de processamento (falha rápida).
        PoliticaV4 quebrada = new PoliticaV4("quebrada", LocalDate.of(2026, 1, 1), "BRL",
                Map.of("alimentacao", new PoliticaV4.LimiteCategoria(Dinheiro.de("60.00"), "mes", null)),
                Map.of(), Dinheiro.de("100.00"), BigDecimal.valueOf(50));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Politica.de(quebrada));
        assertTrue(ex.getMessage().contains("mes"),
                "mensagem cita a periodicidade inválida: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("alimentacao"),
                "mensagem cita a categoria onde está o erro: " + ex.getMessage());
    }
}
