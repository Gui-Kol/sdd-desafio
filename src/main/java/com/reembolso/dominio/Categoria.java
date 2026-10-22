package com.reembolso.dominio;

import java.util.Locale;
import java.util.Optional;

/**
 * Categoria normalizada (RN-001 / AMB-009): a comparação é case-insensitive
 * e sem espaços nas bordas — {@code "ALIMENTACAO"} equivale a
 * {@code "alimentacao"}.
 *
 * <p>Até a v3 a política trazia um catálogo fechado; com a política v4
 * (D-003) o catálogo é dado: uma categoria é reembolsável quando a política
 * resolve limite para (centro de custo, categoria) — decisão do motor
 * (RN-012). Aqui só existe a normalização: qualquer nome não vazio é uma
 * categoria válida; estar na política é outra conversa.
 */
public record Categoria(String chave) {

    public static final Categoria ALIMENTACAO = new Categoria("alimentacao");
    public static final Categoria TRANSPORTE_URBANO = new Categoria("transporte_urbano");
    public static final Categoria HOSPEDAGEM = new Categoria("hospedagem");

    /**
     * Normaliza a categoria bruta da entrada (trim + caixa baixa,
     * Locale.ROOT). Vazia ou nula não é categoria — o motor recusa com o
     * motivo de categoria fora da política (RN-002), pois nunca haverá
     * resolução de limite para ela (RN-012).
     */
    public static Optional<Categoria> normalizar(String categoriaBruta) {
        if (categoriaBruta == null) {
            return Optional.empty();
        }
        String normalizada = categoriaBruta.trim().toLowerCase(Locale.ROOT);
        if (normalizada.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Categoria(normalizada));
    }
}
