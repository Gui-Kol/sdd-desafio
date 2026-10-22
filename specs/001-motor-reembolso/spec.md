# Spec — Motor de Cálculo de Reembolso

**Versão:** 2.0 · **Status:** aprovada · **Última alteração:** 2026-10-22 (envelope do Dia 2 — ver D-003)

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
a política de reembolso vigente (v4) e, quando houver despesas em moeda
estrangeira, uma tabela de câmbio, produzir um arquivo com a decisão de
reembolso de cada despesa — valor reembolsado e justificativa legível — mais
os totais consolidados, sem intervenção humana.

## 3. Fora de escopo

- Não lê planilhas, PDFs de nota fiscal ou e-mails — apenas o formato de entrada definido na seção 4.
- Não persiste histórico nem compara com períodos anteriores — cada cálculo é independente.
- Não autentica usuário, não tem interface gráfica nem relatórios gerenciais.
- Não processa pagamento nem integra com folha/contábil — apenas calcula e justifica.
- Não verifica autenticidade de nota fiscal — confia no campo `tem_nota_fiscal`.
- Não valida centro de custo contra sistema externo — confia no campo `colaborador.centro_custo`.
- Não consulta cotações de câmbio na internet — usa exclusivamente a tabela de câmbio fornecida como entrada (RN-013).
- Não mistura políticas no mesmo período — o cálculo usa exatamente uma política, válida para todo o período (RN-012).

## 4. Entrada e saída

**Entrada:** três arquivos JSON — **despesas**, **política** e **câmbio**.

**Despesas** — conforme `exemplos/despesas-exemplo.json` (formato original) e
os arquivos do envelope `exemplos/despesas-envelope.json` e
`exemplos/despesas-envelope-cc-desconhecido.json` (Dia 2), que acrescentam o
campo opcional `moeda`. Campos e significado:

| Campo | Tipo | Significado | Obrigatório |
|---|---|---|---|
| `colaborador.id` | texto | identificador único do colaborador | sim |
| `colaborador.nome` | texto | nome do colaborador | sim |
| `colaborador.centro_custo` | texto | centro de custo — chave de resolução dos limites na política v4 (RN-012) | sim |
| `periodo.competencia` | texto `AAAA-MM` | mês/ano de competência | sim |
| `periodo.inicio` | data `AAAA-MM-DD` | primeiro dia do período (inclusive) | sim |
| `periodo.fim` | data `AAAA-MM-DD` | último dia do período (inclusive) | sim |
| `periodo.em_viagem` | booleano | colaborador esteve em viagem no período | não — ausente = `false` (ver AMB-005, AMB-020) |
| `despesas[].id` | texto | identificador único da despesa | sim |
| `despesas[].data` | data `AAAA-MM-DD` | data da despesa — define a taxa de câmbio aplicável (RN-013) | sim |
| `despesas[].categoria` | texto | categoria, sem distinção de maiúsculas/minúsculas (ver AMB-009) | sim |
| `despesas[].descricao` | texto | descrição livre | sim |
| `despesas[].fornecedor` | texto | fornecedor | sim |
| `despesas[].valor` | número | valor na moeda informada; pode ser negativo (estorno, ver AMB-008) | sim |
| `despesas[].moeda` | texto | moeda do valor informado (`BRL`, `EUR`, ...); ausente = `BRL` (ver AMB-018) | não |
| `despesas[].tem_nota_fiscal` | booleano | se há nota fiscal anexada | sim |

**Política** — conforme `exemplos/politica-v4.json`; substitui a redação em
prosa da v3 (itens 1–3, 5 e 6 viraram dados). Campos:

| Campo | Tipo | Significado | Obrigatório |
|---|---|---|---|
| `versao` | texto | versão da política | sim |
| `vigencia` | data `AAAA-MM-DD` | a partir de quando a política vale; pré-condição do lote (RN-012) | sim |
| `moeda_base` | texto | moeda em que limites, gatilho de nota, taxas e saída são expressos (`BRL`) | sim |
| `padrao` | mapa categoria → `{limite, periodicidade, observacao?}` | limites default para qualquer centro de custo sem override | sim |
| `centros_custo` | mapa CC → mapa categoria → `{limite, periodicidade, observacao?}` | overrides por centro de custo | não |
| `nota_fiscal_obrigatoria_acima_de` | número | gatilho da exigência de nota (RN-005) | sim |
| `acrescimo_em_viagem_percentual` | número | percentual de acréscimo dos limites em viagem (RN-008) | sim |

