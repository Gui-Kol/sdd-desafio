package com.reembolso.io;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.reembolso.dominio.Dinheiro;

/**
 * Politica v4 lida do arquivo (spec §4, T-018 / D-003). Espelho fiel do
 * `exemplos/politica-v4.json`: versao, vigencia, moeda base, limites padrao,
 * overrides por centro de custo, gatilho de nota e acrescimo de viagem.
 *
 * <p>Aqui so se REPRESENTA o dado: normalizacao de categoria, resolucao de
 * limites por centro de custo, vigencia vs periodo e semantica de
 * `periodicidade` sao regras do motor (RN-012, task T-019). A `observacao`
 * e texto livre do RH (ex.: "nao reembolsavel"), lido mas interpretado
 * apenas pela regra de limite zero (RN-012, T-019).
 */
public record PoliticaV4(
        String versao,
        LocalDate vigencia,
        String moedaBase,
        Map<String, LimiteCategoria> padrao,
        Map<String, Map<String, LimiteCategoria>> centrosCusto,
        Dinheiro notaFiscalObrigatoriaAcimaDe,
        BigDecimal acrescimoEmViagemPercentual) {

    /**
     * Limite de uma categoria na politica: valor em BRL (2 casas, RN-010),
     * periodicidade (`dia` ou `diaria`, semantica fixa na spec §4) e
     * observacao opcional do RH.
     */
    public record LimiteCategoria(Dinheiro limite, String periodicidade, String observacao) {
    }
}
