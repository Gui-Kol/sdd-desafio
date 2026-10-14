# Spec — Motor de Cálculo de Reembolso

**Versão:** 1.1 · **Status:** aprovada · **Última alteração:** 2026-10-14

> **Regra de ouro deste arquivo:** ele descreve o QUÊ e o PORQUÊ. Nenhuma linha
> aqui pode citar linguagem, biblioteca, classe, função ou estrutura de pasta.
> Se apareceu solução, o lugar dela é o `plan.md`.
>
> **Teste de aceitação da própria spec:** uma pessoa que nunca viu o projeto
> consegue, lendo só este arquivo, verificar se o sistema está correto?

---

## 1. Problema

O reembolso de despesas dos colaboradores hoje é conferido manualmente pelo
financeiro, item a item, contra uma política escrita de forma ambígua. O
processo é lento, inconsistente entre conferentes e sem justificativa
rastreável para cada aprovação ou recusa.

## 2. Objetivo

Dado um arquivo com as despesas de um colaborador num período de competência,
produzir um arquivo com a decisão de reembolso de cada despesa — valor
reembolsado e justificativa legível — mais os totais consolidados, sem
intervenção humana.

## 3. Fora de escopo

- Não lê planilhas, PDFs de nota fiscal ou e-mails — apenas o formato de entrada definido na seção 4.
- Não persiste histórico nem compara com períodos anteriores — cada cálculo é independente.
- Não autentica usuário, não tem interface gráfica nem relatórios gerenciais.
- Não processa pagamento nem integra com folha/contábil — apenas calcula e justifica.
- Não verifica autenticidade de nota fiscal — confia no campo `tem_nota_fiscal`.
- Não converte moedas — todos os valores estão em reais.

## 4. Entrada e saída

**Entrada:** conforme `exemplos/despesas-exemplo.json`. Campos e significado:

| Campo | Tipo | Significado | Obrigatório |
|---|---|---|---|
| `colaborador.id` | texto | identificador único do colaborador | sim |
| `colaborador.nome` | texto | nome do colaborador | sim |
| `colaborador.centro_custo` | texto | centro de custo | sim |
| `periodo.competencia` | texto `AAAA-MM` | mês/ano de competência | sim |
| `periodo.inicio` | data `AAAA-MM-DD` | primeiro dia do período (inclusive) | sim |
| `periodo.fim` | data `AAAA-MM-DD` | último dia do período (inclusive) | sim |
| `periodo.em_viagem` | booleano | colaborador esteve em viagem no período | não — ausente = `false` (ver AMB-005) |
| `despesas[].id` | texto | identificador único da despesa | sim |
| `despesas[].data` | data `AAAA-MM-DD` | data da despesa | sim |
| `despesas[].categoria` | texto | categoria, sem distinção de maiúsculas/minúsculas (ver AMB-009) | sim |
| `despesas[].descricao` | texto | descrição livre | sim |
| `despesas[].fornecedor` | texto | fornecedor | sim |
| `despesas[].valor` | número | valor em reais; pode ser negativo (estorno, ver AMB-008) | sim |
| `despesas[].tem_nota_fiscal` | booleano | se há nota fiscal anexada | sim |

**Saída:** um objeto com o resultado de cada despesa e os totais.

| Campo | Tipo | Significado |
|---|---|---|
| `colaborador_id` | texto | id do colaborador |
| `competencia` | texto | competência calculada |
| `total_solicitado` | número (2 casas) | soma de todos os valores informados |
| `total_reembolsado` | número (2 casas) | soma dos valores reembolsados |
| `despesas[].id` | texto | id da despesa |
| `despesas[].status` | texto | `aprovada`, `parcial` ou `recusada` |
| `despesas[].valor_solicitado` | número (2 casas) | valor original |
| `despesas[].valor_reembolsado` | número (2 casas) | valor efetivamente reembolsado |
| `despesas[].motivos` | lista de texto | justificativas legíveis, na ordem em que as regras aplicaram |

**Exemplo** (alimentação com limite de R$ 60/dia, sem viagem):

```json
{"despesas": [{"id": "d-003", "valor": 100.01, "tem_nota_fiscal": false, ...}]}
```

