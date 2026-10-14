package com.reembolso.materializacao;

import java.util.List;

import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Entrada;
import com.reembolso.motor.ResultadoDespesa;

/**
 * Modelo de saida (spec §4): totais do periodo + decisao de cada despesa.
 *
 * <p>Fronteira da materializacao (T-014): consolida os {@link ResultadoDespesa}
 * do motor no formato fixo do contrato de saida. Os totais sao somados sobre os
 * valores ja arredondados na entrada (RN-010: arredondamento uma unica vez sobre
 * o valor final de cada campo — {@link Dinheiro} ja garante 2 casas HALF_UP).
 */
public record Saida(String colaboradorId,
                    String competencia,
                    Dinheiro totalSolicitado,
                    Dinheiro totalReembolsado,
                    List<DespesaOut> despesas) {

    public Saida {
        despesas = List.copyOf(despesas);
    }

    /** Item de `despesas[]` da saida: os 5 campos do contrato (spec §4). */
    public record DespesaOut(String id,
                             String status,
                             Dinheiro valorSolicitado,
                             Dinheiro valorReembolsado,
                             List<String> motivos) {

        public DespesaOut {
            motivos = List.copyOf(motivos);
        }
    }

    /**
     * Consolida a saida a partir da entrada lida e das decisoes do motor.
     *
     * <p>`total_solicitado` = soma de TODOS os valores informados (inclusive
     * estornos negativos e despesas recusadas — spec §4); `total_reembolsado`
     * = soma dos valores efetivamente reembolsados (RN-010 na saida).
     */
    public static Saida de(Entrada entrada, List<ResultadoDespesa> resultados) {
        Dinheiro totalSolicitado = Dinheiro.ZERO;
        Dinheiro totalReembolsado = Dinheiro.ZERO;
        List<DespesaOut> despesas = new java.util.ArrayList<>();
        for (ResultadoDespesa r : resultados) {
            totalSolicitado = totalSolicitado.adicionar(r.valorSolicitado());
            totalReembolsado = totalReembolsado.adicionar(r.valorReembolsado());
            despesas.add(new DespesaOut(r.id(), status(r.status()),
                    r.valorSolicitado(), r.valorReembolsado(), r.motivos()));
        }
        return new Saida(entrada.colaborador().id(), entrada.periodo().competencia(),
                totalSolicitado, totalReembolsado, despesas);
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