`periodicidade` tem semântica fixa: `dia` = limite diário agregado (RN-006);
`diaria` = limite por lançamento (RN-007). Qualquer outro valor é erro de
processamento (RN-012).

**Câmbio** — conforme `exemplos/cambio.json`; entrada **opcional** (necessária
apenas quando há despesa em moeda ≠ BRL):

| Campo | Tipo | Significado |
|---|---|---|
| `moeda_base` | texto | moeda em que as taxas são expressas (`BRL`) |
| `taxas` | mapa data → mapa moeda → número | BRL por unidade da moeda na data (PTAX de fechamento); publicado apenas em dias úteis bancários — o fallback está na RN-013 |

Sem arquivo de câmbio (ou sem taxa para a moeda/data), toda despesa em moeda
≠ BRL é recusada como "sem cotação" (RN-013).

**Saída:** um objeto com o resultado de cada despesa e os totais.

| Campo | Tipo | Significado |
|---|---|---|
| `colaborador_id` | texto | id do colaborador |
| `competencia` | texto | competência calculada |
| `total_solicitado` | número (2 casas) | soma dos valores em BRL (item sem cotação contribui com 0) |
| `total_reembolsado` | número (2 casas) | soma dos valores reembolsados, em BRL |
| `despesas[].id` | texto | id da despesa |
| `despesas[].status` | texto | `aprovada`, `parcial` ou `recusada` |
| `despesas[].moeda` | texto | moeda original do lançamento (`BRL` quando ausente na entrada) |
| `despesas[].valor_original` | número (2 casas) | valor informado, na moeda original |
| `despesas[].valor_solicitado` | número (2 casas) | valor convertido para BRL (RN-013); nulo quando não há cotação |
| `despesas[].valor_reembolsado` | número (2 casas) | valor efetivamente reembolsado, em BRL |
| `despesas[].taxa_cambio` | número | taxa aplicada; presente somente quando moeda ≠ BRL |
| `despesas[].data_taxa` | data `AAAA-MM-DD` | data da publicação da taxa aplicada (pode ser anterior à data da despesa); presente somente quando moeda ≠ BRL |
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

**Exemplo com moeda estrangeira** (30 EUR num sábado sem cotação própria;
limite diário de alimentação do CC-COMERCIAL: R$ 90,00) — item na entrada e o
item correspondente na saída:

```json
{"id": "e-004", "data": "2026-07-18", "categoria": "alimentacao", "descricao": "Jantar de sabado", "fornecedor": "Cervejaria Ramiro", "valor": 30.00, "moeda": "EUR", "tem_nota_fiscal": true}
```

```json
{
  "id": "e-004",
  "status": "parcial",
  "moeda": "EUR",
  "valor_original": 30.00,
  "valor_solicitado": 178.80,
  "valor_reembolsado": 90.00,
  "taxa_cambio": 5.96,
  "data_taxa": "2026-07-17",
  "motivos": ["Excedente cortado ao limite da categoria"]
}
```

## 5. Regras de negócio

Cada regra recebe um ID (`RN-001`, ...). As tasks vão referenciar esses IDs.

### RN-001 — Normalização e catálogo de categorias

**Regra:** a categoria é comparada sem distinção de maiúsculas/minúsculas e sem espaços nas bordas. O catálogo deixa de ser fixo: uma categoria é reembolsável quando a política v4 resolve um limite para a combinação (centro de custo, categoria) — override do centro de custo ou bloco `padrao` (RN-012). Categoria sem limite resolvido é recusada integralmente (RN-002).
**Origem:** política do RH, itens 1–3 e 9; política v4 (blocos `padrao` e `centros_custo`); AMB-009, AMB-015.
**Aceite:** `"ALIMENTACAO"` é tratada como `alimentacao`; `coworking` gera recusa com motivo "Categoria fora da política"; `representacao` é reembolsável para CC-COMERCIAL e recusada para um centro de custo sem a chave.

### RN-002 — Categoria fora da política

**Regra:** despesa de categoria sem limite resolvido pela RN-012 — inexistente na política, ou presente para outros centros de custo mas ausente no bloco do colaborador e no `padrao` — é recusada com reembolso zero.
**Origem:** política do RH, item 9; política v4; AMB-014, AMB-015.
**Aceite:** uma despesa `coworking` de R$ 89,00 resulta em `recusada`, R$ 0,00; `representacao` de R$ 190,00 com centro de custo CC-SUPORTE-N2 (fora da política) é recusada, R$ 0,00.

### RN-003 — Pertinência ao período de competência