```json
{
  "colaborador_id": "c-0417",
  "competencia": "2026-07",
  "total_solicitado": 100.01,
  "total_reembolsado": 0.00,
  "despesas": [
    {
      "id": "d-003",
      "status": "recusada",
      "valor_solicitado": 100.01,
      "valor_reembolsado": 0.00,
      "motivos": ["Despesa acima de R$ 100,00 sem nota fiscal"]
    }
  ]
}
```

## 5. Regras de negócio

Cada regra recebe um ID (`RN-001`, ...). As tasks vão referenciar esses IDs.

### RN-001 — Normalização e catálogo de categorias

**Regra:** a categoria é comparada sem distinção de maiúsculas/minúsculas e sem espaços nas bordas. As categorias reembolsáveis são exatamente: `alimentacao`, `transporte_urbano`, `hospedagem`. Qualquer outra categoria é recusada integralmente (RN-002).
**Origem:** política do RH, itens 1–3 e 9; AMB-009.
**Aceite:** `"ALIMENTACAO"` é tratada como `alimentacao`; `coworking` gera recusa com motivo "Categoria fora da política".

### RN-002 — Categoria fora da política

**Regra:** despesa de categoria não listada na RN-001 é recusada com reembolso zero.
**Origem:** política do RH, item 9.
**Aceite:** uma despesa `coworking` de R$ 89,00 resulta em `recusada`, R$ 0,00.

### RN-003 — Pertinência ao período de competência

**Regra:** a data da despesa deve estar entre `periodo.inicio` e `periodo.fim`, inclusive. Fora desse intervalo, a despesa é recusada integralmente com motivo "Despesa fora do período de competência".
**Origem:** política do RH, item 7; AMB-007.
**Aceite:** despesa de 2026-04-15 num período 2026-07-01 a 2026-07-31 é recusada.

### RN-004 — Duplicatas

**Regra:** duas ou mais despesas são duplicatas quando coincidem em **data, categoria normalizada, fornecedor e valor** (tolerância de R$ 0,01 no valor). A primeira, na ordem da entrada, permanece elegível; as demais são recusadas integralmente com motivo "Duplicata da despesa d-XXX".
**Origem:** política do RH, item 8; AMB-006.
**Aceite:** d-006 e d-007 (mesma data/fornecedor/valor) → d-006 elegível, d-007 recusada citando d-006.

### RN-005 — Exigência de nota fiscal

**Regra:** toda despesa **individual** com valor estritamente maior que R$ 100,00 exige nota fiscal. Despesa sem nota acima desse valor é recusada integralmente. Despesa de exatamente R$ 100,00 (ou menos) não exige nota.
**Origem:** política do RH, item 5; AMB-003 e AMB-004.
**Aceite:** R$ 100,00 sem nota → elegível; R$ 100,01 sem nota → recusada.

### RN-006 — Limite diário por categoria, com agregação

**Regra:** os limites de `alimentacao` (R$ 60,00) e `transporte_urbano` (R$ 80,00) são **diários** e aplicam-se à **soma dos valores elegíveis das despesas daquela categoria naquele dia** (após RN-002..RN-005). O excedente é cortado: o colaborador recebe o limite, nunca mais. O corte é registrado nas despesas do dia em ordem de entrada, consumindo a cota diária sucessivamente — a preferência vai para a primeira despesa do dia.
**Origem:** política do RH, itens 1, 2 e 4; AMB-001 e AMB-002.
**Aceite:** duas alimentações de R$ 72,50 + R$ 38,00 no mesmo dia → R$ 72,50 reembolsado na primeira e R$ 0,00 na segunda (soma 110,50 > 60), total diário de alimentação = R$ 60,00.

### RN-007 — Limite de hospedagem por lançamento

**Regra:** hospedagem limita-se a R$ 250,00 **por lançamento** (cada lançamento de hospedagem representa uma única fatura de diária na acepção da política). O excedente é cortado. Limites diários RN-006 não se aplicam a hospedagem.
**Origem:** política do RH, itens 3 e 4; AMB-010.
**Aceite:** hospedagem de R$ 480,00 → parcial, R$ 250,00 reembolsados.

### RN-008 — Acréscimo de 50% em viagem

