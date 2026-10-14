package com.reembolso.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fronteira de leitura: JSON -> Entrada (records imutaveis).
 *
 * <p>Parsia por arvore (JsonNode) e converte campo a campo, para que:
 * <ul>
 *   <li>`valor` chegue como string exata do arquivo ("33.333", "72.50");</li>
 *   <li>campos ausentes/invalidos gerem erro legivel com o nome do campo;</li>
 *   <li>`em_viagem` ausente vire `false` (AMB-005).</li>
 * </ul>
 */
public final class Ingestao {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Ingestao() {
    }

    public static Entrada ler(Path caminho) {
        String json;
        try {
            json = Files.readString(caminho);
        } catch (IOException e) {
            throw new IngestaoException("Nao foi possivel ler o arquivo de entrada: " + caminho + " (" + e.getMessage() + ")");
        }
        return lerDeString(json);
    }

    public static Entrada lerDeString(String json) {
        JsonNode raiz;
        try {
            raiz = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IngestaoException("JSON de entrada malformado: " + e.getMessage());
        }
        try {
            return mapear(raiz);
        } catch (IngestaoException e) {
            throw e;
        } catch (Exception e) {
            throw new IngestaoException("Erro de validacao no JSON de entrada: " + e.getMessage());
        }
    }

    private static Entrada mapear(JsonNode raiz) {
        JsonNode colaboradorNode = obrigatorio(raiz, "colaborador");
        Entrada.Colaborador colaborador = new Entrada.Colaborador(
                texto(colaboradorNode, "id"),
                texto(colaboradorNode, "nome"),
                texto(colaboradorNode, "centro_custo"));

        JsonNode periodoNode = obrigatorio(raiz, "periodo");
        Entrada.Periodo periodo = new Entrada.Periodo(
                texto(periodoNode, "competencia"),
                data(periodoNode, "inicio"),
                data(periodoNode, "fim"),
                booleano(periodoNode, "em_viagem", false)); // AMB-005: ausente = false

        JsonNode despesasNode = obrigatorio(raiz, "despesas");
        if (!despesasNode.isArray()) {
            throw new IngestaoException("Campo 'despesas' deve ser uma lista.");
        }
        List<Entrada.DespesaIn> despesas = new ArrayList<>();
        for (JsonNode d : despesasNode) {
            despesas.add(new Entrada.DespesaIn(
                    texto(d, "id"),
                    data(d, "data"),
                    texto(d, "categoria"),
                    texto(d, "descricao"),
                    texto(d, "fornecedor"),
                    textoValor(d, "valor"),
                    booleano(d, "tem_nota_fiscal", null)));
        }
        return new Entrada(colaborador, periodo, List.copyOf(despesas));
    }

    private static JsonNode obrigatorio(JsonNode pai, String campo) {
        JsonNode node = pai != null ? pai.get(campo) : null;
        if (node == null || node.isNull()) {
            throw new IngestaoException("Campo obrigatorio ausente: '" + campo + "'");
        }
        return node;
    }

    private static String texto(JsonNode pai, String campo) {
        JsonNode node = obrigatorio(pai, campo);
        if (node.isNull() || node.asText().isBlank()) {
            throw new IngestaoException("Campo '" + campo + "' nao pode ser vazio.");
        }
        return node.asText();
    }

    /** Valor chega como texto exato do JSON (numero ou string), sem arredondamento silencioso (DT-001). */
    private static String textoValor(JsonNode pai, String campo) {
        JsonNode node = obrigatorio(pai, campo);
        if (!node.isNumber() && !node.isTextual()) {
            throw new IngestaoException("Campo 'valor' deve ser numero: '" + node + "'");
        }
        return node.asText();
    }

    private static LocalDate data(JsonNode pai, String campo) {
        String bruto = texto(pai, campo);
        try {
            return LocalDate.parse(bruto);
        } catch (DateTimeParseException e) {
            throw new IngestaoException("Campo '" + campo + "' nao e data AAAA-MM-DD: '" + bruto + "'");
        }
    }

    private static boolean booleano(JsonNode pai, String campo, Boolean padrao) {
        JsonNode node = pai.get(campo);
        if (node == null || node.isNull()) {
            if (padrao == null) {
                throw new IngestaoException("Campo obrigatorio ausente: '" + campo + "'");
            }
            return padrao;
        }
        if (!node.isBoolean()) {
            throw new IngestaoException("Campo '" + campo + "' deve ser booleano: '" + node + "'");
        }
        return node.asBoolean();
    }

    /** Erro de leitura/validacao com mensagem legivel para o operador da CLI. */
    public static final class IngestaoException extends RuntimeException {
        public IngestaoException(String message) {
            super(message);
        }
    }
}
