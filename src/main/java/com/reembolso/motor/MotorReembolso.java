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
import com.reembolso.io.PoliticaV4;

/**
 * Motor de cálculo de reembolso: percorre cada despesa pelo pipeline fixo
 * da spec §8, sob a política v4 resolvida por RN-012. Passos (T-019 / D-003):
 *
 * <ol>
 *   <li>RN-001 — normalização de categoria (case-insensitive + trim). O
 *       catálogo é dado da política: a normalização aceita qualquer nome;
 *       estar na política é decisão do passo 2;</li>
 *   <li>RN-012 — resolve o limite e a periodicidade para (centro de custo,
 *       categoria): override do CC ou herança do {@code padrao} (AMB-014).
 *       Sem resolução → RN-002 recusa com "Categoria fora da política";
 *       limite 0,00 → recusa como não reembolsável (AMB-015) — e a viagem
 *       não transforma proibição em permissão (0 × 1,5 = 0);</li>
 *   <li>RN-003 — data fora de [periodo.inicio, periodo.fim] (inclusive nas
 *       pontas) recusa integralmente;</li>
 *   <li>RN-013 — conversão cambial: chega na task T-020 (o passo existe na
 *       spec §8, ainda não no código);</li>
 *   <li>RN-004 — duplicata (mesma data + categoria normalizada + fornecedor +
 *       valor com tolerância de R$ 0,01 sobre o valor arredondado) recusa
 *       integralmente todas exceto a primeira na ordem da entrada, citando o
 *       id da original. Só é duplicata de item que sobreviveu aos passos
 *       anteriores (§8, AMB-006, DT-002);</li>
 *   <li>RN-009 — valor negativo é estorno: o item é recusado com motivo
 *       próprio e o seu valor reduz a soma elegível do dia/categoria; soma
 *       diária negativa ou zero não gera reembolso nem crédito (AMB-008);</li>
 *   <li>RN-005 — valor individual **estritamente maior** que o gatilho
 *       {@code nota_fiscal_obrigatoria_acima_de} da política exige nota
 *       fiscal; sem nota, recusa integral. O gatilho é lido da política
 *       (D-003) e NÃO é ampliado em viagem (RN-008/AMB-005);</li>
 *   <li>RN-006 (periodicidade {@code dia}) — o limite diário resolvido aplica-
 *       se à soma elegível do dia/categoria, com corte do excedente em ordem
 *       de entrada (AMB-001, AMB-002, AMB-012);</li>
 *   <li>RN-007 (periodicidade {@code diaria}) — o limite é por lançamento
 *       (cada um é uma única fatura de diária, AMB-010): corta-se cada item
 *       individualmente; limites diários não se aplicam.</li>
 * </ol>
 *
 * <p>Pré-condição do lote (§8): a vigência da política (RN-012/AMB-021) é
 * validada antes de qualquer despesa — falha aqui, nada é calculado.
 *
 * <p>O processamento é feito em duas fases: primeiro as recusas por item
 * (passos 1-3, 5-7), depois a distribuição da soma elegível de cada
 * dia/categoria — o abatimento do estorno é um efeito de dia inteiro, não de
 * posição na lista.
 */
public final class MotorReembolso {

    public static final String MOTIVO_CATEGORIA_FORA_DA_POLITICA = "Categoria fora da política";
    public static final String MOTIVO_CATEGORIA_NAO_REEMBOLSAVEL = "Categoria não reembolsável para o centro de custo";
    public static final String MOTIVO_FORA_DO_PERIODO = "Despesa fora do período de competência";
    public static final String MOTIVO_ESTORNO = "Estorno: abatido do total da categoria no dia";
    public static final String MOTIVO_REDUZIDA_POR_ESTORNO = "Reduzida por estorno: abatida da soma da categoria no dia";
    public static final String MOTIVO_CORTE_LIMITE_DIARIO = "Excedente cortado ao limite diário da categoria";
    public static final String MOTIVO_CORTE_LIMITE_LANCAMENTO = "Excedente cortado ao limite de hospedagem por lançamento";

    /** Tolerância da RN-004: valores a até R$ 0,01 de distância são "o mesmo valor". */
    private static final Dinheiro TOLERANCIA_DUPLICATA = Dinheiro.de("0.01");

    /** Chave de agregação diária: dia + categoria normalizada. */
    private record DiaCategoria(LocalDate data, Categoria categoria) {
    }

    /** Item que sobreviveu às recusas e aguarda a distribuição da soma do dia. */
    private record Pendente(int indice, Entrada.DespesaIn despesa, Categoria categoria,
                            Dinheiro valor, PoliticaV4.LimiteCategoria limite) {
    }

