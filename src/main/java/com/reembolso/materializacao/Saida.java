package com.reembolso.materializacao;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Entrada;
import com.reembolso.motor.ResultadoDespesa;

/**
 * Modelo de saída (spec §4): totais do período em BRL + decisão de cada despesa.
 *
 * <p>Fronteira da materialização (T-014; multi-moeda na T-020/T-021, D-003):
 * consolida os {@link ResultadoDespesa} do motor no formato fixo do contrato de
 * saída. Totais somados sobre os valores em BRL já arredondados na entrada
 * (RN-010: arredondamento uma única vez sobre o valor final de cada campo —
 * {@link Dinheiro} já garante 2 casas HALF_UP); item sem cotação não tem valor
 * em BRL e contribui com 0 (spec §4/§8).
 */
public record Saida(String colaboradorId,
                    String competencia,
                    Dinheiro totalSolicitado,
                    Dinheiro totalReembolsado,
                    List<DespesaOut> despesas) {

    public Saida {
        despesas = List.copyOf(despesas);
    }

    /**
     * Item de `despesas[]` da saída (spec §4): moeda original do lançamento
     * ("BRL" quando ausente na entrada), valor informado na moeda original,
     * valor_solicitado em BRL (nulo quando não há cotação), valor reembolsado
     * e motivos — RN-011.
     *
     * <p>{@code conversao} não nula marca despesa em moeda estrangeira: a saída
     * carrega {@code taxa_cambio}/{@code data_taxa} (nulos quando nenhuma taxa
     * foi aplicada — sem cotação); para moeda base esses campos não existem.
     */
    public record DespesaOut(String id,
                             String status,
                             String moeda,
                             Dinheiro valorOriginal,
                             Dinheiro valorSolicitado,
                             Dinheiro valorReembolsado,
                             List<String> motivos,
                             ResultadoDespesa.Conversao conversao) {

        public DespesaOut {
            motivos = List.copyOf(motivos);
        }
    }

    /**
     * Consolida a saída a partir da entrada lida e das decisões do motor.
     *
     * <p>`total_solicitado` = soma dos valores em BRL (convertidos inclusos;
     * item sem cotação contribui com 0); `total_reembolsado` = soma dos
     * valores efetivamente reembolsados (RN-010 na saída).
     */
    public static Saida de(Entrada entrada, List<ResultadoDespesa> resultados) {
        Dinheiro totalSolicitado = Dinheiro.ZERO;
        Dinheiro totalReembolsado = Dinheiro.ZERO;
        List<DespesaOut> despesas = new java.util.ArrayList<>();

        // moeda original de cada lançamento (spec §4: "BRL" quando ausente na entrada)
        Map<String, String> moedaPorId = new HashMap<>();
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            moedaPorId.put(despesa.id(), despesa.moeda());
        }

        for (ResultadoDespesa r : resultados) {
            // totais em BRL: sem valor em BRL (sem cotação ou recusa anterior ao
            // passo 4 da conversão), o item contribui com 0 (spec §4/§8)
            if (r.valorSolicitado() != null) {
                totalSolicitado = totalSolicitado.adicionar(r.valorSolicitado());
            }
            totalReembolsado = totalReembolsado.adicionar(r.valorReembolsado());
            despesas.add(new DespesaOut(r.id(), status(r.status()),
                    moedaPorId.getOrDefault(r.id(), "BRL"),
                    valorOriginal(r),
                    r.valorSolicitado(),
                    r.valorReembolsado(),
                    r.motivos(),
                    r.conversao()));
        }
        return new Saida(entrada.colaborador().id(), entrada.periodo().competencia(),
                totalSolicitado, totalReembolsado, despesas);
    }

    /** valor_original (spec §4): valor informado na moeda original — para moeda base, é o próprio valor em BRL. */
    private static Dinheiro valorOriginal(ResultadoDespesa r) {
        return r.conversao() != null ? r.conversao().valorOriginal() : r.valorSolicitado();
    }

    /** Status textual do contrato: `aprovada`, `parcial` ou `recusada` (spec §4). */
    private static String status(ResultadoDespesa.Status status) {
        return switch (status) {
            case APROVADA -> "aprovada";
            case PARCIAL -> "parcial";
            case RECUSADA -> "recusada";
        };
    }
}
