package com.reembolso.motor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Entrada;

/**
 * Motor de calculo de reembolso: percorre cada despesa pelo pipeline fixo
 * da spec §8. Nesta fase (T-006) estao implementados os passos:
 *
 * <ol>
 *   <li>RN-001 — normalizacao de categoria (case-insensitive + trim) contra o
 *       catalogo fechado;</li>
 *   <li>RN-002 — categoria fora da politica recusa integralmente, reembolso
 *       R$ 0,00, com motivo "Categoria fora da política";</li>
 *   <li>RN-003 — data fora de [periodo.inicio, periodo.fim] (inclusive nas
 *       pontas) recusa integralmente com motivo "Despesa fora do período de
 *       competência";</li>
 *   <li>RN-004 — duplicata (mesma data + categoria normalizada + fornecedor +
 *       valor com tolerancia de R$ 0,01 sobre o valor arredondado) recusa
 *       integralmente todas exceto a primeira na ordem da entrada, citando o
 *       id da original. So e duplicata de item que sobreviveu aos passos
 *       anteriores (§8, AMB-006, DT-002).</li>
 * </ol>
 *
 * <p>Despesas reconhecidas sao aprovadas integralmente enquanto os demais
 * passos do pipeline (RN-005 a RN-009) nao sao implementados nas tasks seguintes.
 */
public final class MotorReembolso {

    public static final String MOTIVO_CATEGORIA_FORA_DA_POLITICA = "Categoria fora da política";
    public static final String MOTIVO_FORA_DO_PERIODO = "Despesa fora do período de competência";

    /** Tolerancia da RN-004: valores a ate R$ 0,01 de distancia sao "o mesmo valor". */
    private static final Dinheiro TOLERANCIA_DUPLICATA = Dinheiro.de("0.01");

    /** Item que ja sobreviveu aos passos 1-2 e pode ser original de uma duplicata. */
    private record Elegivel(String id, java.time.LocalDate data, Categoria categoria,
                            String fornecedor, Dinheiro valor) {
    }

    public List<ResultadoDespesa> processar(Entrada entrada) {
        Entrada.Periodo periodo = entrada.periodo();
        List<Elegivel> elegiveis = new ArrayList<>();
        List<ResultadoDespesa> resultado = new ArrayList<>();
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            resultado.add(processarUma(despesa, periodo, elegiveis));
        }
        return List.copyOf(resultado);
    }

    // Pipeline fixo da spec §8: passo 1 (RN-001/RN-002), passo 2 (RN-003), passo 3 (RN-004)
    private ResultadoDespesa processarUma(Entrada.DespesaIn despesa, Entrada.Periodo periodo,
                                          List<Elegivel> elegiveis) {
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

        // RN-004: duplicata de item elegivel ja processado -> recusa citando o id da original
        Elegivel atual = new Elegivel(despesa.id(), despesa.data(), categoria.get(),
                despesa.fornecedor(), valor);
        for (Elegivel original : elegiveis) {
            if (mesmaIdentidade(original, atual) && dentroDaTolerancia(original.valor(), valor)) {
                return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.RECUSADA,
                        valor, Dinheiro.ZERO, List.of("Duplicata da despesa " + original.id()));
            }
        }
        elegiveis.add(atual);

        // Demais regras entram nas proximas tasks (T-007+)
        return new ResultadoDespesa(despesa.id(), ResultadoDespesa.Status.APROVADA,
                valor, valor, List.of("Aprovada integralmente"));
    }

    /** Chave de duplicata da RN-004 (sem o valor): data + categoria normalizada + fornecedor. */
    private boolean mesmaIdentidade(Elegivel a, Elegivel b) {
        return a.data().equals(b.data())
                && a.categoria() == b.categoria()
                && a.fornecedor().equals(b.fornecedor());
    }

    /** RN-004: tolerancia de R$ 0,01 sobre os valores ja arredondados (RN-010). */
    private boolean dentroDaTolerancia(Dinheiro a, Dinheiro b) {
        Dinheiro diferenca = a.subtrair(b);
        if (diferenca.negativo()) {
            diferenca = Dinheiro.ZERO.subtrair(diferenca);
        }
        return diferenca.menorOuIgualA(TOLERANCIA_DUPLICATA);
    }
}
