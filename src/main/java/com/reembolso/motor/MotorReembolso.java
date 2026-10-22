package com.reembolso.motor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.Cambio;
import com.reembolso.io.Entrada;
import com.reembolso.io.PoliticaV4;

/**
 * Motor de cálculo de reembolso: percorre cada despesa pelo pipeline fixo
 * da spec §8, sob a política v4 resolvida por RN-012 e a tabela de câmbio
 * da RN-013. Passos (T-019 + T-020 / D-003):
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
 *   <li>RN-013 — conversão cambial: despesa na moeda base (ausente = BRL,
 *       AMB-018) não sofre conversão nem consulta de taxa; moeda estrangeira
 *       converte pela taxa da **data da despesa** ou da última publicada em
 *       dia igual ou anterior (AMB-016 — nunca taxa futura); sem cotação,
 *       recusa do item com motivo próprio e o lote segue (AMB-017). O valor
 *       convertido é arredondado a 2 casas antes de qualquer comparação;</li>
 *   <li>RN-004 — duplicata (mesma data + categoria normalizada + fornecedor +
 *       **valor em BRL** com tolerância de R$ 0,01 — AMB-022) recusa
 *       integralmente todas exceto a primeira na ordem da entrada, citando o
 *       id da original. Só é duplicata de item que sobreviveu aos passos
 *       anteriores (§8, AMB-006, DT-002);</li>
 *   <li>RN-009 — valor em BRL negativo é estorno: o item é recusado com
 *       motivo próprio e o seu valor reduz a soma elegível do dia/categoria;
 *       soma diária negativa ou zero não gera reembolso nem crédito
 *       (AMB-008);</li>
 *   <li>RN-005 — valor em BRL **estritamente maior** que o gatilho
 *       {@code nota_fiscal_obrigatoria_acima_de} da política exige nota
 *       fiscal — o gatilho dispara sobre o valor convertido (AMB-019), é
 *       lido da política (D-003) e NÃO é ampliado em viagem (RN-008/AMB-005);</li>
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
 * <p>Consequências da ordem (§8): a conversão (passo 4) acontece depois das
 * recusas de categoria e período — despesa em moeda sem cotação **e** fora do
 * período morre como "fora do período"; item sem cotação não possui valor em
 * BRL (não entra em totais, não gasta cota e não serve de original para
 * duplicata); duplicata, estorno e gatilho de nota comparam exclusivamente o
 * valor em BRL.
 */
public final class MotorReembolso {

