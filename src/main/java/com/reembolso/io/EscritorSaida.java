package com.reembolso.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reembolso.dominio.Dinheiro;
import com.reembolso.materializacao.Saida;

/**
 * Fronteira de escrita: Saida -> JSON no formato fixo da spec §4 (T-014).
 *
 * <p>Monta a arvore campo a campo com {@link JsonNodeFactory} para que os
 * numeros monetarios sejam serializados a partir de {@link Dinheiro#saida()}
 * (BigDecimal scale 2): "72.50" sai como {@code 72.50}, nunca {@code 72.5}
 * (RN-010 — exatamente 2 casas decimais).
 */
public final class EscritorSaida {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // withExactBigDecimals: preserva a escala do BigDecimal ("72.50" nao vira 72.5) — RN-010
    private static final JsonNodeFactory JSON = JsonNodeFactory.withExactBigDecimals(true);

    private EscritorSaida() {
    }

    /** Serializa a saida como texto JSON (usado pela CLI e pelos testes). */
    public static String escreverJson(Saida saida) {
        try {
            return MAPPER.writeValueAsString(no(saida));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao serializar a saida JSON", e);
        }
    }

    /** Escreve a saida em arquivo (UTF-8), criando diretorios pais se preciso. */
    public static void escrever(Saida saida, Path destino) {
        try {
            if (destino.getParent() != null) {
                Files.createDirectories(destino.getParent());
            }
            Files.writeString(destino, escreverJson(saida), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Nao foi possivel escrever o arquivo de saida: " + destino, e);
        }
    }

    private static ObjectNode no(Saida saida) {
        ObjectNode raiz = JSON.objectNode();
        raiz.put("colaborador_id", saida.colaboradorId());
        raiz.put("competencia", saida.competencia());
        raiz.set("total_solicitado", JSON.numberNode(saida.totalSolicitado().saida()));
        raiz.set("total_reembolsado", JSON.numberNode(saida.totalReembolsado().saida()));
        ArrayNode despesas = raiz.putArray("despesas");
        for (Saida.DespesaOut d : saida.despesas()) {
            ObjectNode no = JSON.objectNode();
            no.put("id", d.id());
            no.put("status", d.status());
            no.put("moeda", d.moeda());
            no.set("valor_original", JSON.numberNode(d.valorOriginal().saida()));
            if (d.valorSolicitado() != null) {
                no.set("valor_solicitado", JSON.numberNode(d.valorSolicitado().saida()));
            } else {
                // sem cotação não há valor em BRL (spec §4: nulo; item não entra nos totais)
                no.putNull("valor_solicitado");
            }
            no.set("valor_reembolsado", JSON.numberNode(d.valorReembolsado().saida()));
            if (d.conversao() != null) {
                // moeda estrangeira: taxa_cambio/data_taxa presentes — nulos quando
                // nenhuma taxa foi aplicada (AMB-017); moeda base: campos não existem
                if (d.conversao().taxa() != null) {
                    no.set("taxa_cambio", JSON.numberNode(d.conversao().taxa()));
                } else {
                    no.putNull("taxa_cambio");
                }
                if (d.conversao().dataTaxa() != null) {
                    no.put("data_taxa", d.conversao().dataTaxa().toString());
                } else {
                    no.putNull("data_taxa");
                }
            }
            ArrayNode motivos = no.putArray("motivos");
            d.motivos().forEach(motivos::add);
            despesas.add(no);
        }
        return raiz;
    }
}