**Regra:** quando `periodo.em_viagem` é `true`, todos os limites (RN-006 e RN-007) são ampliados em 50%: alimentação R$ 90,00/dia, transporte urbano R$ 120,00/dia, hospedagem R$ 375,00/lançamento. O valor que dispara a exigência de nota (R$ 100,00) **não** é ampliado.
**Origem:** política do RH, item 6; AMB-005.
**Aceite:** em viagem, alimentação de R$ 90,00/dia é totalmente reembolsada; sem viagem, o mesmo total seria cortado em R$ 60,00.

### RN-009 — Valores negativos (estornos)

**Regra:** despesa com valor negativo nunca é reembolsável por si só; ela **reduz a soma diária da sua categoria** antes da aplicação do limite. Se a soma diária de uma categoria ficar negativa ou zero, nada é reembolsado por aquela categoria naquele dia (não há devolução ao colaborador). Estorno não exige nota fiscal e não gera categoria própria.
**Origem:** derivada de consistência financeira; AMB-008.
**Aceite:** num dia com estorno de −R$ 45,00 e nenhuma outra despesa na categoria, o reembolso daquela categoria/dia é R$ 0,00.

### RN-010 — Arredondamento monetário

**Regra:** todo valor monetário na saída é arredondado para 2 casas decimais, "metade para cima" (0,5 arredonda para longe de zero), aplicado uma única vez sobre o valor final de cada campo de saída. Valores de entrada com mais de 2 casas são arredondados da mesma forma antes de qualquer cálculo.
**Origem:** não coberto pela política; AMB-011.
**Aceite:** valor informado de 33,333 → 33,33 em todos os campos derivados.

### RN-011 — Justificativa obrigatória e rastreável

**Regra:** toda despesa na saída carrega ao menos um motivo legível: o motivo da recusa, do corte parcial ("excedente cortado ao limite da categoria") ou da aprovação integral. Nenhum valor muda sem motivo correspondente.
**Origem:** objetivo do sistema (seção 2).
**Aceite:** nenhuma despesa na saída com `motivos` vazio.

---

## 6. Ambiguidades identificadas e decisões

> **Esta seção é o coração da spec e vale a maior parte dos 25 pontos do critério 1.**
> Uma ambiguidade que você resolveu no código sem registrar aqui conta como
> não resolvida.

### AMB-001 — Limite "por dia": por dia ou por despesa?

**Texto original do RH:** "Alimentação tem limite de **R$ 60 por dia**."
**O que não está claro:** o limite pode ser por despesa individual ou pela soma do dia. Ex.: d-001 (R$ 72,50) + d-002 (R$ 38,00) no mesmo dia: corta só a primeira? Soma 110,50 e corta em 60?
**Decisão:** o limite é **diário sobre a soma das despesas elegíveis da categoria naquele dia**; a cota é consumida em ordem de entrada (RN-006).
**Justificativa:** "por dia" descreve um período, não um evento — a leitura agregada é a única que impede burla trivial lançando várias despesas de R$ 59 no mesmo dia.
**Regra afetada:** RN-006.

### AMB-002 — "Reembolsadas parcialmente": corta ou recusa?

**Texto original do RH:** "Despesas **acima do limite** são reembolsadas parcialmente."
**O que não está claro:** paga o limite e corta o excedente, ou recusa o item/dia inteiro?
**Decisão:** **paga até o limite e corta o excedente**; nunca recusa por excesso de valor (RN-006, RN-007).
**Justificativa:** punir o item inteiro incentiva gasto exatamente no teto e ignora a parte legítima da despesa; "parcialmente" só faz sentido se algo do excedente vira rejeição e o resto vira pagamento.
**Regra afetada:** RN-006, RN-007.

### AMB-003 — "Acima de R$ 100": e exatamente R$ 100,00?

**Texto original do RH:** "**Nota fiscal é obrigatória acima de R$ 100.**"
**O que não está claro:** fronteira inclusiva ou exclusiva. d-003 (R$ 100,00 sem nota) vs d-004 (R$ 100,01 sem nota) existem no exemplo justamente para testar a fronteira.
**Decisão:** **estritamente maior** — R$ 100,00 não exige nota; R$ 100,01 exige (RN-005).
**Justificativa:** "acima de" em português exclui o próprio valor; além disso, a leitura conservadora (exigente) aqui não perdoaria fraude, apenas geraria fricção operacional.
**Regra afetada:** RN-005.

