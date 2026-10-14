# Motor de Cálculo de Reembolso

CLI em **Java 17** que automatiza o cálculo de reembolso de despesas
corporativas. Dado um JSON com as despesas de um colaborador num período de
competência, o motor decide — despesa a despesa — quanto é reembolsável, com
justificativa legível para cada decisão, e consolida os totais do período.

Fonte da verdade das regras: [`specs/001-motor-reembolso/spec.md`](specs/001-motor-reembolso/spec.md).

---

## Visão geral

O processo de reembolso hoje é manual: alguém do financeiro confere item a
item contra a política do RH. Este projeto substitui esse processo por um
motor determinístico e auditável:

```
despesas.json  →  [motor de reembolso]  →  resultado.json
```

Cada despesa sai com um `status` (`aprovada`, `parcial`, `recusada`), o
`valor_reembolsado` e a lista de `motivos` — nenhuma decisão sai sem
justificativa (RN-011).

### Regras de negócio tratadas

A política original do RH tinha ao menos oito ambiguidades reais. Todas foram
resolvidas e registradas na spec (AMB-001..AMB-013) e implementadas como
regras RN-001..RN-011:

| Regra | O que faz |
|---|---|
| RN-001 | Normalização de categoria (case-insensitive, trim); catálogo: `alimentacao`, `transporte_urbano`, `hospedagem` |
| RN-002 | Categoria fora da política → recusa integral |
| RN-003 | Despesa fora do período de competência (`inicio`/`fim` inclusive) → recusa |
| RN-004 | Duplicatas (mesma data + categoria + fornecedor + valor) → recusa da 2ª em diante, citando a original |
| RN-005 | Nota fiscal obrigatória para despesa individual **estritamente** acima de R$ 100,00 |
| RN-006 | Limite **diário** por categoria (alimentação R$ 60; transporte R$ 80), aplicado à soma elegível do dia; excedente é cortado em ordem de entrada |
| RN-007 | Hospedagem: limite de R$ 250 **por lançamento**, sem agregação diária |
| RN-008 | `periodo.em_viagem: true` amplia os limites em 50% (o gatilho de nota **não** amplia) |
| RN-009 | Estornos (valor negativo) abatem a soma diária da categoria; nunca viram crédito |
| RN-010 | Dinheiro com 2 casas decimais, arredondamento metade-para-cima (HALF_UP) |
| RN-011 | Toda decisão carrega motivo legível |

---

## Pré-requisitos

- **JDK 17** ou superior — verifique com `java -version`
- **Maven 3.8+** — verifique com `mvn -version`

Nenhuma outra instalação é necessária: as dependências (JUnit 5 e Jackson)
são baixadas pelo Maven na primeira compilação.

---

## Como compilar

```bash
mvn clean compile
```

Para gerar também o classpath cacheado (usado pelos wrappers):

```bash
mvn -q compile -DskipTests
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
```

## Como executar

A interface é fixa: `calcular --input <arquivo> --output <arquivo>`.

**Windows (wrapper recomendado):**

```cmd
reembolso.cmd calcular --input exemplos/despesas-exemplo.json --output saida.json
```

**Linux / macOS:**

```bash
sh reembolso.sh calcular --input exemplos/despesas-exemplo.json --output saida.json
```

Os wrappers compilam o projeto na primeira execução (`mvn compile` +
classpath em `target/cp.txt`) e depois rodam `java` direto, propagando os
códigos de saída corretamente.

**Alternativa via Maven (sem wrapper):**

```bash
mvn -q compile exec:java -Dexec.args="calcular --input exemplos/despesas-exemplo.json --output saida.json"
```

### Códigos de saída

| Código | Significado |
|---|---|
| `0` | Sucesso — arquivo de saída escrito |
| `1` | Erro de processamento (arquivo ilegível, JSON malformado) — mensagem no stderr |
| `2` | Erro de uso — imprime a ajuda no stderr |

### Exemplo de saída

Para o arquivo de exemplo, o motor produz (trecho de `saida.json`):

```json
{
  "colaborador_id": "c-0417",
  "competencia": "2026-07",
  "total_solicitado": 1816.84,
  "total_reembolsado": 585.43,
  "despesas": [
    { "id": "d-004", "status": "recusada", "valor_solicitado": 100.01,
      "valor_reembolsado": 0.00,
      "motivos": ["Despesa acima de R$ 100,00 sem nota fiscal"] }
  ]
}
```

---

## Como testar

Rodar a suíte completa de testes automatizados:

```bash
mvn test
```

Isso compila o projeto e executa todas as camadas da suíte JUnit 5 (em
`tests/`): testes unitários do motor e dos tipos de domínio (ex.:
`MotorReembolsoTest`, `DinheiroTest`, `PoliticaTest`), testes de integração do
JSON (`SaidaTest`), e os ponta a ponta da CLI (`CliE2ETest`) — incluindo o
E2E dourado (`ExemploE2ETest`) que processa o arquivo exemplo e confere o
status e o valor de cada uma das 14 despesas, com `total_reembolsado =
585,43`. Ao final você deve ver **BUILD SUCCESS**.

---

## Formato de entrada

Espelha [`exemplos/despesas-exemplo.json`](exemplos/despesas-exemplo.json):

| Campo | Tipo | Obrigatório |
|---|---|---|
| `colaborador.id` / `nome` / `centro_custo` | texto | sim |
| `periodo.competencia` | texto `AAAA-MM` | sim |
| `periodo.inicio` / `periodo.fim` | data `AAAA-MM-DD` | sim |
| `periodo.em_viagem` | booleano | não — ausente = `false` |
| `despesas[].id`, `data`, `categoria`, `descricao`, `fornecedor` | texto | sim |
| `despesas[].valor` | número (pode ser negativo = estorno) | sim |
| `despesas[].tem_nota_fiscal` | booleano | sim |

## Estrutura do código

```
src/main/java/com/reembolso/
├── dominio/        # Dinheiro (BigDecimal scale 2, HALF_UP), Categoria
├── io/             # Ingestao (JSON -> Entrada), EscritorSaida (Saida -> JSON)
├── motor/          # MotorReembolso (pipeline na ordem da spec §8), Politica
├── materializacao/ # Saida: modelo de saída e consolidação dos totais
└── cli/            # Main: calcular --input X --output Y (parser à mão)
tests/              # suíte JUnit 5 espelhando os pacotes de src
```

## Documentação do processo (SDD)

Este projeto foi desenvolvido com Spec Driven Development. A trilha completa
está em `specs/001-motor-reembolso/` e `docs/`:

- [`spec.md`](specs/001-motor-reembolso/spec.md) — o quê e o porquê (RN-001..RN-011, AMB-001..AMB-013)
- [`plan.md`](specs/001-motor-reembolso/plan.md) — o como (stack, arquitetura, DT-001..DT-005)
- [`tasks.md`](specs/001-motor-reembolso/tasks.md) — implementação T-001..T-017 e matriz de cobertura
- [`DECISIONS.md`](specs/001-motor-reembolso/DECISIONS.md) — log de decisões e mudanças de spec
- [`docs/sessions/`](docs/sessions/) — sessões exportadas do agente
- [`docs/RELATORIO.md`](docs/RELATORIO.md) — relatório final