**Regra:** a data da despesa deve estar entre `periodo.inicio` e `periodo.fim`, inclusive. Fora desse intervalo, a despesa é recusada integralmente com motivo "Despesa fora do período de competência".
**Origem:** política do RH, item 7; AMB-007.
**Aceite:** despesa de 2026-04-15 num período 2026-07-01 a 2026-07-31 é recusada.

### RN-004 — Duplicatas

**Regra:** duas ou mais despesas são duplicatas quando coincidem em **data, categoria normalizada, fornecedor e valor convertido na moeda base** (tolerância de R$ 0,01; a moeda original não entra na comparação). A primeira, na ordem da entrada, permanece elegível; as demais são recusadas integralmente com motivo "Duplicata da despesa d-XXX".
**Origem:** política do RH, item 8; AMB-006, AMB-022.
**Aceite:** d-006 e d-007 (mesma data/fornecedor/valor) → d-006 elegível, d-007 recusada citando d-006.

### RN-005 — Exigência de nota fiscal

**Regra:** toda despesa **individual** com valor em BRL (RN-013) estritamente maior que o gatilho `nota_fiscal_obrigatoria_acima_de` da política (R$ 100,00 na v4) exige nota fiscal. Despesa sem nota acima desse valor é recusada integralmente. Despesa de exatamente R$ 100,00 (ou menos) não exige nota.
**Origem:** política do RH, item 5; política v4 (`nota_fiscal_obrigatoria_acima_de`); AMB-003, AMB-004, AMB-019.
**Aceite:** R$ 100,00 sem nota → elegível; R$ 100,01 sem nota → recusada; 40 USD sem nota (R$ 220,00 após conversão) → recusada por nota.

### RN-006 — Limite diário por categoria, com agregação

**Regra:** as categorias com periodicidade `dia` na política v4 (`alimentacao`, `transporte_urbano` e, quando o centro de custo a cobrir, `representacao`) têm limite **diário**, resolvido por RN-012 para o centro de custo do colaborador (`padrao`: alimentação R$ 60,00, transporte R$ 80,00; CC-COMERCIAL: R$ 90,00 / R$ 150,00 / representação R$ 300,00). O limite aplica-se à **soma dos valores elegíveis em BRL das despesas daquela categoria naquele dia** (após RN-002..RN-005 e RN-013). O excedente é cortado: o colaborador recebe o limite, nunca mais. O corte é registrado nas despesas do dia em ordem de entrada, consumindo a cota diária sucessivamente — a preferência vai para a primeira despesa do dia.
**Origem:** política do RH, itens 1, 2 e 4; política v4 (`padrao`, `centros_custo`, `periodicidade: dia`); AMB-001, AMB-002.
**Aceite:** duas alimentações de R$ 72,50 + R$ 38,00 no mesmo dia → R$ 72,50 reembolsado na primeira e R$ 0,00 na segunda (soma 110,50 > 60), total diário de alimentação = R$ 60,00; com CC-COMERCIAL (limite 90,00), alimentação de R$ 95,00 num dia → parcial, R$ 90,00.

### RN-007 — Limite de hospedagem por lançamento

**Regra:** categorias com periodicidade `diaria` na política v4 — hospedagem — limitam-se ao valor resolvido por RN-012 **por lançamento** (cada lançamento representa uma única fatura de diária na acepção da política; `padrao`: R$ 250,00; CC-COMERCIAL: R$ 400,00). O excedente é cortado. Limites diários RN-006 não se aplicam. A política v4 confirma a leitura por lançamento de D-002.
**Origem:** política do RH, itens 3 e 4; política v4 (`periodicidade: diaria`); AMB-010.
**Aceite:** hospedagem de R$ 480,00 → parcial, R$ 250,00 reembolsados; com CC-COMERCIAL, hospedagem de R$ 1.200,00 ("3 noites") → parcial, R$ 400,00.

### RN-008 — Acréscimo de 50% em viagem

**Regra:** quando `periodo.em_viagem` é `true`, todos os limites resolvidos por RN-012 são ampliados pelo percentual `acrescimo_em_viagem_percentual` da política (50% na v4): limite vigente × 1,5 (no `padrao`: alimentação R$ 90,00/dia, transporte urbano R$ 120,00/dia, hospedagem R$ 375,00/lançamento). Limite 0,00 — categoria não reembolsável, AMB-015 — permanece 0,00. O valor que dispara a exigência de nota (RN-005) **não** é ampliado. `em_viagem` ausente = `false` (AMB-005); nem despesas em moeda estrangeira nem despesas no exterior caracterizam viagem (AMB-020).
**Origem:** política do RH, item 6; política v4 (`acrescimo_em_viagem_percentual`); AMB-005, AMB-020.
**Aceite:** em viagem, alimentação de R$ 90,00/dia é totalmente reembolsada; sem viagem, o mesmo total seria cortado em R$ 60,00; as despesas em EUR/USD do envelope sem `em_viagem` não recebem acréscimo.

