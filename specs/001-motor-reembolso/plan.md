# Plano Técnico — Motor de Cálculo de Reembolso

**Versão:** 1.0 · **Baseado na spec:** 1.1

> Aqui mora o COMO. Este arquivo pode e deve falar de linguagem, biblioteca e
> arquitetura. O que ele **não** pode é introduzir regra de negócio nova — se
> apareceu uma, ela pertence à `spec.md`.

---

## 1. Stack

| Escolha | O quê | Por quê | O que descartei e por quê |
|---|---|---|---|
| Linguagem | **Java 17** | `BigDecimal` nativo e tipagem forte para regras com estado; o avaliador escolheu | Python/Node indisponíveis nesta máquina; Go exigiria lib externa para decimal |
| Testes | **JUnit 5** | padrão do ecossistema; asserts fluentes legíveis para a correção | TestNG — sem ganho real para este tamanho |
| Build/dep | **Maven** | padrão, wrapper `mvnw` evita instalação global | Gradle — DSL desnecessária aqui |
| Parsing/validação | **Jackson** (`jackson-databind`) | maduro, desserializa direto nos records de entrada; validação manual e explícita por campo | Gson — menos ergonomia com records |
| Aritmética monetária | **`BigDecimal` com `scale=2` e `RoundingMode.HALF_UP`**, encapsulado numa fachada de domínio (`Dinheiro`) | exigência RN-010 (2 casas, metade para cima); `double` acumularia erro binário (33,333 é a armadilha plantada no exemplo) | `double` (descartado: 0.1+0.2≠0.3); lib externa de money (descartada: overkill para uma fachada de 40 linhas) |
| CLI | **args simples** (`main(String[])`) parseado à mão: `calcular --input X --output Y` | a assinatura do enunciado é fixa e tem exatos 2 flags; um parser de CLI inteiro é solução procurando problema | picocli — descartado por ser dependência sem necessidade |

> A linha de aritmética monetária não é decoração. Ponto flutuante em dinheiro é
> a fonte de bug mais previsível deste projeto — daí `Dinheiro` como tipo de
> domínio único, com toda conversa vindo dele.

## 2. Arquitetura

Pipeline linear, um passo por regra de negócio, na ordem da spec §8:

```
entrada JSON
   → [Ingestão]      ler/validar JSON → Entrada(record)
   → [Normalização]  categorias case/trim, Dinheiro nas entradas     (RN-001, RN-010)
   → [Motor]         pipeline de regras por despesa, em ordem:       (RN-002…RN-005)
                     categoria → período → duplicata → estorno → nota
   → [Agregação]     por categoria+dia, corta excedente em ordem      (RN-006, RN-007, RN-008, RN-009)
   → [Materialização] motivos, arredonda saída                        (RN-010, RN-011)
   → saída JSON
```

**Fronteiras:** pacotes `io` (ler arquivo, Jackson, escrever arquivo) e `cli` (args, códigos de saída) carregam todo o I/O; `dominio` e `motor` são puros (sem arquivo, sem stdout, sem `System`). Regra nova mexe em `motor`; novo formato de saída mexe em `materializacao`; nada quebra os dois ao mesmo tempo.

## 3. Modelo de dados

**Entrada (records imutáveis, desserializados por Jackson):**

```
Entrada(colaborador, periodo, List<DespesaIn>)
DespesaIn(id, data, categoria, descricao, fornecedor, valor: String raw, tem_nota_fiscal)
Periodo(competencia, inicio, fim, em_viagem: Boolean nullable → false)
```

> `valor` entra como string e vira `Dinheiro` na normalização — impede que o
> JSON parser faça arredondamento silencioso.

**Domínio (o que o motor manipula):**

```
Dinheiro(valor BigDecimal scale=2)         — adicionar, subtrair, comparar; singleton ZERO
Categoria(enum ALIMENTACAO/TRANSPORTE_URBANO/HOSPEDAGEM) — normaliza(String) → Optional
Despesa(id, data, categoria?, valor: Dinheiro, temNota, bruto: DespesaIn)
Decisao(despesa, status enum APROVADA/PARCIAL/RECUSADA, valorReembolsado: Dinheiro, List<String> motivos)
```

**Saída (records serializados):**

```
Saida(colaborador_id, competencia, total_solicitado, total_reembolsado, List<ResultadoDespesa>)
ResultadoDespesa(id, status, valor_solicitado, valor_reembolsado, motivos)
```

`Decisao` é o coração da RN-011: nenhum status nasce sem motivo — o construtor exige `motivos` não vazio.

## 4. Como a política é representada

**Objeto-imutável `Politica`** passado ao motor — não constantes espalhadas:

```
Politica(
  limiteAlimentacao=60.00, limiteTransporte=80.00, limiteHospedagem=250.00,
  gatilhoNota=100.00, fatorViagem=1.5        
)
Politica.padrao()  // a v3 do RH
```

Toda leitura de limite passa por `Politica.limite(categoria, emViagem)` — um único ponto onde o fator 1,5 incide (RN-008) e onde **não** incide sobre o gatilho de nota (AMB-005). Custo de mudança de política = editar este record, não caçar números no motor.

## 5. Decisões técnicas

### DT-001 — Dinheiro centesimal com HALF_UP na fronteira

