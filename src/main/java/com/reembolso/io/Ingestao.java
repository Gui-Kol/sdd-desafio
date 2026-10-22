package com.reembolso.io;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reembolso.dominio.Dinheiro;

/**
 * Fronteira de leitura: JSON -> records imutaveis.
 *
 * <p>T-003 (despesas) e T-018 (politica v4, cambio e campo `moeda` — D-003).
 * Parsia por arvore (JsonNode) e converte campo a campo, para que:
 * <ul>
 *   <li>`valor` chegue como string exata do arquivo ("33.333", "72.50");</li>
 *   <li>campos ausentes/invalidos gerem erro legivel com o nome do campo;</li>
 *   <li>`em_viagem` ausente vire `false` (AMB-005) e `moeda` ausente vire
 *       "BRL" (AMB-018);</li>
 *   <li>limites da politica virem `Dinheiro` (2 casas, RN-010) e taxas de
 *       cambio fiquem em BigDecimal exato — taxa e fator, nao dinheiro.</li>
 * </ul>
 *
 * <p>Aqui so se le e valida ESTRUTURA (obrigatoriedade e tipo, spec §4):
 * normalizacao de categoria, resolucao de limites por centro de custo,
 * vigencia vs periodo e escolha de taxa por data sao regras do motor
 * (RN-012/RN-013, tasks T-019/T-020).
 */
public final class Ingestao {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** AMB-018: moeda default de uma despesa sem `moeda` informada (moeda base, spec §4). */
    private static final String MOEDA_PADRAO = "BRL";

    private Ingestao() {
    }

    // ------------------------------------------------------------ despesas (T-003)

    public static Entrada ler(Path caminho) {
        return lerDeString(lerArquivo(caminho, "entrada"));
    }