### AMB-004 — Nota fiscal: por item ou por total do dia?

**Texto original do RH:** "Nota fiscal é obrigatória acima de R$ 100."
**O que não está claro:** se duas corridas de R$ 60 no mesmo dia somam R$ 120, a exigência dispara?
**Decisão:** **por despesa individual** (RN-005). O total diário nunca dispara exigência de nota.
**Justificativa:** a nota fiscal é um documento de um lançamento, não de um dia; consolidar por dia criaria exigência impossível de cumprir retroativamente (a despesa já foi paga sem saber da soma futura).
**Regra afetada:** RN-005.

### AMB-005 — "Em viagem": o que caracteriza?

**Texto original do RH:** "Colaborador **em viagem** tem limites ampliados em 50%."
**O que não está claro:** não há campo de viagem na entrada; inferir por presença de hospedagem? Categoria aeroporto? O 50% amplia também o gatilho de nota?
**Decisão:** criar o campo opcional `periodo.em_viagem` na entrada; **ausente = `false`**. O acréscimo vale só para os limites de categoria, **não** para o gatilho de nota de R$ 100 (RN-008).
**Justificativa:** sem dado confiável, a inferência inventaria precisão; tornar o dado explícito transfere a responsabilidade da informação para quem lança, e a política não sugere que nota fiscal fique opcional em viagem.
**Regra afetada:** RN-008.

### AMB-006 — "Duplicatas devem ser tratadas": quais são, e como?

**Texto original do RH:** "**Duplicatas devem ser tratadas.**"
**O que não está claro:** o que caracteriza duplicata (id repetido? mesma descrição?) e o que é "tratar" (recusar, alertar, mesclar?). d-006 e d-007 são idênticas exceto pelo id.
**Decisão:** duplicata = mesmo **data + categoria normalizada + fornecedor + valor** (tolerância R$ 0,01). Trata-se **recusando** todas exceto a primeira, com motivo citando o id da original (RN-004).
**Justificativa:** a recusa com citação do id cobre o erro de lançamento duplo sem punir gastos legítimos coincidentes (dois táxis do mesmo valor para o mesmo fornecedor em dias iguais é o único risco — aceito, pois incomum e contestável pelo motivo registrado).
**Regra afetada:** RN-004.

### AMB-007 — "Dentro do período": recusar ou sinalizar?

**Texto original do RH:** "Despesas devem ser lançadas **dentro do período de competência**."
**O que não está claro:** despesa fora do intervalo é recusada, realocada para outra competência ou apenas sinalizada? d-008 é de abril num período de julho.
**Decisão:** **recusar integralmente** com motivo explícito (RN-003). Sem realocação — o motor processa um período por vez.
**Justificativa:** realocar exigiria reprocessar outros períodos e bateria a duplicata entre competências; a recusa documentada deixa ao financeiro a decisão de relançar no período certo.
**Regra afetada:** RN-003.

### AMB-008 — Valor negativo (estorno)

**Texto original do RH:** não coberto.
**O que não está claro:** d-009 é um estorno de −R$ 45,00. Reduz o reembolso? É recusado? Vira devolução?
**Decisão:** o estorno **abate a soma diária da categoria** antes do limite; soma diária negativa ou zero vira R$ 0,00 reembolsado (nunca crédito ao colaborador); o item estorno em si recebe status `recusada` com motivo "Estorno: abatido do total da categoria no dia" (RN-009).
**Justificativa:** estorno não é despesa a pagar; tratar como abatimento reproduz a realidade financeira sem criar fluxo de devolução, que está fora de escopo.
**Regra afetada:** RN-009.

### AMB-009 — Case da categoria ("ALIMENTACAO")

**Texto original do RH:** não coberto.
**O que não está claro:** d-014 usa `ALIMENTACAO` em maiúsculas. É a mesma categoria acolhida pelo item 1 ou categoria "fora da política" (item 9)?
**Decisão:** comparação **case-insensitive** com trim; `ALIMENTACAO` ≡ `alimentacao` (RN-001).
**Justificativa:** caixa é artefato de digitação, não de política; punir por caixa criaria recusa sem fundamento de negócio.
**Regra afetada:** RN-001.

