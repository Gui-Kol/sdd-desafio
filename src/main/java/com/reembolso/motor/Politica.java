package com.reembolso.motor;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;

/**
 * Tabela de limites da politica de reembolso (RN-005, RN-006, RN-007, RN-008).
 *
 * <p>Toda leitura de limite passa por {@link #limite(Categoria, boolean)} —
 * unico ponto onde o fator de viagem de 1,5 incide (RN-008) e onde ele
 * deliberadamente NAO incide: o gatilho de nota fiscal de R$ 100,00 permanece
 * fixo mesmo em viagem (AMB-005). Mudanca de politica = editar esta classe,
 * nao cacar numeros no motor.
 */
public final class Politica {

    /** RN-006: limite diario de alimentacao (base). */
    public static final Dinheiro LIMITE_DIARIO_ALIMENTACAO = Dinheiro.de("60.00");

    /** RN-006: limite diario de transporte urbano (base). */
    public static final Dinheiro LIMITE_DIARIO_TRANSPORTE = Dinheiro.de("80.00");

    /** RN-007: limite de hospedagem por lancamento (base). */
    public static final Dinheiro LIMITE_LANCAMENTO_HOSPEDAGEM = Dinheiro.de("250.00");

    /** RN-005 / AMB-005: gatilho de nota fiscal — nunca ampliado em viagem. */
    public static final Dinheiro GATILHO_NOTA_FISCAL = Dinheiro.de("100.00");

    /** RN-008: acrescimo de 50% sobre os limites quando em_viagem = true. */
    private static final BigDecimal FATOR_VIAGEM = new BigDecimal("1.5");

    private Politica() {
    }

    /**
     * Gatilho da exigencia de nota fiscal (RN-005). Recebe {@code emViagem}
     * apenas para deixar explicito no ponto de leitura que a RN-008 NAO se
     * aplica: o gatilho permanece R$ 100,00 com ou sem viagem (AMB-005).
     */
    public static Dinheiro gatilhoNotaFiscal(boolean emViagem) {
        return GATILHO_NOTA_FISCAL;
    }

    /**
     * Limite vigente da categoria para o periodo informado.
     *
     * <p>RN-008: com {@code emViagem == true}, o limite base e ampliado em 50%
     * (60 -> 90, 80 -> 120, 250 -> 375), arredondado HALF_UP para 2 casas
     * (RN-010). Sem viagem, devolve o limite base.
     *
     * @param categoria categoria normalizada (RN-001)
     * @param emViagem  valor de {@code periodo.em_viagem} (ausente = false, AMB-005)
     */
    public static Dinheiro limite(Categoria categoria, boolean emViagem) {
        Dinheiro base = switch (categoria) {
            case ALIMENTACAO -> LIMITE_DIARIO_ALIMENTACAO;
            case TRANSPORTE_URBANO -> LIMITE_DIARIO_TRANSPORTE;
            case HOSPEDAGEM -> LIMITE_LANCAMENTO_HOSPEDAGEM;
        };
        if (!emViagem) {
            return base;
        }
        return Dinheiro.de(base.saida().multiply(FATOR_VIAGEM).setScale(2, RoundingMode.HALF_UP));
    }
}