### RN-009 — Valores negativos (estornos)

**Regra:** despesa com valor em BRL (RN-013) negativo nunca é reembolsável por si só; ela **reduz a soma diária da sua categoria** antes da aplicação do limite. Se a soma diária de uma categoria ficar negativa ou zero, nada é reembolsado por aquela categoria naquele dia (não há devolução ao colaborador). Estorno não exige nota fiscal e não gera categoria própria.
**Origem:** derivada de consistência financeira; AMB-008.
**Aceite:** num dia com estorno de −R$ 45,00 e nenhuma outra despesa na categoria, o reembolso daquela categoria/dia é R$ 0,00.

### RN-010 — Arredondamento monetário

**Regra:** todo valor monetário na saída é arredondado para 2 casas decimais, "metade para cima" (0,5 arredonda para longe de zero), aplicado uma única vez sobre o valor final de cada campo de saída. Valores de entrada com mais de 2 casas são arredondados da mesma forma antes de qualquer cálculo; o valor convertido por câmbio (RN-013) segue o mesmo regime.
**Origem:** não coberto pela política; AMB-011.
**Aceite:** valor informado de 33,333 → 33,33 em todos os campos derivados.

### RN-011 — Justificativa obrigatória e rastreável

**Regra:** toda despesa na saída carrega ao menos um motivo legível: o motivo da recusa, do corte parcial ("excedente cortado ao limite da categoria") ou da aprovação integral. Nenhum valor muda sem motivo correspondente.
**Origem:** objetivo do sistema (seção 2).
**Aceite:** nenhuma despesa na saída com `motivos` vazio.

### RN-012 — Política v4: vigência e limites por centro de custo

**Regra:** a política v4 é um arquivo de entrada (seção 4). Pré-condição do lote: `vigencia` deve ser anterior ou igual a `periodo.inicio` — política que ainda não valia para o período, ou que passou a valer no meio dele, é erro de processamento; nada é calculado. Para cada despesa, o limite e a periodicidade vigentes da sua categoria resolvem assim: entrada em `centros_custo[centro_custo][categoria]` quando existir; senão, entrada em `padrao[categoria]`; ausente em ambos → categoria fora da política (RN-002). Centro de custo sem bloco próprio resolve tudo pelo `padrao`. Limite resolvido igual a R$ 0,00 significa que a categoria **não é reembolsável** para aquele centro de custo: recusa integral com motivo próprio, não corte parcial — e o acréscimo de viagem não o altera (0 × 1,5 = 0). Periodicidade aceita: `dia` (limite diário agregado, RN-006) ou `diaria` (limite por lançamento, RN-007); qualquer outro valor é erro de processamento.
**Origem:** política v4 (`vigencia`, `padrao`, `centros_custo`, `periodicidade`); AMB-014, AMB-015, AMB-021.
**Aceite:** CC-SUPORTE-N2 (sem bloco) resolve alimentação 60 / transporte 80 / hospedagem 250 e recusa `representacao`; CC-ADM resolve hospedagem 250 (herdado do `padrao`); CC-ENG-PLATAFORMA recusa hospedagem (limite 0,00, "nao reembolsavel"); CC-COMERCIAL resolve 90 / 150 / 400 / representação 300.

### RN-013 — Moeda e conversão cambial

**Regra:** `despesas[].moeda` ausente = `BRL` (moeda base). Despesa em BRL não sofre conversão nem consulta de taxa. Despesa em outra moeda converte-se para BRL multiplicando o valor pela taxa da **data da despesa**; sem cotação publicada na data (fim de semana, feriado — o arquivo de câmbio publica só em dias úteis), usa-se a **última cotação publicada em data igual ou anterior**; se nenhuma cotação existir para a moeda em data ≤ à da despesa, a despesa é recusada integralmente com motivo "Moeda sem cotação disponível" e o lote segue. O valor convertido é arredondado a 2 casas (RN-010) antes de qualquer comparação. Todas as regras (nota, duplicata, estorno, limites) e todos os totais operam sobre o valor em BRL.
**Origem:** envelope do Dia 2 (`cambio.json`, campo `moeda`); AMB-016, AMB-017, AMB-018, AMB-019.
**Aceite:** 22 EUR em 2026-07-14 → 22 × 5,93 = R$ 130,46; 30 EUR em 2026-07-18 (sábado sem cotação) → taxa de 2026-07-17 (5,96) → R$ 178,80; 55 GBP (sem cotação na tabela) → recusada com motivo "Moeda sem cotação disponível".

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

