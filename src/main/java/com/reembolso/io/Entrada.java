package com.reembolso.io;

import java.time.LocalDate;
import java.util.List;

/**
 * Modelo de entrada, espelho do formato de `exemplos/despesas-exemplo.json` (spec §4).
 *
 * <p>`valor` é preservado como String bruta (DT-001): o parser JSON não pode
 * arredondar antes de `Dinheiro.de(...)` aplicar HALF_UP. `em_viagem` é boolean
 * com default false (AMB-005).
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
                            boolean temNotaFiscal) {
    }
}
