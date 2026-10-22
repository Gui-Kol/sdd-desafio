# CLAUDE.md

> Lido pelo agente no início de toda sessão. Moram aqui as convenções que não se
> repetem em todo prompt.

## O projeto

Motor de cálculo de reembolso de despesas corporativas. CLI Java 17 que lê um
JSON de despesas e emite um JSON com o valor reembolsável e a justificativa de
cada item.

## Fonte da verdade (pactos do projeto)

- `specs/001-motor-reembolso/spec.md` define **o quê** o sistema faz.
- `specs/001-motor-reembolso/plan.md` define **como**.
- `specs/001-motor-reembolso/tasks.md` define **em que ordem**.
- `specs/001-motor-reembolso/DECISIONS.md` registra toda mudança de spec.

Quando código e spec discordarem, a spec está certa e o código é o bug — a menos
que a spec esteja errada; nesse caso corrige-se a spec primeiro e registra-se em
`DECISIONS.md`.

**Antes de implementar qualquer coisa, leia a task correspondente em `tasks.md`.**
Se o pedido não está coberto por nenhuma task, pare e avise em vez de implementar.

## Regras de trabalho

- **Toda regra de negócio vive na spec** — não no chat, não em comentário de
  código. Se uma regra for explicada fora da spec, isso é bug de spec: pare e
  registre antes de escrever código.
- **Todo commit referencia uma task**: `feat(T-003): <descrição>` ou
  `test(T-003): <descrição>`. Documentação: `docs(spec):`, `docs(plan):`,
  `docs(tasks):`.
- **Nenhuma regra de negócio entra sem teste.** Nomes de teste carregam o ID da
  regra: `rn006_...`, `amb013_...`.
- Mudança de código que alterar estrutura/estilo/convenções atualiza este arquivo.

## Stack e comandos

- Linguagem: Java 17, Maven, JUnit 5, Jackson
- Testes: `mvn test`
- Rodar: `reembolso.cmd calcular --input X --politica exemplos/politica-v4.json --output Y [--cambio exemplos/cambio.json]` (Windows) ou
  `sh reembolso.sh calcular ...`; alternativa: `mvn -q exec:java -Dexec.args="calcular --input X --politica P --output Y"`

## Convenções de código

- Pacotes por fronteira: `dominio`, `io`, `motor`, `materializacao`, `cli` —
  regra de negócio só no `motor`; I/O cruza por records imutáveis.
- **Valores monetários:** `Dinheiro` (BigDecimal, scale 2, HALF_UP, DT-001).
  Entrada cruza por `Dinheiro.de(String)` — o parser JSON entrega o valor como
  string bruta; saída por `Dinheiro.saida()`. Nunca `double`.
- Mensagens de erro e motivos em português, legíveis para o operador da CLI.
- Código de saída da CLI: 0 = sucesso; 1 = erro de processamento; 2 = erro de uso.

## Fora de escopo

- Nenhuma persistência, UI, HTTP ou framework web.
- Nenhum parser de CLI de terceiros (DT-005: args à mão).
- Nenhum arredondamento divergente de HALF_UP/2 casas em qualquer lugar.