    /**
     * Processa todas as despesas da entrada sob a política informada (RN-012).
     * A vigência é pré-condição do lote: política que não vale desde o início
     * do período falha aqui, sem calcular nada.
     */
    public List<ResultadoDespesa> processar(Entrada entrada, Politica politica) {
        Entrada.Periodo periodo = entrada.periodo();
        String centroCusto = entrada.colaborador().centroCusto();

        // Pré-condição do lote (§8 / RN-012 / AMB-021): vigência ≤ periodo.inicio.
        politica.validarVigencia(periodo.inicio());

        List<Elegivel> elegiveis = new ArrayList<>();
        Map<DiaCategoria, Dinheiro> estornosPorDia = new HashMap<>();
        ResultadoDespesa[] resultados = new ResultadoDespesa[entrada.despesas().size()];
        List<Pendente> pendentes = new ArrayList<>();

        // Fase 1 — decisões por item, na ordem da entrada (passos 1-3, 5-7 do §8;
        // o passo 4 — conversão cambial RN-013 — chega na task T-020)
        int indice = 0;
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            Dinheiro valor = Dinheiro.de(despesa.valor()); // RN-010: arredonda na entrada
            Categoria categoria = Categoria.normalizar(despesa.categoria()).orElse(null);

            // Passos 1-2 — RN-001 + RN-012: normaliza e resolve o limite de
            // (centro de custo, categoria). Sem resolução → RN-002 recusa.
            Optional<PoliticaV4.LimiteCategoria> resolucao = categoria == null
                    ? Optional.empty()
                    : politica.resolucao(centroCusto, categoria);
            if (resolucao.isEmpty()) {
                // RN-002 / AMB-014: fora da política → recusa integral, sem consumir cota
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_CATEGORIA_FORA_DA_POLITICA);
                indice++;
                continue;
            }
            if (!resolucao.get().limite().maiorQue(Dinheiro.ZERO)) {
                // RN-012 / AMB-015: limite 0,00 = não reembolsável para o CC —
                // recusa com motivo próprio; viagem não amplia proibição (0 × 1,5 = 0)
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_CATEGORIA_NAO_REEMBOLSAVEL);
                indice++;
                continue;
            }

            // Passo 3 — RN-003: data fora de [inicio, fim] (inclusive nas pontas) → recusa integral
            if (despesa.data().isBefore(periodo.inicio()) || despesa.data().isAfter(periodo.fim())) {
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_FORA_DO_PERIODO);
                indice++;
                continue;
            }

            // Passo 5 — RN-004: duplicata de item elegível já processado → recusa citando a original
            Elegivel atual = new Elegivel(despesa.id(), despesa.data(), categoria,
                    despesa.fornecedor(), valor);
            Elegivel original = buscarOriginalDuplicata(elegiveis, atual);
            if (original != null) {
                resultados[indice] = recusada(despesa.id(), valor, "Duplicata da despesa " + original.id());
                indice++;
                continue;
            }
            elegiveis.add(atual);

            // Passo 6 — RN-009: valor negativo é estorno — recusa o item e abate a soma do dia/categoria
            if (valor.negativo()) {
                DiaCategoria chave = new DiaCategoria(despesa.data(), categoria);
                estornosPorDia.merge(chave, valor, Dinheiro::adicionar);
                resultados[indice] = recusada(despesa.id(), valor, MOTIVO_ESTORNO);
                indice++;
                continue;
            }

            // Passo 7 — RN-005: valor individual estritamente maior que o gatilho
            // da política exige nota (por item, nunca por dia — AMB-004);
            // RN-008/AMB-005: o gatilho NÃO é ampliado em viagem.
            if (valor.maiorQue(politica.gatilhoNotaFiscal()) && !despesa.temNotaFiscal()) {
                resultados[indice] = recusada(despesa.id(), valor,
                        motivoSemNotaFiscal(politica.gatilhoNotaFiscal()));
                indice++;
                continue;
            }

            // Elegível até aqui; o valor final depende do limite do dia/categoria (fase 2)
            pendentes.add(new Pendente(indice, despesa, categoria, valor, resolucao.get()));
            indice++;
        }

        // Fase 2 — passos 8-9 (§8): limites conforme a periodicidade da política.
        // `dia`: distribui a soma elegível do dia (positivos + estornos) limitada
        // ao teto diário, consumindo em ordem de entrada (AMB-001/AMB-002);
        // `diaria`: corta cada lançamento no seu próprio teto (AMB-010).
        Map<DiaCategoria, Dinheiro> positivosPorDia = new HashMap<>();
        for (Pendente p : pendentes) {
            DiaCategoria chave = new DiaCategoria(p.despesa().data(), p.categoria());
            positivosPorDia.merge(chave, p.valor(), Dinheiro::adicionar);
        }

        Map<DiaCategoria, Dinheiro> cotaDisponivel = new HashMap<>();
        Map<DiaCategoria, String> motivoCortePorDia = new HashMap<>();
        for (Pendente p : pendentes) {
            DiaCategoria chave = new DiaCategoria(p.despesa().data(), p.categoria());
            PoliticaV4.LimiteCategoria limite = p.limite();
            boolean porLancamento = Politica.PERIODICIDADE_DIARIA.equals(limite.periodicidade());
            Dinheiro cota = cotaDisponivel.computeIfAbsent(chave, k -> {
                Dinheiro soma = positivosPorDia.get(k)
                        .adicionar(estornosPorDia.getOrDefault(k, Dinheiro.ZERO));
                Dinheiro somaElegivel = soma.negativo() ? Dinheiro.ZERO : soma;
                if (!porLancamento) {
                    // RN-006 (periodicidade `dia`): teto diário agregado da categoria
                    Dinheiro tetoDiario = politica.limiteVigente(limite, periodo.emViagem());
                    if (somaElegivel.maiorQue(tetoDiario)) {
                        // RN-006: o excedente do dia é cortado no limite da categoria
                        motivoCortePorDia.put(k, MOTIVO_CORTE_LIMITE_DIARIO);
                        return tetoDiario;
                    }
                }
                return somaElegivel;
            });
            Dinheiro reembolso = p.valor().menorOuIgualA(cota) ? p.valor() : cota;
            String motivoCorte = motivoCortePorDia.getOrDefault(chave, MOTIVO_REDUZIDA_POR_ESTORNO);
            if (porLancamento) {
                // RN-007 (periodicidade `diaria`): limite por LANCAMENTO, não
                // diário — cada item é cortado individualmente no teto (AMB-010)
                Dinheiro limiteLancamento = politica.limiteVigente(limite, periodo.emViagem());
                if (reembolso.maiorQue(limiteLancamento)) {
                    reembolso = limiteLancamento;
                    motivoCorte = MOTIVO_CORTE_LIMITE_LANCAMENTO;
                }
            }
            cotaDisponivel.put(chave, cota.subtrair(reembolso));
            resultados[p.indice()] = aprovada(p.despesa().id(), p.valor(), reembolso, motivoCorte);
        }

        return List.of(resultados);
    }

    /** RN-005/RN-011: motivo legível citando o gatilho vigente da política (D-003). */
    private String motivoSemNotaFiscal(Dinheiro gatilho) {
        return "Despesa acima de R$ " + gatilho.saida().toPlainString().replace('.', ',') + " sem nota fiscal";
    }

    private ResultadoDespesa recusada(String id, Dinheiro valor, String motivo) {
        return new ResultadoDespesa(id, ResultadoDespesa.Status.RECUSADA,
                valor, Dinheiro.ZERO, List.of(motivo));
    }

    // RN-011: toda decisão carrega motivo — aprovação integral ou corte
    private ResultadoDespesa aprovada(String id, Dinheiro valor, Dinheiro reembolso, String motivoCorte) {
        if (reembolso.equals(valor)) {
            return new ResultadoDespesa(id, ResultadoDespesa.Status.APROVADA,
                    valor, valor, List.of("Aprovada integralmente"));
        }
        return new ResultadoDespesa(id, ResultadoDespesa.Status.PARCIAL,
                valor, reembolso, List.of(motivoCorte));
    }

    /** Item que já sobreviveu às recusas e pode ser original de uma duplicata. */
    private record Elegivel(String id, LocalDate data, Categoria categoria,
                            String fornecedor, Dinheiro valor) {
    }

    /** RN-004: devolve o primeiro elegível que coincide na chave de duplicata, ou null. */
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
                && a.categoria().equals(b.categoria())
                && a.fornecedor().equals(b.fornecedor());
    }

    /** RN-004: tolerância de R$ 0,01 — diferenças visíveis (>= 1 centavo após o
     *  arredondamento da RN-010) NÃO são a mesma despesa: d-003 (100,00) e
     *  d-004 (100,01) não duplicam e d-004 morre por nota (§7). */
    private boolean dentroDaTolerancia(Dinheiro a, Dinheiro b) {
        Dinheiro diferenca = a.subtrair(b);
        if (diferenca.negativo()) {
            diferenca = Dinheiro.ZERO.subtrair(diferenca);
        }
        return diferenca.compareTo(TOLERANCIA_DUPLICATA) < 0;
    }
}
