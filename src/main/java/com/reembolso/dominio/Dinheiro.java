package com.reembolso.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetario imutavel em reais, sempre com 2 casas decimais,
 * arredondado "metade para cima" (HALF_UP). Implementa RN-010 / AMB-011.
 *
 * <p>Toda conversao cruza a fronteira do dominio por {@link #de(String)} e toda
 * saida usa {@link #saida()}; o motor nunca manipula {@code double} (DT-001).
 */
public final class Dinheiro implements Comparable<Dinheiro> {

    public static final Dinheiro ZERO = new Dinheiro( BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) );

    private final BigDecimal valor;

    private Dinheiro(BigDecimal valor) {
        this.valor = valor;
    }

    /** Fronteira de entrada: aceita string (preserva precisao bruta do JSON) e aplica HALF_UP para 2 casas. */
    public static Dinheiro de(String bruto) {
        Objects.requireNonNull(bruto, "valor monetario nao pode ser nulo");
        return new Dinheiro(new BigDecimal(bruto).setScale(2, RoundingMode.HALF_UP));
    }

    /** Conveniencia para constantes em testes/codigo. Prefira {@link #de(String)} para dados externos. */
    public static Dinheiro de(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor monetario nao pode ser nulo");
        return new Dinheiro(valor.setScale(2, RoundingMode.HALF_UP));
    }

    public Dinheiro adicionar(Dinheiro outro) {
        return new Dinheiro(this.valor.add(outro.valor));
    }

    public Dinheiro subtrair(Dinheiro outro) {
        return new Dinheiro(this.valor.subtract(outro.valor));
    }

    public boolean maiorQue(Dinheiro outro) {
        return this.compareTo(outro) > 0;
    }

    public boolean menorOuIgualA(Dinheiro outro) {
        return this.compareTo(outro) <= 0;
    }

    public boolean negativo() {
        return this.valor.signum() < 0;
    }

    /** Valor pronto para a saida JSON: exatamente 2 casas decimais (RN-010). */
    public BigDecimal saida() {
        return valor;
    }

    @Override
    public int compareTo(Dinheiro outro) {
        return this.valor.compareTo(outro.valor);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Dinheiro outro)) return false;
        return valor.compareTo(outro.valor) == 0;
    }

    @Override
    public int hashCode() {
        return valor.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
