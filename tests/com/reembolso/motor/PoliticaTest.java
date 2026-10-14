package com.reembolso.motor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;

/**
 * T-009 — RN-008: objeto Politica + fator de viagem (+50%).
 * Criterios de aceite da task:
 *  - limite_emViagem_amplia50porCento: 60 -> 90 (alimentacao),
 *    80 -> 120 (transporte_urbano), 250 -> 375 (hospedagem)
 *  - gatilhoNota_emViagem_naoAmplia: gatilho de nota continua R$ 100,00 (AMB-005)
 */
class PoliticaTest {

    @Test
    @DisplayName("limite_emViagem_amplia50porCento")
    void limite_emViagem_amplia50porCento() {
        // RN-008: os tres limites crescem 50% quando periodo.em_viagem = true
        assertEquals(Dinheiro.de("90.00"),
                Politica.limite(Categoria.ALIMENTACAO, true), "alimentacao: 60 -> 90");
        assertEquals(Dinheiro.de("120.00"),
                Politica.limite(Categoria.TRANSPORTE_URBANO, true), "transporte_urbano: 80 -> 120");
        assertEquals(Dinheiro.de("375.00"),
                Politica.limite(Categoria.HOSPEDAGEM, true), "hospedagem: 250 -> 375");
    }

    @Test
    @DisplayName("limite_semViagem_devolveBase")
    void limite_semViagem_devolveBase() {
        // AMB-005: em_viagem ausente/false = limites base da politica
        assertEquals(Dinheiro.de("60.00"), Politica.limite(Categoria.ALIMENTACAO, false));
        assertEquals(Dinheiro.de("80.00"), Politica.limite(Categoria.TRANSPORTE_URBANO, false));
        assertEquals(Dinheiro.de("250.00"), Politica.limite(Categoria.HOSPEDAGEM, false));
    }

    @Test
    @DisplayName("limite_ampliado_duasCasasDecimais")
    void limite_ampliado_duasCasasDecimais() {
        // RN-010: mesmo o limite calculado sai com exatamente 2 casas
        assertEquals(2, Politica.limite(Categoria.ALIMENTACAO, true).saida().scale());
        assertEquals(2, Politica.limite(Categoria.HOSPEDAGEM, true).saida().scale());
    }

    @Test
    @DisplayName("gatilhoNota_emViagem_naoAmplia")
    void gatilhoNota_emViagem_naoAmplia() {
        // AMB-005: o valor que dispara a exigencia de nota NAO e ampliado em viagem —
        // permanece R$ 100,00, unico valor fixo fora do fator 1,5 da RN-008
        assertEquals(Dinheiro.de("100.00"), Politica.GATILHO_NOTA_FISCAL);
        assertEquals(Dinheiro.de("100.00"), Politica.gatilhoNotaFiscal(true),
                "em viagem o gatilho de nota continua 100,00");
        assertEquals(Dinheiro.de("100.00"), Politica.gatilhoNotaFiscal(false));
    }
}
