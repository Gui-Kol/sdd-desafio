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
resolvidas e registradas na spec (AMB-001..AMB-013 no Dia 1; o envelope do
Dia 2 trouxe AMB-014..AMB-022 — ver `DECISIONS.md`, D-003) e implementadas
como regras RN-001..RN-013:

| Regra | O que faz |
|---|---|
| RN-001 | Normalização de categoria (case-insensitive, trim); o catálogo é dado da política (RN-012) |
| RN-002 | Categoria sem limite resolvido para o centro de custo → recusa integral |
| RN-003 | Despesa fora do período de competência (`inicio`/`fim` inclusive) → recusa |
| RN-004 | Duplicatas (mesma data + categoria + fornecedor + valor em BRL) → recusa da 2ª em diante, citando a original |
| RN-005 | Nota fiscal obrigatória para despesa individual **estritamente** acima do gatilho da política (R$ 100,00 na v4), sobre o valor em BRL |
| RN-006 | Limite **diário** por categoria, resolvido por centro de custo, aplicado à soma elegível do dia; excedente cortado em ordem de entrada |
| RN-007 | Hospedagem (periodicidade `diaria`): limite **por lançamento**, sem agregação diária |
| RN-008 | `periodo.em_viagem: true` amplia os limites pelo percentual da política (o gatilho de nota **não** amplia) |
| RN-009 | Estornos (valor negativo em BRL) abatem a soma diária da categoria; nunca viram crédito |
| RN-010 | Dinheiro com 2 casas decimais, arredondamento metade-para-cima (HALF_UP) |
| RN-011 | Toda decisão carrega motivo legível |
| RN-012 | Política v4 como dado: limites por centro de custo com herança do bloco `padrao`; vigência é pré-condição do lote; limite 0,00 = categoria não reembolsável |
| RN-013 | Moeda e câmbio: `moeda` ausente = BRL; conversão pela taxa da data da despesa (última publicada se não houver); sem cotação → recusa por item, o lote segue |

---

## Instalação

1. Clone o repositório e entre na pasta do projeto.
2. Instale o **JDK 17** ou superior — verifique com `java -version` (deve
   reportar 17 ou mais).
3. Instale o **Maven** 3.8+ — verifique com `mvn -version`.
4. Nenhum outro passo: as dependências (JUnit 5 e Jackson) são baixadas pelo
   Maven automaticamente na primeira execução de qualquer comando `mvn`
   (compilação ou teste).

Sanidade da instalação (compila sem testes):

```bash
mvn clean compile
```

Para gerar também o classpath cacheado (usado pelos wrappers da CLI):

```bash
mvn -q compile -DskipTests
mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
```

## Como executar

A interface é fixa: `calcular --input <despesas> --politica <politica> --output
<resultado> [--cambio <cambio>]` — a política v4 é obrigatória (spec §4) e o
câmbio é opcional (sem ele, despesa em moeda estrangeira é recusada como
"Moeda sem cotação disponível", RN-013).

**Windows (wrapper recomendado):**

```cmd
reembolso.cmd calcular --input exemplos/despesas-envelope.json --politica exemplos/politica-v4.json --cambio exemplos/cambio.json --output resultado.json
```

**Linux / macOS:**

```bash
sh reembolso.sh calcular --input exemplos/despesas-envelope.json --politica exemplos/politica-v4.json --cambio exemplos/cambio.json --output resultado.json
```

O exemplo do Dia 1 também roda (é todo BRL, sem necessidade de `--cambio`):

```bash
sh reembolso.sh calcular --input exemplos/despesas-exemplo.json --politica exemplos/politica-v4.json --output saida.json
```

Os wrappers compilam o projeto na primeira execução (`mvn compile` +
classpath em `target/cp.txt`) e depois rodam `java` direto, propagando os
códigos de saída corretamente.

**Alternativa via Maven (sem wrapper):**

```bash
mvn -q compile exec:java -Dexec.args="calcular --input exemplos/despesas-envelope.json --politica exemplos/politica-v4.json --cambio exemplos/cambio.json --output resultado.json"
```

### Códigos de saída

| Código | Significado |
|---|---|
| `0` | Sucesso — arquivo de saída escrito |
| `1` | Erro de processamento (arquivo ilegível, JSON malformado, vigência da política, periodicidade inválida) — mensagem no stderr |
| `2` | Erro de uso — imprime a ajuda no stderr |

### Exemplo de saída

