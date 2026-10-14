package com.reembolso.motor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Entrada;

/**
 * Motor de calculo de reembolso: percorre cada despesa pelo pipeline fixo
 * da spec §8. Nesta fase (T-007) estao implementados os passos:
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
 *       anteriores (§8, AMB-006, DT-002);</li>
 *   <li>RN-009 — valor negativo e estorno: o item em si e recusado com motivo
 *       "Estorno: abatido do total da categoria no dia" e o seu valor reduz a
 *       soma elegivel do dia/categoria; soma diaria negativa ou zero nao gera
 *       reembolso algum nem credito ao colaborador (AMB-008). Estorno nao exige
 *       nota fiscal nem e afetado por limites;</li>
 *   <li>RN-005 — valor individual **estritamente maior** que R$ 100,00 exige
 *       nota fiscal; sem nota, a despesa e recusada integralmente. Exatamente
 *       R$ 100,00 nao exige nota (AMB-003); a exigencia e por despesa
 *       individual, nunca pelo total do dia (AMB-004).</li>
 * </ol>
 *
 * <p>O processamento e feito em duas fases: primeiro as recusas por item
 * (passos 1-4), depois a distribuicao da soma elegivel de cada dia/categoria
 * aos itens positivos em ordem de entrada — o abatimento do estorno e um efeito
 * de dia inteiro, nao de posicao na lista. Os limites de categoria (RN-006,
 * RN-007) entram nas proximas tasks sobre esta mesma distribuicao.
 */
public final class MotorReembolso {

    public static final String MOTIVO_CATEGORIA_FORA_DA_POLITICA = "Categoria fora da política";
    public static final String MOTIVO_FORA_DO_PERIODO = "Despesa fora do período de competência";
    public static final String MOTIVO_ESTORNO = "Estorno: abatido do total da categoria no dia";
    public static final String MOTIVO_REDUZIDA_POR_ESTORNO = "Reduzida por estorno: abatida da soma da categoria no dia";
    public static final String MOTIVO_SEM_NOTA_FISCAL = "Despesa acima de R$ 100,00 sem nota fiscal";

    /** RN-005: gatilho de nota fiscal — estritamente maior que R$ 100,00 (AMB-003). */
    private static final Dinheiro GATILHO_NOTA_FISCAL = Dinheiro.de("100.00");

    /** Tolerancia da RN-004: valores a ate R$ 0,01 de distancia sao "o mesmo valor". */
    private static final Dinheiro TOLERANCIA_DUPLICATA = Dinheiro.de("0.01");

    /** Chave de agregacao diaria: dia + categoria normalizada. */
    private record DiaCategoria(LocalDate data, Categoria categoria) {
    }

    /** Item que sobreviveu aos passos 1-4 e aguarda a distribuicao da soma do dia. */
    private record Pendente(int indice, Entrada.DespesaIn despesa, Categoria categoria, Dinheiro valor) {
    }

    public List<ResultadoDespesa> processar(Entrada entrada) {
        Entrada.Periodo periodo = entrada.periodo();
        List<Elegivel> elegiveis = new ArrayList<>();
        Map<DiaCategoria, Dinheiro> estornosPorDia = new HashMap<>();
        ResultadoDespesa[] resultados = new ResultadoDespesa[entrada.despesas().size()];
        List<Pendente> pendentes = new ArrayList<>();

        // Fase 1 — decisoes por item, na ordem da entrada (passos 1 a 4 do §8)
        int indice = 0;
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            Dinheiro valor = Dinheiro.de(despesa.valor()); // RN-010: arredonda na entrada

            Optional<Categoria> categoria = Categoria.normalizar(despesa.categoria());
            if (categoria.isEmpty()) {
                // RN-002: fora da politica -> recusa integral, sem consumir qualquer cota
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_CATEGORIA_FORA_DA_POLITICA);
                indice++;
                continue;
            }

