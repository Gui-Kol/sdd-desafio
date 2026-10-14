# Motor de Cálculo de Reembolso

CLI em Java 17 que lê um JSON de despesas corporativas e calcula o valor
reembolsável de cada item, com justificativa legível por despesa e totais do
período — conforme a política do RH especificada em
[`specs/001-motor-reembolso/spec.md`](specs/001-motor-reembolso/spec.md).

## Pré-requisitos

- **JDK 17** (`java -version` deve mostrar 17 ou superior)
- **Maven 3.8+** (`mvn -version`)

Nenhuma outra instalação é necessária: as dependências (JUnit 5, Jackson) são
baixadas pelo Maven na primeira execução.

## Como testar

```bash
mvn test
```

Isso compila o projeto e roda toda a suíte (testes unitários do motor e dos
tipos de domínio, testes de integração do JSON de saída e testes ponta a ponta
da CLI, incluindo o E2E dourado com os totais do exemplo). Ao final você deve
ver **BUILD SUCCESS** com todos os testes verdes.

## Como rodar

O comando fixo do projeto é o wrapper `reembolso`:

```bash
# Windows
reembolso.cmd calcular --input exemplos/despesas-exemplo.json --output saida.json

# Linux / macOS
sh reembolso.sh calcular --input exemplos/despesas-exemplo.json --output saida.json
```

Na primeira execução o wrapper compila o projeto e cacheia o classpath; depois
roda o Java direto. O resultado é escrito em `saida.json` no formato da spec §4
(`colaborador_id`, `competencia`, `total_solicitado`, `total_reembolsado` e
`despesas[]` com `id`, `status`, `valor_solicitado`, `valor_reembolsado`,
`motivos`).

Alternativa via Maven (sem wrapper):

```bash
mvn -q exec:java -Dexec.args="calcular --input exemplos/despesas-exemplo.json --output saida.json"
```

### Códigos de saída

| Código | Significado |
|---|---|
| 0 | Sucesso — arquivo de saída escrito |
| 1 | Erro de processamento (arquivo ilegível, JSON malformado) — mensagem no stderr |
| 2 | Erro de uso — imprime a ajuda no stderr |

## Formato de entrada

Espelha [`exemplos/despesas-exemplo.json`](exemplos/despesas-exemplo.json):
`colaborador`, `periodo` (com `competencia`, `inicio`, `fim` e `em_viagem`
opcional — ausente = `false`) e a lista `despesas[]` com `id`, `data`,
`categoria`, `descricao`, `fornecedor`, `valor` e `tem_nota_fiscal`.

## Estrutura do código

```
src/main/java/com/reembolso/
├── dominio/        # Dinheiro (BigDecimal scale 2, HALF_UP), Categoria
├── io/             # Ingestao (JSON -> Entrada), EscritorSaida (Saida -> JSON)
├── motor/          # MotorReembolso (pipeline da spec §8), Politica (RN-008)
├── materializacao/ # Saida: modelo de saída e consolidação dos totais
└── cli/            # Main: calcular --input X --output Y (parser à mão, DT-005)
tests/              # suíte JUnit 5 espelhando os pacotes de src
```

## Regras de negócio

Todas as regras (RN-001 a RN-011) e ambiguidades resolvidas (AMB-001 a AMB-013)
estão especificadas com critério de aceite em `specs/001-motor-reembolso/`:

- [`spec.md`](specs/001-motor-reembolso/spec.md) — o quê e o porquê
- [`plan.md`](specs/001-motor-reembolso/plan.md) — o como (decisões técnicas DT-001..)
- [`tasks.md`](specs/001-motor-reembolso/tasks.md) — ordem de implementação e rastreabilidade
- [`DECISIONS.md`](specs/001-motor-reembolso/DECISIONS.md) — log de mudanças de spec
