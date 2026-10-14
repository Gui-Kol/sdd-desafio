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
        motivos = List.copyOf(motivos);
    }

    public enum Status {
        APROVADA, PARCIAL, RECUSADA
    }
}