Para o envelope do Dia 2, o motor produz (trecho de `resultado.json`; a
despesa cai num sábado sem cotação e usa a última taxa publicada, RN-013):

```json
{
  "colaborador_id": "c-0912",
  "competencia": "2026-07",
  "total_solicitado": 2457.52,
  "total_reembolsado": 1143.26,
  "despesas": [
    { "id": "e-004", "status": "parcial", "moeda": "EUR",
      "valor_original": 30.00, "valor_solicitado": 178.80,
      "valor_reembolsado": 90.00, "taxa_cambio": 5.96, "data_taxa": "2026-07-17",
      "motivos": ["Excedente cortado ao limite diário da categoria"] }
  ]
}
```

---

## Como testar

O comando exato para rodar a suíte completa de testes automatizados, na raiz
do projeto:

```bash
mvn test
```

Ele compila o projeto e executa todas as camadas da suíte JUnit 5 (em
`tests/`): testes unitários do motor e dos tipos de domínio (ex.:
`MotorReembolsoTest`, `DinheiroTest`, `PoliticaTest`), testes de integração
do JSON (`IngestaoTest`, `SaidaTest`) e os ponta a ponta da CLI
(`CliE2ETest`) — incluindo o E2E dourado do Dia 1 (`ExemploE2ETest`,
`total_reembolsado = 585,43` sob a política v3 como dado) e o E2E dourado
dos envelopes do Dia 2 (`EnvelopeE2ETest`, com a política v4 e o câmbio
reais).

Resultado esperado no fechamento do desafio — verifique estas duas linhas:

```
Tests run: 122, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Se qualquer número for outro, a suíte está quebrada: nenhum commit deve sair
com `mvn test` vermelho.

---

## Formato de entrada

Espelha [`exemplos/despesas-envelope.json`](exemplos/despesas-envelope.json)
(o do Dia 1 é [`exemplos/despesas-exemplo.json`](exemplos/despesas-exemplo.json)):

| Campo | Tipo | Obrigatório |
|---|---|---|
| `colaborador.id` / `nome` / `centro_custo` | texto | sim |
| `periodo.competencia` | texto `AAAA-MM` | sim |
| `periodo.inicio` / `periodo.fim` | data `AAAA-MM-DD` | sim |
| `periodo.em_viagem` | booleano | não — ausente = `false` |
| `despesas[].id`, `data`, `categoria`, `descricao`, `fornecedor` | texto | sim |
| `despesas[].valor` | número (pode ser negativo = estorno) | sim |
| `despesas[].moeda` | texto (`BRL`, `EUR`, ...) | não — ausente = `BRL` |
| `despesas[].tem_nota_fiscal` | booleano | sim |

A política v4 ([`exemplos/politica-v4.json`](exemplos/politica-v4.json)) e o
câmbio ([`exemplos/cambio.json`](exemplos/cambio.json)) seguem a spec §4:
limites por centro de custo com herança do bloco `padrao`, gatilho de nota,
acréscimo de viagem, vigência; e taxas PTAX por dia útil.

## Estrutura do código

```
src/main/java/com/reembolso/
├── dominio/        # Dinheiro (BigDecimal scale 2, HALF_UP), Categoria
├── io/             # Ingestao (despesas/politica/cambio -> records), EscritorSaida
├── motor/          # MotorReembolso (pipeline da spec §8), Politica (RN-012)
├── materializacao/ # Saida: modelo de saída multi-moeda e consolidação dos totais em BRL
└── cli/            # Main: calcular --input --politica [--cambio] --output (parser à mão)
tests/              # suíte JUnit 5 espelhando os pacotes de src
```

## Documentação do processo (SDD)

Este projeto foi desenvolvido com Spec Driven Development. A trilha completa
está em `specs/001-motor-reembolso/` e `docs/`:

- [`spec.md`](specs/001-motor-reembolso/spec.md) — o quê e o porquê (RN-001..RN-013, AMB-001..AMB-022)
- [`plan.md`](specs/001-motor-reembolso/plan.md) — o como (stack, arquitetura, DT-001..DT-005)
- [`tasks.md`](specs/001-motor-reembolso/tasks.md) — implementação T-001..T-022 e matriz de cobertura
- [`DECISIONS.md`](specs/001-motor-reembolso/DECISIONS.md) — log de decisões e mudanças de spec (D-001..D-003)
- [`docs/sessions/`](docs/sessions/) — sessões exportadas do agente
- [`docs/RELATORIO.md`](docs/RELATORIO.md) — relatório final
