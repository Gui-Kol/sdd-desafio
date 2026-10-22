package com.reembolso.motor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.reembolso.dominio.Categoria;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.PoliticaV4;

/**
 * Política de reembolso aplicada ao cálculo (RN-012, T-019 / D-003): envolve
 * os dados da política v4 e resolve, para cada (centro de custo, categoria),
 * o limite vigente e sua periodicidade.
 *
 * <p>Resolução (RN-012 / AMB-014 / AMB-015): entrada em
 * {@code centros_custo[cc][categoria]} quando existir; senão
 * {@code padrao[categoria]}; ausente em ambos → categoria fora da política
 * (decisão do motor, RN-002). Centro de custo sem bloco resolve tudo pelo
 * {@code padrao}. Limite 0,00 = categoria não reembolsável para o CC —
 * também decisão do motor, que recusa com motivo próprio.
 *
 * <p>RN-008: limite vigente = base × (1 + {@code acrescimo_em_viagem_percentual}/100)
 * quando {@code em_viagem}; o gatilho de nota (RN-005) nunca é ampliado
 * (AMB-005). Toda leitura de limite e de gatilho passa por aqui — mudança de
 * política = editar o arquivo, não caçar números no motor.
 */
public final class Politica {

    /** Periodicidade da spec §4: limite diário agregado (RN-006). */
    public static final String PERIODICIDADE_DIA = "dia";

    /** Periodicidade da spec §4: limite por lançamento (RN-007). */
    public static final String PERIODICIDADE_DIARIA = "diaria";

    private final PoliticaV4 dados;
    private final Map<String, PoliticaV4.LimiteCategoria> padrao;
    private final Map<String, Map<String, PoliticaV4.LimiteCategoria>> centrosCusto;

    private Politica(PoliticaV4 dados,
                     Map<String, PoliticaV4.LimiteCategoria> padrao,
                     Map<String, Map<String, PoliticaV4.LimiteCategoria>> centrosCusto) {
        this.dados = dados;
        this.padrao = padrao;
        this.centrosCusto = centrosCusto;
    }

    /**
     * Envolve os dados da política v4 validando as periodicidades (RN-012):
     * só existem {@code dia} e {@code diaria} (semântica fixa na spec §4).
     * Qualquer outro valor é erro de processamento — falha aqui, nada é
     * calculado.
     */
    public static Politica de(PoliticaV4 dados) {
        Objects.requireNonNull(dados, "politica nao pode ser nula");
        Objects.requireNonNull(dados.padrao(), "bloco padrao nao pode ser nulo");
        Map<String, PoliticaV4.LimiteCategoria> padrao = blocoNormalizado(dados.padrao());
        Map<String, Map<String, PoliticaV4.LimiteCategoria>> centros = new LinkedHashMap<>();
        dados.centrosCusto().forEach((centro, bloco) -> centros.put(centro, blocoNormalizado(bloco)));
        return new Politica(dados, padrao, Map.copyOf(centros));
    }

    /** RN-001: chaves de categoria da política também são normalizadas (case/trim). */
    private static Map<String, PoliticaV4.LimiteCategoria> blocoNormalizado(
            Map<String, PoliticaV4.LimiteCategoria> bloco) {
        Map<String, PoliticaV4.LimiteCategoria> normalizado = new LinkedHashMap<>();
        bloco.forEach((categoria, limite) -> {
            validarPeriodicidade(limite.periodicidade(), categoria);
            normalizado.put(chaveNormalizada(categoria), limite);
        });
        return Map.copyOf(normalizado);
    }

    private static void validarPeriodicidade(String periodicidade, String categoria) {
        if (!PERIODICIDADE_DIA.equals(periodicidade) && !PERIODICIDADE_DIARIA.equals(periodicidade)) {
            throw new IllegalArgumentException("Periodicidade desconhecida '" + periodicidade
                    + "' na categoria '" + categoria + "' da política — valores válidos: '"
                    + PERIODICIDADE_DIA + "' (limite diário agregado) e '"
                    + PERIODICIDADE_DIARIA + "' (limite por lançamento) [RN-012]");
        }
    }

    private static String chaveNormalizada(String bruta) {
        return bruta == null ? "" : bruta.trim().toLowerCase(Locale.ROOT);
    }

    public LocalDate vigencia() {
        return dados.vigencia();
    }

    /**
     * Pré-condição do lote (RN-012 / AMB-021): a política deve valer desde o
     * início do período. Vigência posterior ao início — inclusive vigência
     * no meio do período — é erro de processamento: nada é calculado.
     */
    public void validarVigencia(LocalDate inicioPeriodo) {
        if (dados.vigencia().isAfter(inicioPeriodo)) {
            throw new IllegalArgumentException("Política com vigência " + dados.vigencia()
                    + " posterior ao início do período " + inicioPeriodo
                    + " — nada calculado [RN-012]");
        }
    }

    /** RN-005: gatilho da exigência de nota, lido da política (D-003); nunca ampliado em viagem. */
    public Dinheiro gatilhoNotaFiscal() {
        return dados.notaFiscalObrigatoriaAcimaDe();
    }

    /**
     * RN-012: limite vigente da categoria para o centro de custo — override do
     * CC ou herança do bloco {@code padrao} (AMB-014/AMB-015). Vazio =
     * categoria fora da política para aquele centro de custo.
     *
     * <p>Centro de custo é comparado exatamente (código); categoria é
     * normalizada (RN-001).
     */
    public Optional<PoliticaV4.LimiteCategoria> resolucao(String centroCusto, Categoria categoria) {
        Map<String, PoliticaV4.LimiteCategoria> bloco = centrosCusto.get(centroCusto);
        if (bloco != null) {
            PoliticaV4.LimiteCategoria limite = bloco.get(categoria.chave());
            if (limite != null) {
                return Optional.of(limite);
            }
        }
        return Optional.ofNullable(padrao.get(categoria.chave()));
    }

    /**
     * RN-008: limite vigente = base × (1 + acréscimo/100) quando em viagem,
     * HALF_UP 2 casas (RN-010). Sem viagem, devolve a base. Limite 0,00
     * permanece 0,00 — mas o motor já recusou a categoria não reembolsável
     * antes de qualquer limite (AMB-015).
     */
    public Dinheiro limiteVigente(PoliticaV4.LimiteCategoria limite, boolean emViagem) {
        Dinheiro base = limite.limite();
        if (!emViagem) {
            return base;
        }
        BigDecimal fator = BigDecimal.ONE.add(dados.acrescimoEmViagemPercentual().movePointLeft(2));
        return Dinheiro.de(base.saida().multiply(fator).setScale(2, RoundingMode.HALF_UP));
    }
}
