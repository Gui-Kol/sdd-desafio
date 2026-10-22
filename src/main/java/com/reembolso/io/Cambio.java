package com.reembolso.io;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Tabela de cambio lida do arquivo (spec §4, T-018 / D-003; base de RN-013).
 *
 * <p>Taxa = BRL por unidade da moeda, publicada por dia util bancario. A
 * escolha de qual taxa aplicar por data e decisao do motor (RN-013, T-020):
 * aqui so se representa a tabela. Taxa e fator, nao dinheiro — preservada
 * como BigDecimal exato; so o valor convertido e arredondado a 2 casas.
 */
public record Cambio(String moedaBase, Map<LocalDate, Map<String, BigDecimal>> taxas) {
}