### AMB-014 — Centro de custo ausente do bloco `centros_custo`

**Texto original (envelope):** a política v4 traz `padrao` e `centros_custo`; o colaborador de `despesas-envelope-cc-desconhecido.json` tem `CC-SUPORTE-N2`, inexistente no bloco.
**O que não está claro:** usam-se os limites `padrao`? Recusa-se tudo? O lote inteiro falha? E a categoria `representacao` (f-003), que só existe no bloco do CC-COMERCIAL?
**Decisão:** centro de custo sem bloco resolve **todas** as categorias pelo `padrao`; categoria sem entrada no `padrao` é recusada como fora da política. Nenhuma recusa em bloco, nenhum erro de lote (RN-012, RN-002).
**Justificativa:** o bloco se chama `padrao` — o nome do dado já declara a semântica de fallback; recusar tudo tornaria o motor inútil para todo centro de custo novo (o caso normal de crescimento), e a recusa granular mantém cada decisão com motivo próprio e auditável (RN-011).
**Regra afetada:** RN-002, RN-012.

### AMB-015 — Categoria ausente no bloco do CC; limite 0,00 "nao reembolsavel"

**Texto original (envelope):** CC-ADM não traz `hospedagem` no bloco; CC-ENG-PLATAFORMA traz `hospedagem` com `limite: 0.00` e `observacao: "nao reembolsavel"`.
**O que não está claro:** omissão significa proibição ou herança do `padrao`? Limite 0,00 é corte a zero, recusa, ou outra coisa?
**Decisão:** a resolução é por (centro de custo, categoria): entrada no bloco do CC é override; categoria ausente no bloco **herda** o `padrao`. Limite explícito 0,00 = categoria **não reembolsável** para o CC: recusa com motivo próprio, não corte parcial — e viagem não a altera (RN-012).
**Justificativa:** o próprio arquivo distingue os dois casos: CC-ENG-PLATAFORMA proíbe hospedagem com 0,00 + observação em vez de simplesmente omitir a chave. Se omissão já fosse proibição, o 0,00 explícito seria redundante — ler omissão como herança é a única interpretação compatível com o arquivo inteiro.
**Regra afetada:** RN-002, RN-008, RN-012.

### AMB-016 — Câmbio: taxa de qual data? E dias sem cotação?

**Texto original (envelope):** `cambio.json` declara "Cotacoes publicadas apenas em dias uteis bancarios"; e-004 tem data 2026-07-18, um sábado sem cotação na tabela.
**O que não está claro:** converte pela taxa de qual dia — a da data da despesa, a do dia útil anterior, a do próximo, a do fim do período?
**Decisão:** taxa da **data da despesa** (PTAX de fechamento); sem cotação na data, a **última publicada em dia igual ou anterior**. Nunca taxa futura; sem nenhuma publicação em data ≤ à da despesa, recusa do item (RN-013).
**Justificativa:** a data da despesa é o fato gerador do custo; "última publicada" reproduz a prática bancária da PTAX vigente — a cotação que valia quando o gasto aconteceu. Taxa futura inventa informação que não existia; recusar fim de semana derrubaria despesas legítimas que a própria tabela consegue precificar.
**Regra afetada:** RN-013.

### AMB-017 — Moeda sem cotação na tabela (GBP)

**Texto original (envelope):** e-006 é 55 GBP; `cambio.json` só traz USD e EUR.
**O que não está claro:** estima-se uma taxa? Derruba-se o lote? Recusa-se o item? Trata-se como BRL?
**Decisão:** **recusa do item** com motivo "Moeda sem cotação disponível", reembolso 0,00 e sem valor em BRL; o lote continua e os demais itens são processados normalmente (RN-013).
**Justificativa:** taxa estimada destrói a rastreabilidade — nenhuma justificativa sobrevive a um número inventado; derrubar o lote por um item contradiz o objetivo de decidir despesa a despesa sem intervenção humana; a recusa documentada devolve ao financeiro a ação correta: cadastrar a cotação e reprocessar.
**Regra afetada:** RN-011, RN-013.

### AMB-018 — Campo `moeda` ausente

