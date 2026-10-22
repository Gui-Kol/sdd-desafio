package com.reembolso;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.reembolso.dominio.Dinheiro;
import com.reembolso.io.PoliticaV4;
import com.reembolso.motor.Politica;

/**
 * A política v3 do RH (itens 1–6 da redação original) codificada no formato
 * v4 — o dado que sustenta as expectativas douradas do Dia 1 (T-016 e os
 * testes do motor): padrão 60/80/250, gatilho 100,00, acréscimo 50%, sem
 * overrides de centro de custo.
 *
 * <p>D-003: a política v4 real traz override para o CC-ENG-PLATAFORMA (o CC
 * do arquivo-exemplo do Dia 1), o que mudaria os números dourados — por isso
 * os testes do Dia 1 rodam sob esta política e os testes do envelope sob a
 * v4 real. A T-022 substitui a ponte da CLI pela flag --politica.
 */
public final class PoliticaV3 {

    private PoliticaV3() {
    }

    public static PoliticaV4 dados() {
        Map<String, PoliticaV4.LimiteCategoria> padrao = Map.of(
                "alimentacao", new PoliticaV4.LimiteCategoria(Dinheiro.de("60.00"), "dia", null),
                "transporte_urbano", new PoliticaV4.LimiteCategoria(Dinheiro.de("80.00"), "dia", null),
                "hospedagem", new PoliticaV4.LimiteCategoria(Dinheiro.de("250.00"), "diaria", null));
        return new PoliticaV4("v3", LocalDate.of(2026, 1, 1), "BRL",
                padrao, Map.of(), Dinheiro.de("100.00"), BigDecimal.valueOf(50));
    }

    public static Politica politica() {
        return Politica.de(dados());
    }
}
