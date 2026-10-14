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
 *       R$ 0,00, com motivo "Categoria fora da politica".</li>
 * </ol>
 *
 * <p>Despesas reconhecidas sao aprovadas integralmente enquanto os demais
 * passos do pipeline (RN-003 a RN-009) nao sao implementados nas tasks seguintes.
 */
public final class MotorReembolso {

    public static final String MOTIVO_CATEGORIA_FORA_DA_POLITICA = "Categoria fora da política";

    public List<ResultadoDespesa> processar(Entrada entrada) {
        List<ResultadoDespesa> resultado = new ArrayList<>();
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            resultado.add(processarUma(despesa));
        }
        return List.copyOf(resultado);
    }

    // Passo 1 do pipeline §8: RN-001 -> RN-002
    private ResultadoDespesa processarUma(Entrada.DespesaIn despesa) {
        Dinheiro valor = Dinheiro.de(despesa.valor()); // RN-010: arredonda na entrada

        Optional<Categoria> categoria = Categoria.normalizar(despesa.categoria());
        if (categoria.isEmpty()) {
            // RN-002: fora da politica -> recusa integral, sem consumir qualquer cota
            return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.RECUSADA,
                    valor, Dinheiro.ZERO, List.of(MOTIVO_CATEGORIA_FORA_DA_POLITICA));
        }

        // RN-001 reconhecida; demais regras entram nas proximas tasks (T-005+)
        return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.APROVADA,
                valor, valor, List.of("Aprovada integralmente"));
    }
}