### AMB-010 — Hospedagem "por diária" vs valor total do lançamento

**Texto original do RH:** "Hospedagem tem limite de **R$ 250 por diária**."
**O que não está claro:** d-010 (R$ 480) diz "2 diárias" e d-013 (R$ 690) diz "3 noites" na descrição livre. Compara-se o valor total contra R$ 250 ou divide-se pela quantidade de diárias? E onde está a quantidade — só em texto livre?
**Decisão:** **o limite aplica-se ao valor de cada lançamento**, sem extrair diárias da descrição — a entrada não tem campo confiável para isso (RN-007).
**Justificativa:** parsear texto livre ("2 diarias", "3 noites", "x2") é frágil e inventa precisão; a regra simples é conservadora, verificável, e o caso real de múltiplas diárias cabe em um envelope futuro (nova captação de dado), não em heurística de string.
**Regra afetada:** RN-007.

### AMB-011 — Precisão decimal (33,333)

**Texto original do RH:** não coberto.
**O que não está claro:** valores abaixo do centavo (d-011 = 33,333) existem na conta? Qual modo de arredondamento?
**Decisão:** arredondar para **2 casas, metade para cima**, na entrada e em cada campo de saída; comparações de limite usam os valores arredondados (RN-010).
**Justificativa:** moeda opera em centavos; "metade para cima" é o modo mais comum em sistemas financeiros em português e o esperado em justificativas para colaboradores.
**Regra afetada:** RN-010.

### AMB-012 — Interação: recusa anterior ainda conta no limite do dia?

**Texto original do RH:** combinação dos itens 4, 5 e 7.
**O que não está claro:** se uma corrida é recusada por falta de nota, o valor dela ainda enche a cota diária de transporte? E um estorno abate cota já recusada?
**Decisão:** **não** — a cota diária considera apenas despesas que sobreviveram às recusas (RN-002..RN-005); estornos (RN-009) abatem a soma dessas despesas elegíveis, pela ordem de entrada (RN-009). A ordem completa está na seção 8.
**Justificativa:** cota gasta por valor que o colaborador não recebe seria injusta e indetectável sem abrir o cálculo; a regra "só vale quem passou" mantém o resultado auditável despesa a despesa.
**Regra afetada:** RN-006, RN-009.

### AMB-013 — Fim de semana e feriados

**Texto original do RH:** não coberto.
**O que não está claro:** d-012 é um sábado ("plantão"). Alimentação de sábado é elegível?
**Decisão:** **sim** — a política não distingue dia da semana; qualquer data dentro do período é válida (RN-003 define o único recorte temporal).
**Justificativa:** criar restrição que a política não menciona seria mudança de regra disfarçada de interpretação; plantão e viagens aos fins de semana são o caso normal de quem precisa de reembolso.
**Regra afetada:** RN-003, RN-006.

---

## 7. Casos de borda

| Caso | Entrada | Comportamento esperado | Regra |
|---|---|---|---|
| Exatamente R$ 100,00 sem nota | d-003 (100,00, sem nota) | elegível; cota diária reduzida de 80; parte elegível R$ 80,00 → aprovada parcial se houver outra no dia | RN-005, RN-006 |
| R$ 0,01 acima de R$ 100 sem nota | d-004 (100,01, sem nota) | recusada por falta de nota | RN-005 |
| Duplicata exata mesmo dia | d-006 + d-007 | d-006 elegível, d-007 recusada citando d-006 | RN-004 |
| Soma diária acima do limite | d-001 + d-002 (110,50, alimentação) | total reembolsado do dia = R$ 60,00 | RN-006 |
| Estorno sozinho no dia | d-009 (−45,00) | recusada como estorno; dia/categoria zerado | RN-009 |
| Despesa de outra competência | d-008 (2026-04-15) | recusada, fora do período | RN-003 |
| Categoria não listada | d-005 (coworking) | recusada | RN-002 |
| Case diferente | d-014 (ALIMENTACAO) | tratada como alimentacao | RN-001 |
| Valor com 3 casas decimais | d-011 (33,333) | 33,33 em todos os cálculos e saídas | RN-010 |
| Hospedagem sem nota acima de R$ 100 | d-013 (690,00, sem nota) | recusada por nota (não chega ao limite) | RN-005, ordem §8 |
| Múltiplos limites ampliados | `em_viagem: true`, hospedagem 400,00 | parcial em R$ 375,00 | RN-007, RN-008 |
| Sábado/plantão | d-012 (47,20 em sábado) | elegível normalmente | AMB-013 |
| Hospedagem com diárias na descrição | d-010 ("2 diarias", 480,00) | cortada no limite do lançamento: R$ 250,00; número de diárias ignorado | RN-007, AMB-010 |
| Estorno sobre cota já cortada | alimentação 90 + estorno −40 no mesmo dia | soma elegível = 50; sem corte; sem crédito residual | RN-006, RN-009 |
| d-004 recusada contamina d-003? | ambas transporte_urbano no mesmo dia | d-003 entra na cota (80,00); d-004 zerada antes da agregação | AMB-012 |