    public static final String MOTIVO_CATEGORIA_FORA_DA_POLITICA = "Categoria fora da política";
    public static final String MOTIVO_CATEGORIA_NAO_REEMBOLSAVEL = "Categoria não reembolsável para o centro de custo";
    public static final String MOTIVO_FORA_DO_PERIODO = "Despesa fora do período de competência";
    public static final String MOTIVO_SEM_COTACAO = "Moeda sem cotação disponível";
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
                            Dinheiro valor, PoliticaV4.LimiteCategoria limite,
                            ResultadoDespesa.Conversao conversao) {
    }

    /** Taxa cambial aplicável (RN-013/AMB-016): valor e data da publicação usada. */
    private record TaxaVigente(BigDecimal valor, LocalDate publicacao) {
    }

    /**
     * Processa sem tabela de câmbio — spec §4: o câmbio é entrada **opcional**;
     * sem ele, toda despesa em moeda estrangeira é recusada como sem cotação
     * (RN-013/AMB-017).
     */
    public List<ResultadoDespesa> processar(Entrada entrada, Politica politica) {
        return processar(entrada, politica, null);
    }

    /**
     * Processa todas as despesas da entrada sob a política e o câmbio informados
     * (RN-012 + RN-013). A vigência é pré-condição do lote: política que não
     * vale desde o início do período falha aqui, sem calcular nada.
     */
    public List<ResultadoDespesa> processar(Entrada entrada, Politica politica, Cambio cambio) {
        Entrada.Periodo periodo = entrada.periodo();
        String centroCusto = entrada.colaborador().centroCusto();

        // Pré-condição do lote (§8 / RN-012 / AMB-021): vigência ≤ periodo.inicio.
        politica.validarVigencia(periodo.inicio());

        List<Elegivel> elegiveis = new ArrayList<>();
        Map<DiaCategoria, Dinheiro> estornosPorDia = new HashMap<>();
        ResultadoDespesa[] resultados = new ResultadoDespesa[entrada.despesas().size()];
        List<Pendente> pendentes = new ArrayList<>();

        // Fase 1 — decisões por item, na ordem da entrada (passos 1-7 do §8)
        int indice = 0;
        for (Entrada.DespesaIn despesa : entrada.despesas()) {
            Dinheiro valorOriginal = Dinheiro.de(despesa.valor()); // RN-010: arredonda na entrada
            String moeda = despesa.moeda();                        // AMB-018: ausente = BRL (ingestão)
            boolean naMoedaBase = moeda.equals(politica.moedaBase());
            // A conversão acontece no passo 4 (§8): antes dele, despesa em moeda
            // estrangeira ainda não tem valor em BRL (valorSolicitado nulo).
            Dinheiro valorBrl = naMoedaBase ? valorOriginal : null;
            ResultadoDespesa.Conversao conversao = naMoedaBase ? null
                    : new ResultadoDespesa.Conversao(moeda, valorOriginal, null, null);

            Categoria categoria = Categoria.normalizar(despesa.categoria()).orElse(null);

            // Passos 1-2 — RN-001 + RN-012: normaliza e resolve o limite de
            // (centro de custo, categoria). Sem resolução → RN-002 recusa.
            Optional<PoliticaV4.LimiteCategoria> resolucao = categoria == null
                    ? Optional.empty()
                    : politica.resolucao(centroCusto, categoria);
            if (resolucao.isEmpty()) {
                // RN-002 / AMB-014: fora da política → recusa integral, sem consumir cota
                resultados[indice] = recusada(despesa.id(), valorBrl, conversao, MOTIVO_CATEGORIA_FORA_DA_POLITICA);
                indice++;
                continue;
            }
            if (!resolucao.get().limite().maiorQue(Dinheiro.ZERO)) {
                // RN-012 / AMB-015: limite 0,00 = não reembolsável para o CC —
                // recusa com motivo próprio; viagem não amplia proibição (0 × 1,5 = 0)
                resultados[indice] = recusada(despesa.id(), valorBrl, conversao, MOTIVO_CATEGORIA_NAO_REEMBOLSAVEL);
                indice++;
                continue;
            }

            // Passo 3 — RN-003: data fora de [inicio, fim] (inclusive nas pontas) → recusa integral
            if (despesa.data().isBefore(periodo.inicio()) || despesa.data().isAfter(periodo.fim())) {
                resultados[indice] = recusada(despesa.id(), valorBrl, conversao, MOTIVO_FORA_DO_PERIODO);
                indice++;
                continue;
            }

            // Passo 4 — RN-013: converte para a moeda base. AMB-016: taxa da data
            // da despesa ou da última publicada em dia ≤ à data — nunca futura.
            if (!naMoedaBase) {
                TaxaVigente taxa = cambio == null ? null : taxaVigente(cambio, despesa.data(), moeda);
                if (taxa == null) {
                    // AMB-017: moeda sem cotação → recusa do item, o lote segue
                    resultados[indice] = recusada(despesa.id(), null, conversao, MOTIVO_SEM_COTACAO);
                    indice++;
                    continue;
                }
                valorBrl = converter(valorOriginal, taxa.valor());
                conversao = new ResultadoDespesa.Conversao(moeda, valorOriginal, taxa.valor(), taxa.publicacao());
            }

            // Passo 5 — RN-004: duplicata de item elegível já processado, comparando
            // o valor em BRL (AMB-022) → recusa citando a original
            Elegivel atual = new Elegivel(despesa.id(), despesa.data(), categoria,
                    despesa.fornecedor(), valorBrl);
            Elegivel original = buscarOriginalDuplicata(elegiveis, atual);
            if (original != null) {
                resultados[indice] = recusada(despesa.id(), valorBrl, conversao,
                        "Duplicata da despesa " + original.id());
                indice++;
                continue;
            }
            elegiveis.add(atual);

            // Passo 6 — RN-009: valor em BRL negativo é estorno — recusa o item
            // e abate a soma elegível do dia/categoria
            if (valorBrl.negativo()) {
                DiaCategoria chave = new DiaCategoria(despesa.data(), categoria);
                estornosPorDia.merge(chave, valorBrl, Dinheiro::adicionar);
                resultados[indice] = recusada(despesa.id(), valorBrl, conversao, MOTIVO_ESTORNO);
                indice++;
                continue;
            }

            // Passo 7 — RN-005: valor em BRL estritamente maior que o gatilho da
            // política exige nota (por item, nunca por dia — AMB-004; sobre o valor
            // convertido — AMB-019); RN-008/AMB-005: o gatilho NÃO é ampliado em viagem.
            if (valorBrl.maiorQue(politica.gatilhoNotaFiscal()) && !despesa.temNotaFiscal()) {
                resultados[indice] = recusada(despesa.id(), valorBrl, conversao,
                        motivoSemNotaFiscal(politica.gatilhoNotaFiscal()));
                indice++;
                continue;
            }

            // Elegível até aqui; o valor final depende do limite do dia/categoria (fase 2)
            pendentes.add(new Pendente(indice, despesa, categoria, valorBrl, resolucao.get(), conversao));
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
            resultados[p.indice()] = aprovada(p.despesa().id(), p.valor(), reembolso, motivoCorte, p.conversao());
        }

        return List.of(resultados);
    }

    // ------------------------------------------------------------ conversão cambial (RN-013)

    /** RN-013/RN-010: valor em BRL = valor original × taxa, HALF_UP 2 casas, antes de qualquer comparação. */
    private static Dinheiro converter(Dinheiro valorOriginal, BigDecimal taxa) {
        return Dinheiro.de(valorOriginal.saida().multiply(taxa).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * RN-013/AMB-016: taxa da data da despesa ou da última publicação em dia
     * igual ou anterior — nunca taxa futura. Sem nenhuma publicação para a
     * moeda em data ≤ à da despesa, devolve null (recusa por item, AMB-017).
     */
    private static TaxaVigente taxaVigente(Cambio cambio, LocalDate dataDespesa, String moeda) {
        LocalDate publicada = null;
        for (Map.Entry<LocalDate, Map<String, BigDecimal>> publicacao : cambio.taxas().entrySet()) {
            LocalDate data = publicacao.getKey();
            if (data.isAfter(dataDespesa) || !publicacao.getValue().containsKey(moeda)) {
                continue;
            }
            if (publicada == null || data.isAfter(publicada)) {
                publicada = data;
            }
        }
        return publicada == null ? null
                : new TaxaVigente(cambio.taxas().get(publicada).get(moeda), publicada);
    }

    // ------------------------------------------------------------ decisões

    /** RN-005/RN-011: motivo legível citando o gatilho vigente da política (D-003). */
    private String motivoSemNotaFiscal(Dinheiro gatilho) {
        return "Despesa acima de R$ " + gatilho.saida().toPlainString().replace('.', ',') + " sem nota fiscal";
    }

    private ResultadoDespesa recusada(String id, Dinheiro valorSolicitado,
                                      ResultadoDespesa.Conversao conversao, String motivo) {
        return new ResultadoDespesa(id, ResultadoDespesa.Status.RECUSADA,
                valorSolicitado, Dinheiro.ZERO, List.of(motivo), conversao);
    }

    // RN-011: toda decisão carrega motivo — aprovação integral ou corte
    private ResultadoDespesa aprovada(String id, Dinheiro valor, Dinheiro reembolso,
                                      String motivoCorte, ResultadoDespesa.Conversao conversao) {
        if (reembolso.equals(valor)) {
            return new ResultadoDespesa(id, ResultadoDespesa.Status.APROVADA,
                    valor, valor, List.of("Aprovada integralmente"), conversao);
        }
        return new ResultadoDespesa(id, ResultadoDespesa.Status.PARCIAL,
                valor, reembolso, List.of(motivoCorte), conversao);
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
