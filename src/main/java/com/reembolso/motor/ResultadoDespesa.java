package com.reembolso.motor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.reembolso.dominio.Dinheiro;

/**
 * Decisão do motor sobre uma despesa, espelho de `despesas[]` da saída (spec §4).
 * RN-011: `motivos` nunca é vazio — toda decisão carrega justificativa.
 *
 * <p>T-020 (RN-013): `valorSolicitado` é o valor em BRL — o informado quando a
 * despesa já está na moeda base, o valor convertido quando em moeda estrangeira
 * (converte primeiro, decide depois — AMB-019). É **nulo** quando o motor não
 * estabeleceu valor em BRL: moeda sem cotação (AMB-017) ou recusa anterior ao
 * passo 4 da conversão (§8) — o item não entra em totais nem gasta cota.
 */
public record ResultadoDespesa(String id,
                               Status status,
                               Dinheiro valorSolicitado,
                               Dinheiro valorReembolsado,
                               List<String> motivos,
                               Conversao conversao) {

    public ResultadoDespesa {
        // RN-011: nenhum status nasce sem motivo — o construtor exige lista nao vazia
        motivos = List.copyOf(motivos);
        if (motivos.isEmpty()) {
            throw new IllegalArgumentException("RN-011: toda decisao carrega ao menos um motivo");
        }
    }

    public enum Status {
        APROVADA, PARCIAL, RECUSADA
    }

    /**
     * Conversão cambial da despesa (RN-013, T-020): moeda original, valor
     * informado na moeda original (2 casas, RN-010) e a taxa aplicada com a
     * data da sua publicação (AMB-016).
     *
     * <p>{@code null} quando a despesa já está na moeda base — sem conversão e
     * sem consulta de taxa (AMB-018). {@code taxa == null} quando a moeda não
     * tem cotação disponível (AMB-017): recusa por item, lote segue.
     */
    public record Conversao(String moeda, Dinheiro valorOriginal, BigDecimal taxa, LocalDate dataTaxa) {
    }
}