**Contexto:** entrada tem 72.50, 100.01, 33.333 — casos de fronteira e sub-centavo.
**Decisão:** todo valor cruza a fronteira do domínio via `Dinheiro.de(String)`: `new BigDecimal(s).setScale(2, HALF_UP)`. Toda saída chama `Dinheiro.saida()`; o motor nunca vê `double`.
**Alternativa descartada:** arredondar só na saída — comparações de limite com 33,333 brutos dariam corte diferente do exibido (inconsistência RN-010).
**Consequência:** fácil testar; impossível vazar float no motor. Difícil: todo novo campo monetário precisa lembrar do tipo — mitigado porque a saída é um record tipado.

### DT-002 — Duplicata caça o primeiro elegível, não o primeiro da lista

**Contexto:** AMB-012/RN-004 — d-007 só é duplicata se d-006 existir como elegível.
**Decisão:** o motor mantém um `Set<ChaveDespesa>` das elegíveis até agora; a duplicata consulta esse conjunto depois das recusas anteriores (passo 3 da §8 da spec — duplicata testa antes do estorno/nota, mas contra os itens vivos).
**Alternativa descartada:** comparar contra todas as entradas — uma d-006 recusada por período marcaria d-007 como duplicata injusta.
**Consequência:** ordem do pipeline da spec §8 é literalmente a ordem do código — fácil de auditar; a chave é um record com data+categoria+fornecedor+valor.

### DT-003 — Cota diária consumida em ordem de entrada, dois limites por família

**Contexto:** RN-006 precisa cortar excedente dando preferência à primeira despesa do dia (AMB-001); hospedagem é por lançamento, não por dia (RN-007).
**Decisão:** agregador mantém `Map<(data,categoria), Dinheiro consumido>` para alimentação/transporte; hospedagem pula o agregador e aplica o limite direto no item. O corte é `min(valor, limite − consumido)`, nunca negativo.
**Alternativa descartada:** rateio proporcional do excedente entre as despesas do dia — esconde a decisão e quebra a leitura "primeira consome primeiro" (§7 da spec o exige: d-001 volta 60,00 e d-002 volta 0,00).
**Consequência:** simples, determinístico, alinhado à ordem de entrada. Difícil: se o exemplo tivesse despesas fora de ordem cronológica, "primeira" continuaria sendo "primeira no arquivo" — decisão registrada na spec.

### DT-004 — Estorno como passo do pipeline, não como categoria especial

**Contexto:** RN-009 — estorno abate a soma elegível da categoria/dia.
**Decisão:** estornos são despachados no passo 4 do pipeline: entram numa lista de pendências; a agregação soma elegíveis **e** estornos do dia/categoria antes de aplicar o limite; o item estorno sai `recusada` com seu próprio motivo.
**Alternativa descartada:** "zerar" o estorno no próprio item sem abater — permitiria que a corrida original fosse reembolsada integral e o cancelamento ignorado.
**Consequência:** o resultado reflete a realidade financeira; aceita que estorno e despesa original não precisam casar (fora de escopo da política).

### DT-005 — CLI mínima feita à mão

**Contexto:** a interface é fixa: `calcular --input X --output Y`.
**Decisão:** parser de 20 linhas no pacote `cli`; erro de uso imprime ajuda e sai com código 2. Lembrete: o enunciado pede `<seu-comando> calcular ...` — o comando do projeto será `mvn -q exec:java -Dexec.args="calcular --input ... --output ..."` e um wrapper `reembolso.cmd`/`.sh` gerado no build.
**Alternativa descartada:** picocli (ver tabela de stack).
**Consequência:** zero dependência de arg-parsing; se surgir subcomando novo, a gente reavalia.

> **Layout de testes:** os testes moram em `tests/` (espelhando pacotes), não em `src/test/java` — exigência da estrutura de entrega do enunciado; o `pom.xml` aponta `<testSourceDirectory>tests</testSourceDirectory>`.

## 6. Estratégia de testes

- **Nível:** 80% unitário (motor + regras, puros), 15% integração (pipeline completo com JSON em memória), 5% ponta a ponta (CLI real com `exemplos/despesas-exemplo.json`, comparando totais).
- **Cada `RN-NNN` da spec tem teste?** Sim — convenção de nomenclatura força isso (abaixo); a matriz de cobertura em `tasks.md` cruza RN × task × teste.
- **Casos de borda da seção 7 da spec:** cada linha da tabela vira um teste de motor com nome espelhando o RN/AMB correspondente.
- **Nomenclatura:** `MotorReembolsoTest.rn005_fronteiraCemReaisSemNota_naoExige()` — o nome começa com o RN, segue com o caso. Correção vê rastreabilidade no relatório de testes sem abrir o código.

## 7. Riscos

| Risco | Probabilidade | O que faço se acontecer |
|---|---|---|
| JDK não instalável na máquina do desafio | média | fallback: Python-only sem mypy; plan.md v1.1 documenta a troca e DECISIONS.md ganha entrada |
| Ordem de `BigDecimal` na última casa divergir da SUT do avaliador | baixa | RN-010 amarra HALF_UP; o teste do 33,333 é a prova de fumaça |
| d-013 (Airbnb 690 sem nota) matar por nota antes de discutir limite de diária — e o avaliador esperar o contrário | média | spec §8 declara ordem nota → limite; teste explícito documenta o resultado escolhido |
| `periodo.em_viagem` ausente vs `false` | baixa | Jackson default `false` + teste; AMB-005 já prevê |
| Duplicata de valor com 33,33 vs 33,333 | baixa | chave usa `Dinheiro` já arredondado — 33,333 e 33,33 **são** a mesma despesa para RN-004 |