**Texto original (envelope):** e-010, f-001 e f-002 não trazem `moeda`; e-001 traz `"moeda": "BRL"` explícito.
**O que não está claro:** ausência é erro de schema, sempre BRL, ou outra coisa?
**Decisão:** ausente = `BRL`, idêntico ao explícito: sem conversão e sem consulta de taxa (RN-013).
**Justificativa:** `moeda_base: "BRL"` está declarado na política e no câmbio; é o mesmo regime de AMB-005 (`em_viagem` ausente = `false`) — default explícito e documentado, sem adivinhação. Erro de schema derrubaria lotes legítimos por um campo que o formato original nem tinha.
**Regra afetada:** RN-013.

### AMB-019 — Gatilhos e limites sobre o valor original ou o convertido?

**Texto original (envelope):** e-005 são 40 USD — "parece" barato, mas vira ~R$ 220,00; gatilho de nota e limites são expressos em reais.
**O que não está claro:** a exigência de nota dispara sobre 40 (moeda original) ou sobre 220,00 (BRL)? E limites, duplicata, estornos e totais?
**Decisão:** **converte primeiro, decide depois** — nota (RN-005), duplicata (RN-004), estorno (RN-009), limites (RN-006/RN-007) e totais operam exclusivamente sobre o valor em BRL arredondado. e-005 é recusada por falta de nota (RN-013).
**Justificativa:** gatilhos e limites são expressos na moeda base; testá-los na moeda original permitiria burlá-los lançando em dólar (40 USD passaria como "abaixo de 100"), e totais que somam moedas diferentes não significam nada.
**Regra afetada:** RN-004, RN-005, RN-006, RN-007, RN-009, RN-013, seção 4 (totais).

### AMB-020 — Viagem aparente, campo `em_viagem` ausente

**Texto original (envelope):** as despesas e-002..e-006 são em Lisboa e Londres; o arquivo não traz `periodo.em_viagem`.
**O que não está claro:** despesas no exterior caracterizam viagem e ampliam limites em 50%?
**Decisão:** **não** — AMB-005 reafirmada: `em_viagem` ausente = `false`, sem acréscimo, por mais óbvia que pareça a viagem (RN-008).
**Justificativa:** inferência a partir de descrição livre é exatamente a heurística rejeitada em AMB-010; despesa em moeda estrangeira não implica política de viagem (cartão corporativo internacional existe); o campo existe para transferir a responsabilidade a quem lança.
**Regra afetada:** RN-008.

### AMB-021 — Vigência da política (`2026-07-01`)

**Texto original (envelope):** a política v4 declara `vigencia: 2026-07-01`; o período processado é 2026-07.
**O que não está claro:** pode-se processar período anterior à vigência? E política que passa a valer no meio do período?
**Decisão:** `vigencia` é pré-condição do lote: deve ser **anterior ou igual a `periodo.inicio`**; se não for, o processamento falha com erro e nada é calculado. Cálculo com política mista no mesmo período não existe (RN-012).
**Justificativa:** calcular um período com política que ainda não valia produz números que nenhuma justificativa sustenta; falhar rápido e alto é o único resultado auditável. Selecionar a política certa por competência é responsabilidade de quem orquestra os lotes (§10).
**Regra afetada:** RN-012.

### AMB-022 — Duplicata entre moedas diferentes

