package com.reembolso.dominio;

import java.util.Locale;
import java.util.Optional;

/**
 * Catalogo fechado de categorias reembolsaveis (RN-001): exatamente
 * {@code alimentacao}, {@code transporte_urbano}, {@code hospedagem}.
 *
 * <p>RN-001 / AMB-009: a comparacao e case-insensitive e ignora espacos nas
 * bordas — {@code "ALIMENTACAO"} equivale a {@code "alimentacao"}. Qualquer
 * categoria fora do catalogo nao e reconhecida e cai na recusa RN-002.
 */
public enum Categoria {
    ALIMENTACAO("alimentacao"),
    TRANSPORTE_URBANO("transporte_urbano"),
    HOSPEDAGEM("hospedagem");

    private final String chave;

    Categoria(String chave) {
        this.chave = chave;
    }

    /** Nome canonico usado em chaves de agregacao, duplicatas e motivos. */
    public String chave() {
        return chave;
    }

    /**
     * Normaliza a categoria bruta da entrada (trim + caixa baixa, Locale.ROOT)
     * e reconhece contra o catalogo da RN-001.
     *
     * @return categoria reconhecida, ou vazio se fora da politica (RN-002).
     */
    public static Optional<Categoria> normalizar(String categoriaBruta) {
        if (categoriaBruta == null) {
            return Optional.empty();
        }
        String normalizada = categoriaBruta.trim().toLowerCase(Locale.ROOT);
        for (Categoria c : values()) {
            if (c.chave.equals(normalizada)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