    public static Entrada lerDeString(String json) {
        JsonNode raiz = arvore(json, "entrada");
        try {
            return mapear(raiz);
        } catch (IngestaoException e) {
            throw e;
        } catch (Exception e) {
            throw new IngestaoException("Erro de validacao no JSON de entrada: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ politica v4 (T-018)

    /** Le o arquivo de politica v4 (spec §4; `exemplos/politica-v4.json`). */
    public static PoliticaV4 lerPolitica(Path caminho) {
        return lerPoliticaDeString(lerArquivo(caminho, "politica"));
    }

    public static PoliticaV4 lerPoliticaDeString(String json) {
        JsonNode raiz = arvore(json, "politica");
        try {
            return mapearPolitica(raiz);
        } catch (IngestaoException e) {
            throw e;
        } catch (Exception e) {
            throw new IngestaoException("Erro de validacao no JSON de politica: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ cambio (T-018)

    /** Le a tabela de cambio (spec §4; `exemplos/cambio.json`). Entrada opcional da RN-013. */
    public static Cambio lerCambio(Path caminho) {
        return lerCambioDeString(lerArquivo(caminho, "cambio"));
    }

    public static Cambio lerCambioDeString(String json) {
        JsonNode raiz = arvore(json, "cambio");
        try {
            return mapearCambio(raiz);
        } catch (IngestaoException e) {
            throw e;
        } catch (Exception e) {
            throw new IngestaoException("Erro de validacao no JSON de cambio: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ mapeamentos

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
                    moedaDespesa(d), // AMB-018: ausente = BRL
                    booleano(d, "tem_nota_fiscal", null)));
        }
        return new Entrada(colaborador, periodo, List.copyOf(despesas));
    }

    /**
     * T-018: espelha a politica v4 (spec §4). Obrigatorios: versao, vigencia,
     * moeda_base, padrao, nota_fiscal_obrigatoria_acima_de e
     * acrescimo_em_viagem_percentual; `centros_custo` e opcional.
     */
    private static PoliticaV4 mapearPolitica(JsonNode raiz) {
        JsonNode padraoNode = obrigatorio(raiz, "padrao");
        if (!padraoNode.isObject()) {
            throw new IngestaoException("Campo 'padrao' deve ser um objeto de categorias.");
        }
        Map<String, PoliticaV4.LimiteCategoria> padrao = blocoLimites(padraoNode, "padrao");

        Map<String, Map<String, PoliticaV4.LimiteCategoria>> centrosCusto = new LinkedHashMap<>();
        JsonNode centrosNode = raiz.get("centros_custo"); // opcional (spec §4)
        if (centrosNode != null && !centrosNode.isNull()) {
            if (!centrosNode.isObject()) {
                throw new IngestaoException("Campo 'centros_custo' deve ser um objeto (centro de custo -> categorias).");
            }
            Iterator<Map.Entry<String, JsonNode>> centros = centrosNode.fields();
            while (centros.hasNext()) {
                Map.Entry<String, JsonNode> centro = centros.next();
                centrosCusto.put(centro.getKey(), blocoLimites(centro.getValue(), "centros_custo." + centro.getKey()));
            }
        }

        return new PoliticaV4(
                texto(raiz, "versao"),
                data(raiz, "vigencia"),
                texto(raiz, "moeda_base"),
                padrao,
                Map.copyOf(centrosCusto),
                dinheiro(raiz, "nota_fiscal_obrigatoria_acima_de", null),
                numero(raiz, "acrescimo_em_viagem_percentual", null));
    }

    /** Bloco categoria -> {limite, periodicidade, observacao?} (spec §4). Erros citam o caminho. */
    private static Map<String, PoliticaV4.LimiteCategoria> blocoLimites(JsonNode bloco, String caminho) {
        Map<String, PoliticaV4.LimiteCategoria> limites = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> campos = bloco.fields();
        while (campos.hasNext()) {
            Map.Entry<String, JsonNode> campo = campos.next();
            String categoria = campo.getKey();
            JsonNode limite = campo.getValue();
            String contexto = caminho + "." + categoria;
            if (!limite.isObject()) {
                throw new IngestaoException("Limite de '" + categoria + "' deve ser um objeto {limite, periodicidade}" + sufixo(contexto) + ".");
            }
            JsonNode observacao = limite.get("observacao"); // opcional
            limites.put(categoria, new PoliticaV4.LimiteCategoria(
                    dinheiro(limite, "limite", contexto),
                    texto(limite, "periodicidade", contexto),
                    observacao == null || observacao.isNull() ? null : observacao.asText()));
        }
        return Map.copyOf(limites);
    }

    /** T-018: espelha a tabela de cambio (spec §4): taxas[data][moeda] = BRL por unidade. */
    private static Cambio mapearCambio(JsonNode raiz) {
        String moedaBase = texto(raiz, "moeda_base");
        JsonNode taxasNode = obrigatorio(raiz, "taxas");
        if (!taxasNode.isObject()) {
            throw new IngestaoException("Campo 'taxas' deve ser um objeto (data -> moeda -> taxa).");
        }
        Map<LocalDate, Map<String, BigDecimal>> taxas = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> datas = taxasNode.fields();
        while (datas.hasNext()) {
            Map.Entry<String, JsonNode> dataTaxa = datas.next();
            LocalDate dia = parseData(dataTaxa.getKey());
            JsonNode moedasNode = dataTaxa.getValue();
            if (!moedasNode.isObject()) {
                throw new IngestaoException("Taxas de '" + dataTaxa.getKey() + "' devem ser um objeto (moeda -> taxa).");
            }
            Map<String, BigDecimal> moedas = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> moedasCampos = moedasNode.fields();
            while (moedasCampos.hasNext()) {
                Map.Entry<String, JsonNode> moeda = moedasCampos.next();
                moedas.put(moeda.getKey(), taxa(moeda.getValue(), "taxas." + dataTaxa.getKey() + "." + moeda.getKey()));
            }
            taxas.put(dia, Map.copyOf(moedas));
        }
        return new Cambio(moedaBase, Map.copyOf(taxas));
    }

    // ------------------------------------------------------------ helpers de leitura

    private static String lerArquivo(Path caminho, String oQue) {
        try {
            return Files.readString(caminho);
        } catch (IOException e) {
            throw new IngestaoException("Nao foi possivel ler o arquivo de " + oQue + ": " + caminho + " (" + e.getMessage() + ")");
        }
    }

    private static JsonNode arvore(String json, String oQue) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IngestaoException("JSON de " + oQue + " malformado: " + e.getMessage());
        }
    }

    private static JsonNode obrigatorio(JsonNode pai, String campo) {
        return obrigatorio(pai, campo, null);
    }

    private static JsonNode obrigatorio(JsonNode pai, String campo, String contexto) {
        JsonNode node = pai != null ? pai.get(campo) : null;
        if (node == null || node.isNull()) {
            throw new IngestaoException("Campo obrigatorio ausente: '" + campo + "'" + sufixo(contexto));
        }
        return node;
    }

    private static String texto(JsonNode pai, String campo) {
        return texto(pai, campo, null);
    }

    private static String texto(JsonNode pai, String campo, String contexto) {
        JsonNode node = obrigatorio(pai, campo, contexto);
        if (node.asText().isBlank()) {
            throw new IngestaoException("Campo '" + campo + "' nao pode ser vazio" + sufixo(contexto) + ".");
        }
        return node.asText();
    }

    private static String sufixo(String contexto) {
        return contexto == null ? "" : " (em " + contexto + ")";
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

    /** Chave de data no mapa de taxas: AAAA-MM-DD (mesma garantia do campo de data). */
    private static LocalDate parseData(String bruto) {
        try {
            return LocalDate.parse(bruto);
        } catch (DateTimeParseException e) {
            throw new IngestaoException("Chave de data em 'taxas' nao e AAAA-MM-DD: '" + bruto + "'");
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

    /** AMB-018: `despesas[].moeda` opcional — ausente/nulo = "BRL"; presente, preservado como veio. */
    private static String moedaDespesa(JsonNode despesa) {
        JsonNode node = despesa.get("moeda");
        if (node == null || node.isNull()) {
            return MOEDA_PADRAO;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IngestaoException("Campo 'moeda' deve ser texto nao vazio: '" + node + "'");
        }
        return node.asText();
    }

    /** Valor monetario do JSON (numero ou texto) -> Dinheiro com HALF_UP 2 casas (RN-010, DT-001). */
    private static Dinheiro dinheiro(JsonNode pai, String campo, String contexto) {
        JsonNode node = obrigatorio(pai, campo, contexto);
        if (!node.isNumber() && !node.isTextual()) {
            throw new IngestaoException("Campo '" + campo + "' deve ser numero" + sufixo(contexto) + ": '" + node + "'");
        }
        try {
            return Dinheiro.de(node.asText());
        } catch (NumberFormatException e) {
            throw new IngestaoException("Campo '" + campo + "' nao e valor monetario" + sufixo(contexto) + ": '" + node.asText() + "'");
        }
    }

    /** Numero bruto do JSON (percentual): BigDecimal exato, sem arredondar. */
    private static BigDecimal numero(JsonNode pai, String campo, String contexto) {
        JsonNode node = obrigatorio(pai, campo, contexto);
        if (!node.isNumber() && !node.isTextual()) {
            throw new IngestaoException("Campo '" + campo + "' deve ser numero" + sufixo(contexto) + ": '" + node + "'");
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw new IngestaoException("Campo '" + campo + "' nao e numero" + sufixo(contexto) + ": '" + node.asText() + "'");
        }
    }

    /** Taxa de cambio: BigDecimal exato — nao e dinheiro; RN-013 arredonda o valor convertido, nao a taxa. */
    private static BigDecimal taxa(JsonNode node, String contexto) {
        if (!node.isNumber() && !node.isTextual()) {
            throw new IngestaoException("Taxa deve ser numero" + sufixo(contexto) + ": '" + node + "'");
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw new IngestaoException("Taxa nao e numero" + sufixo(contexto) + ": '" + node.asText() + "'");
        }
    }

    /** Erro de leitura/validacao com mensagem legivel para o operador da CLI. */
    public static final class IngestaoException extends RuntimeException {
        public IngestaoException(String message) {
            super(message);
        }
    }
}