**Texto original (envelope):** a chave de duplicata da v3 comparava "valor"; o envelope introduz moedas.
**O que não está claro:** duas despesas de 100 (uma USD, uma BRL) no mesmo dia/fornecedor/categoria são duplicatas? E a mesma despesa relançada em outra moeda?
**Decisão:** a comparação de valor usa o **valor convertido em BRL** (tolerância R$ 0,01 mantida); a moeda não entra na chave (RN-004).
**Justificativa:** duplicata é sobre o mesmo fato financeiro, e o fato financeiro do motor é o BRL (AMB-019); comparar na moeda original deixaria passar o relançamento da mesma despesa com moeda trocada. O falso-positivo teórico (valores que coincidem após conversão) tem a natureza do risco já aceito em AMB-006 e é contestável pelo motivo registrado.
**Regra afetada:** RN-004.

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
| Limite do CC acima do padrão | e-010 (88,00 alimentacao, CC-COMERCIAL) | aprovada integral 88,00 — o padrão (60,00) cortaria | RN-012, RN-006 |
| Limite do CC abaixo do valor | e-008 (95,00 alimentacao, CC-COMERCIAL) | parcial 90,00 | RN-012, RN-006 |
| Categoria exclusiva de um CC | e-001 (340,00 representacao, CC-COMERCIAL) | parcial 300,00 (limite diário 300,00) | RN-012, RN-006 |
| Hospedagem com limite de CC | e-007 (1.200,00, "3 noites", CC-COMERCIAL) | parcial 400,00; número de noites ignorado (D-002) | RN-007, RN-012 |
| Moeda estrangeira acima do limite | e-002 (22 EUR em 2026-07-14) | 22 × 5,93 = 130,46 → parcial 90,00 | RN-013, RN-006 |
| Moeda estrangeira dentro do gatilho de nota | e-003 (14,50 EUR = 85,26) | aprovada 85,26; nota não exigida | RN-005, RN-013 |
| Despesa em dia sem cotação (sábado) | e-004 (30 EUR em 2026-07-18) | taxa de 2026-07-17 (5,96) → 178,80 → parcial 90,00 | RN-013, AMB-016 |
| Gatilho de nota sobre valor convertido | e-005 (40 USD = 220,00, sem nota) | recusada por falta de nota | RN-005, RN-013, AMB-019 |
| Moeda sem cotação na tabela | e-006 (55 GBP) | recusada "Moeda sem cotação disponível"; sem valor em BRL; lote continua | RN-013, AMB-017 |
| `moeda` ausente | e-010 (88,00 sem `moeda`) | tratada como BRL; sem conversão | RN-013, AMB-018 |
| CC fora da política + categoria de outro CC | f-003 (190,00 representacao, CC-SUPORTE-N2) | recusada, categoria fora da política (padrão não cobre) | RN-002, RN-012, AMB-014 |
| CC fora da política, limites padrão | f-001 (58,00) e f-002 (310,00 hospedagem) | f-001 aprovada 58,00; f-002 parcial 250,00 | RN-012, AMB-014 |
| CC fora da política + moeda estrangeira | f-004 (12 USD em 2026-07-21) | 12 × 5,48 = 65,76 ≤ 80 → aprovada 65,76 | RN-012, RN-013 |
| Limite 0,00 com observação | hospedagem qualquer, CC-ENG-PLATAFORMA | recusada: "não reembolsável para o centro de custo" | RN-012, AMB-015 |
| Categoria ausente no bloco de CC conhecido | hospedagem qualquer, CC-ADM | herda o padrão: limite 250,00 por lançamento | RN-012, AMB-015 |
| Viagem aparente, campo ausente | e-002..e-006 (Lisboa/Londres) sem `em_viagem` | sem acréscimo de 50%; limites do CC como estão | RN-008, AMB-020 |

## 8. Ordem de aplicação das regras

A ordem é fixa e muda o resultado. Antes do pipeline por despesa, uma
pré-condição do lote: **RN-012** valida a política — `vigencia` ≤
`periodo.inicio` e periodicidades conhecidas; falha aqui encerra o
processamento com erro, nada é calculado. Cada despesa percorre então o
pipeline abaixo, e uma recusa em qualquer passo encerra o processamento daquele item:

1. **RN-001** — normalizar categoria (case/trim).
2. **RN-012** — resolver o limite e a periodicidade vigentes para (centro de custo, categoria). Sem resolução → **RN-002** recusa; limite 0,00 → recusa como não reembolsável.
3. **RN-003** — data fora do período → recusa.
4. **RN-013** — converter para BRL (moeda ausente = BRL); sem cotação → recusa do item.
5. **RN-004** — duplicata de item elegível/existente já processado, comparando valor em BRL → recusa. (O "primeiro" é o primeiro na ordem da entrada.)
6. **RN-009** — valor em BRL negativo: marca como estorno, recusa o item e abate a soma elegível do dia/categoria.
7. **RN-005** — valor em BRL individual > gatilho da política sem nota → recusa.
8. **RN-008** — amplia os limites vigentes pelo percentual de viagem, se `em_viagem`.
9. **RN-006 / RN-007** — aplica o limite conforme periodicidade: `dia` agrega elegíveis por categoria+dia; `diaria` limita por lançamento. Corta o excedente em ordem de entrada do dia.
10. **RN-010** — arredonda saídas.
11. **RN-011** — toda decisão carrega motivo.

Consequências obrigatórias desta ordem:

- item recusado antes do passo 9 **não** gasta cota diária (AMB-012);
- duplicata só é detectada contra itens já elegíveis (d-007 só aponta d-006 se d-006 sobreviveu, mesmo caso prático: duplicata certa segue rejeitável);
- a exigência de nota é testada antes de qualquer limite — hospedagem grande sem nota morre por nota, não por teto;
- a conversão (passo 4) acontece depois das recusas de categoria e período: despesa em moeda sem cotação **e** fora do período morre como "fora do período" — a recusa mais fundamental primeiro;
- item sem cotação não possui valor em BRL: não entra em nenhum total, não gasta cota e não serve de original para duplicata;
- viagem não amplia limite 0,00 — proibição de categoria (AMB-015) não vira permissão por deslocamento a trabalho.