            // RN-003: data fora de [inicio, fim] (inclusive nas pontas) -> recusa integral
            if (despesa.data().isBefore(periodo.inicio()) || despesa.data().isAfter(periodo.fim())) {
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_FORA_DO_PERIODO);
                indice++;
                continue;
            }

            // RN-004: duplicata de item elegivel ja processado -> recusa citando o id da original
            Elegivel atual = new Elegivel(despesa.id(), despesa.data(), categoria.get(),
                    despesa.fornecedor(), valor);
            Elegivel original = buscarOriginalDuplicata(elegiveis, atual);
            if (original != null) {
                resultados[indice] = recusada(despesa.id(), valor, "Duplicata da despesa " + original.id());
                indice++;
                continue;
            }
            elegiveis.add(atual);

            // RN-009: valor negativo e estorno — recusa o item e abate a soma elegivel do dia/categoria
            if (valor.negativo()) {
                DiaCategoria chave = new DiaCategoria(despesa.data(), categoria.get());
                estornosPorDia.merge(chave, valor, Dinheiro::adicionar);
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_ESTORNO);
                indice++;
                continue;
            }

            // RN-005: valor individual > R$ 100,00 exige nota fiscal; sem nota -> recusa integral
            if (valor.maiorQue(GATILHO_NOTA_FISCAL) && !despesa.temNotaFiscal()) {
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_SEM_NOTA_FISCAL);
                indice++;
                continue;
            }

            // Elegivel ate aqui; o valor final depende da soma diaria da categoria (fase 2)
            pendentes.add(new Pendente(indice, despesa, categoria.get(), valor));
            indice++;
        }

        // Fase 2 — RN-009: distribui a soma elegivel do dia (positivos + estornos),
        // consumindo em ordem de entrada; soma <= 0 nao reembolsa nada (sem credito)
        Map<DiaCategoria, Dinheiro> positivosPorDia = new HashMap<>();
        for (Pendente p : pendentes) {
            DiaCategoria chave = new DiaCategoria(p.despesa().data(), p.categoria());
            positivosPorDia.merge(chave, p.valor(), Dinheiro::adicionar);
        }

        Map<DiaCategoria, Dinheiro> cotaDisponivel = new HashMap<>();
        for (Pendente p : pendentes) {
            DiaCategoria chave = new DiaCategoria(p.despesa().data(), p.categoria());
            Dinheiro cota = cotaDisponivel.computeIfAbsent(chave, k -> {
                Dinheiro soma = positivosPorDia.get(k)
                        .adicionar(estornosPorDia.getOrDefault(k, Dinheiro.ZERO));
                return soma.negativo() ? Dinheiro.ZERO : soma;
            });
            Dinheiro reembolso = p.valor().menorOuIgualA(cota) ? p.valor() : cota;
            cotaDisponivel.put(chave, cota.subtrair(reembolso));
            resultados[p.indice()] = aprovada(p.despesa().id(), p.valor(), reembolso);
        }

        return List.of(resultados);
    }

    private ResultadoDespesa recusada(String id, Dinheiro valor, String motivo) {
        return new ResultadoDespesa(id, ResultadoDespesa.Status.RECUSADA,
                valor, Dinheiro.ZERO, List.of(motivo));
    }

    // RN-011: toda decisao carrega motivo — aprovacao integral ou corte
    private ResultadoDespesa aprovada(String id, Dinheiro valor, Dinheiro reembolso) {
        if (reembolso.equals(valor)) {
            return new ResultadoDespesa(id, ResultadoDespesa.Status.APROVADA,
                    valor, valor, List.of("Aprovada integralmente"));
        }
        return new ResultadoDespesa(id, ResultadoDespesa.Status.PARCIAL,
                valor, reembolso, List.of(MOTIVO_REDUZIDA_POR_ESTORNO));
    }

    /** Item que ja sobreviveu aos passos 1-2 e pode ser original de uma duplicata. */
    private record Elegivel(String id, LocalDate data, Categoria categoria,
                            String fornecedor, Dinheiro valor) {
    }

    /** RN-004: devolve o primeiro elegivel que coincide na chave de duplicata, ou null. */
    private Elegivel buscarOriginalDuplicata(List<Elegivel> elegiveis, Elegivel atual) {
        for (Elegivel original : elegiveis) {
            if (mesmaIdentidade(original, atual) && dentroDaTolerancia(original.valor(), atual.valor())) {
                return original;
            }
        }
        return null;
    }

    /** Chave de duplicata da RN-004 (sem o valor): data + categoria normalizada + fornecedor. */
    private boolean mesmaIdentidade(Elegivel a, Elegivel b) {
        return a.data().equals(b.data())
                && a.categoria() == b.categoria()
                && a.fornecedor().equals(b.fornecedor());
    }

    /** RN-004: tolerancia de R$ 0,01 — diferencas visiveis (>= 1 centavo apos o
     *  arredondamento da RN-010) NAO sao a mesma despesa: d-003 (100,00) e
     *  d-004 (100,01) nao duplicam e d-004 morre por nota (§7). */
    private boolean dentroDaTolerancia(Dinheiro a, Dinheiro b) {
        Dinheiro diferenca = a.subtrair(b);
        if (diferenca.negativo()) {
            diferenca = Dinheiro.ZERO.subtrair(diferenca);
        }
        return diferenca.compareTo(TOLERANCIA_DUPLICATA) < 0;
    }
}
