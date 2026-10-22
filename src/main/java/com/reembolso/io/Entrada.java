package com.reembolso.io;

import java.time.LocalDate;
import java.util.List;

/**
 * Modelo de entrada, espelho do formato de `exemplos/despesas-exemplo.json` (spec §4).
 *
 * <p>`valor` é preservado como String bruta (DT-001): o parser JSON não pode
 * arredondar antes de `Dinheiro.de(...)` aplicar HALF_UP. `em_viagem` é boolean
 * com default false (AMB-005). `moeda` é o código da moeda do valor informado,
 * com default "BRL" quando ausente (AMB-005 para `em_viagem`, AMB-018 para
 * `moeda`) — preservada como veio, sem normalização, que é decisão do motor.
 */
public record Entrada(Colaborador colaborador, Periodo periodo, List<DespesaIn> despesas) {

    public record Colaborador(String id, String nome, String centroCusto) {
    }

    public record Periodo(String competencia, LocalDate inicio, LocalDate fim, boolean emViagem) {
    }

    public record DespesaIn(String id,
                            LocalDate data,
                            String categoria,
                            String descricao,
                            String fornecedor,
                            String valor,
                            String moeda,
                            boolean temNotaFiscal) {
    }
}
