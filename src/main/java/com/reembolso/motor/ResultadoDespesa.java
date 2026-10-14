package com.reembolso.motor;

import java.util.List;

import com.reembolso.dominio.Dinheiro;

/**
 * Decisao do motor sobre uma despesa, espelho de `despesas[]` da saida (spec §4).
 * RN-011: `motivos` nunca e vazio — toda decisao carrega justificativa.
 */
public record ResultadoDespesa(String id,
                               Status status,
                               Dinheiro valorSolicitado,
                               Dinheiro valorReembolsado,
                               List<String> motivos) {

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
}