## 8. Ordem de aplicação das regras

A ordem é fixa e muda o resultado. Cada despesa percorre o pipeline abaixo, e uma recusa em qualquer passo encerra o processamento daquele item:

1. **RN-001** — normalizar categoria (case/trim). Se não estiver no catálogo → **RN-002** recusa.
2. **RN-003** — data fora do período → recusa.
3. **RN-004** — duplicata de item elegível/existente já processado → recusa. (O "primeiro" é o primeiro na ordem da entrada.)
4. **RN-009** — valor negativo: marca como estorno, recusa o item e abate a soma elegível do dia/categoria.
5. **RN-005** — valor individual > R$ 100,00 sem nota → recusa.
6. **RN-008** — resolve limites vigentes (base ou +50% se `em_viagem`).
7. **RN-006 / RN-007** — agrega elegíveis por categoria+dia (alimentação, transporte) ou por lançamento (hospedagem) e corta o excedente, em ordem de entrada do dia.
8. **RN-010** — arredonda saídas.
9. **RN-011** — toda decisão carrega motivo.

Consequências obrigatórias desta ordem:

- item recusado antes do passo 7 **não** gasta cota diária (AMB-012);
- duplicata só é detectada contra itens já elegíveis (d-007 só aponta d-006 se d-006 sobreviveu, mesmo caso prático: duplicata certa segue rejeitável);
- a exigência de nota é testada antes de qualquer limite — hospedagem grande sem nota morre por nota, não por teto.

## 9. Critérios de aceite

O sistema está pronto quando:

- [ ] Processando `exemplos/despesas-exemplo.json`, cada despesa tem status, valor reembolsado e ao menos um motivo (RN-011).
- [ ] d-001/d-002 somam R$ 60,00 reembolsados na alimentação de 2026-07-03; d-006/d-007 viram "elegível + duplicata" (RN-004, RN-006).
- [ ] d-003 é aprovada (limite diário aplicado) e d-004 é recusada por nota (RN-005, fronteira do R$ 100).
- [ ] d-005, d-008, d-013 são recusadas (categoria, período, nota) e d-011 é calculada como 33,33 (RN-002, RN-003, RN-005, RN-010).
- [ ] d-010 é parcial em R$ 250,00 (RN-007); d-009 não reembolsa nem gera crédito (RN-009).
- [ ] Com `periodo.em_viagem: true` numa entrada alternativa, os três limites aumentam 50% e o gatilho de nota permanece R$ 100,00 (RN-008).
- [ ] Toda regra RN-001..RN-011 tem ao menos um teste automatizado verificável sem ler a implementação.

## 10. O que fica em aberto

- **Quantidade de diárias de hospedagem:** decidimos ignorar o texto livre (AMB-010). Resta a dúvida se a empresa realmente aprova um Airbnb de 3 noites cortado como se fosse 1 diária — decisão provisória conservadora, válida até o RH esclarecer; a resposta provável virá com o envelope do dia 2.
- **Moeda e data-limite de lançamento:** a política não fala sobre dias de atraso de lançamento dentro do mesmo período; somente o intervalo início/fim conta, sem penalizar "lançado com atraso" se a data cair no período (d-008 é recusada pela data de abril, não pelo atraso do lançamento).
- **Critério de o que torna "viagem":** delegamos a quem preenche a entrada (`em_viagem`). Se o RH der definição oficial, RN-008 deve ser revista.