## 9. Critérios de aceite

O sistema está pronto quando:

- [ ] Processando `exemplos/despesas-exemplo.json`, cada despesa tem status, valor reembolsado e ao menos um motivo (RN-011).
- [ ] d-001/d-002 somam R$ 60,00 reembolsados na alimentação de 2026-07-03; d-006/d-007 viram "elegível + duplicata" (RN-004, RN-006).
- [ ] d-003 é aprovada (limite diário aplicado) e d-004 é recusada por nota (RN-005, fronteira do R$ 100).
- [ ] d-005, d-008, d-013 são recusadas (categoria, período, nota) e d-011 é calculada como 33,33 (RN-002, RN-003, RN-005, RN-010).
- [ ] d-010 é parcial em R$ 250,00 (RN-007); d-009 não reembolsa nem gera crédito (RN-009).
- [ ] Com `periodo.em_viagem: true` numa entrada alternativa, os três limites aumentam 50% e o gatilho de nota permanece R$ 100,00 (RN-008).
- [ ] Processando `exemplos/despesas-envelope.json` com `exemplos/politica-v4.json` e `exemplos/cambio.json`: e-001 parcial R$ 300,00; e-002 parcial R$ 90,00 (valor BRL 130,46, taxa 5,93 de 2026-07-14); e-003 aprovada R$ 85,26; e-004 parcial R$ 90,00 (taxa 5,96 de 2026-07-17 — sábado usa a última cotação publicada); e-005 recusada por nota (40 USD → R$ 220,00 sem nota); e-006 recusada por falta de cotação GBP; e-007 parcial R$ 400,00; e-008 parcial R$ 90,00; e-009 recusada por categoria; e-010 aprovada R$ 88,00 (moeda ausente = BRL); `total_solicitado` = 2457,52 e `total_reembolsado` = 1143,26 (RN-012, RN-013).
- [ ] Processando `exemplos/despesas-envelope-cc-desconhecido.json` com a mesma política e câmbio: f-001 aprovada R$ 58,00; f-002 parcial R$ 250,00; f-003 recusada (representacao fora da política para centro de custo sem override); f-004 aprovada R$ 65,76 (12 USD × 5,48) (RN-012, AMB-014).
- [ ] Hospedagem para CC-ENG-PLATAFORMA (limite 0,00) é recusada como não reembolsável, mesmo com `em_viagem: true` (RN-012, AMB-015).
- [ ] Política com `vigencia` posterior a `periodo.inicio` (ex.: política v4 aplicada à competência 2026-06) falha o processamento com erro, sem produzir saída (RN-012, AMB-021).
- [ ] Toda regra RN-001..RN-013 tem ao menos um teste automatizado verificável sem ler a implementação.

## 10. O que fica em aberto

- **Quantidade de diárias de hospedagem:** decidimos ignorar o texto livre (AMB-010) e o envelope do Dia 2 confirmou a leitura — a política v4 declara periodicidade `diaria` e e-007 ("Hotel Londres - 3 noites", R$ 1.200,00) continua limitada por lançamento (R$ 400,00 no CC-COMERCIAL). D-002 mantida; se a empresa quiser multi-diária, falta campo estruturado na entrada.
- **Moeda:** entrou no escopo com o envelope (RN-013), mas só com a tabela fornecida — sem consulta online e sem taxa estimada. Moeda sem cotação é recusa por item; se o RH publicar tabela GBP, basta fornecer o arquivo, nenhuma regra muda.
- **Critério de o que torna "viagem":** delegamos a quem preenche a entrada (`em_viagem`), decisão reafirmada pelo envelope (AMB-020): despesas em Lisboa/Londres sem o campo não recebem acréscimo. Se o RH der definição oficial, RN-008 deve ser revista.
- **Data-limite de lançamento:** a política não fala sobre dias de atraso de lançamento dentro do mesmo período; somente o intervalo início/fim conta, sem penalizar "lançado com atraso" se a data cair no período (d-008 é recusada pela data de abril, não pelo atraso do lançamento).
- **Políticas históricas:** processar um período anterior à vigência da política fornecida é erro por enquanto (AMB-021); guardar e selecionar políticas por competência é problema de quem orquestra os lotes, fora do escopo do motor.
