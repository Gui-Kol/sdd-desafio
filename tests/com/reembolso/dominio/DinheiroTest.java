package com.reembolso.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * T-002 — Tipo monetario Dinheiro (RN-010 / AMB-011).
 * Criterios de aceite da task:
 *  - de("33.333") -> 33,33
 *  - de("0.005") -> 0,01  (HALF_UP, nunca para baixo na metade)
 *  - adicionar/subtrair/comparar com precisao
 *  - ZERO existe
 *  - imutabilidade
 */
class DinheiroTest {

    @Nested
    @DisplayName("Fronteira de entrada (RN-010, HALF_UP)")
    class Fronteira {

        @Test
        @DisplayName("de_33333_vira_3333")
        void de_33333_vira_3333() {
            // d-011 do exemplo: 33,333 -> 33,33
            assertEquals(new BigDecimal("33.33"), Dinheiro.de("33.333").saida());
        }

        @Test
        @DisplayName("de_0005_vira_001_metadeParaCima")
        void de_0005_vira_001_metadeParaCima() {
            assertEquals(new BigDecimal("0.01"), Dinheiro.de("0.005").saida());
        }

        @Test
        @DisplayName("de_arredondaSubCentavoParaCima_33779")
        void de_33775_vira_3378() {
            // metade exata no centavo arredonda para cima, nunca para zero/baixo
            assertEquals(new BigDecimal("33.78"), Dinheiro.de("33.775").saida());
        }

        @Test
        @DisplayName("de_preservaDuasCasasDecimais")
        void de_preservaDuasCasasDecimais() {
            assertEquals(new BigDecimal("72.50"), Dinheiro.de("72.50").saida());
            assertEquals(new BigDecimal("100.01"), Dinheiro.de("100.01").saida());
        }

        @Test
        @DisplayName("de_valorNegativo_mantidoEEscalaAplicada")
        void de_valorNegativo_mantidoEEscalaAplicada() {
            // estornos (RN-009) entram negativos e com arredondamento HALF_UP ("longe de zero" na metade)
            assertEquals(new BigDecimal("-45.00"), Dinheiro.de("-45.00").saida());
            assertEquals(new BigDecimal("-0.01"), Dinheiro.de("-0.005").saida());
        }

        @Test
        @DisplayName("de_rejeitaNulo")
        void de_rejeitaNulo() {
            assertThrows(NullPointerException.class, () -> Dinheiro.de((String) null));
            assertThrows(NullPointerException.class, () -> Dinheiro.de((BigDecimal) null));
        }
    }

    @Nested
    @DisplayName("Aritmetica com precisao (sem float)")
    class Aritmetica {

        @Test
        @DisplayName("adicionar_01Mais02_da03")
        void adicionar_01Mais02_da03() {
            // a prova classica contra ponto flutuante: 0.1 + 0.2 == 0.3
            assertEquals(Dinheiro.de("0.30"), Dinheiro.de("0.10").adicionar(Dinheiro.de("0.20")));
        }

        @Test
        @DisplayName("subtrair_preservaCentavos")
        void subtrair_preservaCentavos() {
            assertEquals(Dinheiro.de("27.50"),
                    Dinheiro.de("100.00").subtrair(Dinheiro.de("72.50")));
        }

        @Test
        @DisplayName("adicionar_somaSemPerdaDeEscala")
        void adicionar_somaSemPerdaDeEscala() {
            assertEquals(Dinheiro.de("110.50"),
                    Dinheiro.de("72.50").adicionar(Dinheiro.de("38.00")));
        }
    }

    @Nested
    @DisplayName("Comparacao e sinal")
    class Comparacao {

        @Test
        @DisplayName("maiorQue_fronteiraEstritaDoGatilhoDeNota")
        void maiorQue_fronteiraEstritaDoGatilhoDeNota() {
            // AMB-003: estritamente maior que 100 exige nota; 100,00 nao exige
            assertFalse(Dinheiro.de("100.00").maiorQue(Dinheiro.de("100.00")));
            assertTrue(Dinheiro.de("100.01").maiorQue(Dinheiro.de("100.00")));
        }

        @Test
        @DisplayName("menorOuIgualA_limiteDiario")
        void menorOuIgualA_limiteDiario() {
            assertTrue(Dinheiro.de("60.00").menorOuIgualA(Dinheiro.de("60.00")));
            assertFalse(Dinheiro.de("60.01").menorOuIgualA(Dinheiro.de("60.00")));
        }

        @Test
        @DisplayName("negativo_identificaEstorno")
        void negativo_identificaEstorno() {
            assertTrue(Dinheiro.de("-45.00").negativo());   // d-009
            assertFalse(Dinheiro.ZERO.negativo());
            assertFalse(Dinheiro.de("0.01").negativo());
        }

        @Test
        @DisplayName("equalsEEscala_33_33Equal33_330")
        void equals_semEscalaImportar() {
            assertEquals(Dinheiro.de("33.33"), Dinheiro.de("33.330"));
        }
    }

    @Nested
    @DisplayName("Invariantes do tipo")
    class Invariantes {

        @Test
        @DisplayName("zero_existeEhZero")
        void zero_existeEhZero() {
            assertEquals(new BigDecimal("0.00"), Dinheiro.ZERO.saida());
        }

        @Test
        @DisplayName("imutabilidade_operacoesNaoAlteramOriginal")
        void imutabilidade_operacoesNaoAlteramOriginal() {
            Dinheiro original = Dinheiro.de("50.00");
            Dinheiro soma = original.adicionar(Dinheiro.de("10.00"));
            Dinheiro sub = original.subtrair(Dinheiro.de("10.00"));

            assertEquals(new BigDecimal("50.00"), original.saida(), "original foi mutado");
            assertNotSame(original, soma);
            assertNotSame(original, sub);
            assertEquals(Dinheiro.de("60.00"), soma);
            assertEquals(Dinheiro.de("40.00"), sub);
        }

        @Test
        @DisplayName("saida_sempreDuasCasasDecimais")
        void saida_sempreDuasCasasDecimais() {
            assertEquals(2, Dinheiro.de("7").saida().scale());
            assertEquals(2, Dinheiro.de("7.1").saida().scale());
            assertEquals(2, Dinheiro.de("7.999").saida().scale());
        }
    }
}
