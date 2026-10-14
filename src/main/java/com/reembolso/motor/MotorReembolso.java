package com.reembolso.motor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Entrada;

/**
 * Motor de calculo de reembolso: percorre cada despesa pelo pipeline fixo
 * da spec §8. Nesta fase (T-004) estao implementados apenas os passos:
 *
 * <ol>
 *   <li>RN-001 — normalizacao de categoria (case-insensitive + trim) contra o
 *       catalogo fechado;</li>
 *   <li>RN-002 — categoria fora da politica recusa integralmente, reembolso
 *       R$ 0,00, com motivo "Categoria fora da política";</li>
 *   <li>RN-003 — data fora de [periodo.inicio, periodo.fim] (inclusive nas
 *       pontas) recusa integralmente com motivo "Despesa fora do período de
 *       competência".</li>
 * </ol>
 *
 * <p>Despesas reconhecidas sao aprovadas integralmente enquanto os demais
 * passos do pipeline (RN-004 a RN-009) nao sao implementados nas tasks seguintes.
 */
public final class MotorReembolso {

    public static final String MOTIVO_CATEGORIA_FORA_DA_POLITICA = "Categoria fora da política";
    public static final String MOTIVO_FORA_DO_PERIODO = "Despesa fora do período de competência";

    public List<ResultadoDespesa> processar(Entrada entrada) {
        Entrada.Periodo periodo = entrada.periodo();
        List<ResultadoDespesa> resultado = new ArrayList<>();
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            resultado.add(processarUma(despesa, periodo));
        }
        return List.copyOf(resultado);
    }

    // Pipeline fixo da spec §8: passo 1 (RN-001/RN-002) e passo 2 (RN-003)
    private ResultadoDespesa processarUma(Entrada.DespesaIn despesa, Entrada.Periodo periodo) {
        Dinheiro valor = Dinheiro.de(despesa.valor()); // RN-010: arredonda na entrada

        Optional<Categoria> categoria = Categoria.normalizar(despesa.categoria());
        if (categoria.isEmpty()) {
            // RN-002: fora da politica -> recusa integral, sem consumir qualquer cota
            return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.RECUSADA,
                    valor, Dinheiro.ZERO, List.of(MOTIVO_CATEGORIA_FORA_DA_POLITICA));
        }

        // RN-003: data fora de [inicio, fim] (inclusive nas pontas) -> recusa integral
        if (despesa.data().isBefore(periodo.inicio()) || despesa.data().isAfter(periodo.fim())) {
            return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.RECUSADA,
                    valor, Dinheiro.ZERO, List.of(MOTIVO_FORA_DO_PERIODO));
        }

        // Demais regras entram nas proximas tasks (T-006+)
        return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.APROVADA,
                valor, valor, List.of("Aprovada integralmente"));
    }
}
