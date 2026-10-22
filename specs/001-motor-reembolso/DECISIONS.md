# Log de Decisões e Mudanças de Spec

> Uma entrada **toda vez** que a spec mudar. Este arquivo é a prova de que a spec
> foi tratada como artefato vivo e não como cerimônia de abertura.
>
> Spec que não muda em dois dias é spec que ninguém consultou. Mudança não é
> demérito — mudança não registrada é.

Ordem cronológica inversa: a mais recente primeiro.

---

## D-003 — Envelope do Dia 2: política v4 por centro de custo, catálogo dinâmico e multi-moeda · 2026-10-22

**Gatilho:** o envelope lacrado do Dia 2 chegou com quatro arquivos em `exemplos/`:
`politica-v4.json` — a política vira dado (limites por centro de custo, categoria `representacao` exclusiva de um CC, gatilho de nota e acréscimo de viagem configuráveis, campo `vigencia`);
`despesas-envelope.json` — CC-COMERCIAL, despesas em EUR/USD/GBP e `moeda` ausente num item;
`despesas-envelope-cc-desconhecido.json` — centro de custo que não existe na política (CC-SUPORTE-N2);
`cambio.json` — PTAX de fechamento, publicada só em dias úteis, cobrindo só USD e EUR.

**O que mudou na spec:** spec.md 1.1 → 2.0.

- §2/§4: entrada passa a ser três arquivos — despesas, política (obrigatória) e câmbio (opcional); schemas dos três documentados; saída ganha `moeda`, `valor_original`, `taxa_cambio`, `data_taxa`, e `valor_solicitado` passa a significar o valor em BRL; totais em BRL.
- Novas regras: **RN-012** (vigência como pré-condição do lote; resolução de limites por (centro de custo, categoria) com fallback para `padrao`; limite 0,00 = não reembolsável; semântica de `periodicidade`) e **RN-013** (`moeda` ausente = BRL; conversão pela taxa da data da despesa com fallback para a última cotação publicada; recusa por item quando não há cotação; todas as comparações e totais em BRL).
- Reescritas: RN-001/RN-002 (catálogo fixo → dinâmico, derivado da política), RN-004 (duplicata compara valor em BRL), RN-005 (gatilho lido da política, sobre valor BRL), RN-006/RN-007 (limites por CC e periodicidade), RN-008 (percentual lido da política; limite 0,00 não é ampliado), RN-009 (estorno em BRL), RN-010 (valor convertido segue o regime de arredondamento).
- §3: "Não converte moedas" sai do fora-de-escopo; entram "não consulta câmbio online", "não mistura políticas no período" e "não valida centro de custo em sistema externo".
- §6: nove ambiguidades novas — AMB-014..AMB-022, cada uma com decisão e justificativa. §7: 16 casos de borda novos. §8: pré-condição de vigência + passo de conversão. §9: critérios com números esperados dos dois envelopes. §10 atualizado.

**Por quê — racional decisão a decisão:**

1. **Centro de custo ausente → bloco `padrao` (AMB-014).** Três leituras eram possíveis: recusar tudo, errar o lote ou cair no `padrao`. O bloco se chama `padrao` — o nome do dado já declara a semântica de fallback. Recusar tudo puniria o colaborador por um atraso de cadastro no RH e tornaria o motor inútil para todo CC novo; errar o lote derrubaria despesas processáveis por um item de classificação. O fallback mantém o cálculo útil, conservador (o `padrao` nunca é mais generoso que um override pensado) e auditável — cada despesa sai com o limite que valeu.
2. **Omissão ≠ proibição; 0,00 = não reembolsável (AMB-015).** CC-ADM não lista hospedagem; CC-ENG-PLATAFORMA lista hospedagem com 0,00 e "nao reembolsavel". Se omissão fosse proibição, o 0,00 explícito seria redundante — o arquivo só faz sentido se os dois casos forem distintos. Categoria ausente no bloco herda o `padrao`; 0,00 é proibição da categoria para o CC: recusa com motivo próprio, não "parcial de zero". E 0,00 × 1,5 = 0,00 — viagem não transforma proibição em permissão.
3. **Conversão pela taxa da data da despesa, fallback última publicada (AMB-016).** A data da despesa é o fato gerador do custo — competência e data de processamento não têm nada a ver com o preço do almoço em Lisboa. A tabela publica só em dias úteis (o arquivo avisa) e e-004 cai num sábado: a prática bancária da PTAX de fechamento responde exatamente isso — vale a última cotação publicada, nunca uma futura. Taxa futura inventaria informação que não existia quando o gasto aconteceu; recusar fim de semana derrubaria despesas que a própria tabela consegue precificar.
4. **Moeda sem cotação → recusa do item, lote continua (AMB-017).** e-006 (55 GBP) não encontra GBP na tabela. As saídas eram: estimar taxa, tratar como BRL, derrubar o lote ou recusar o item. Estimar destrói a rastreabilidade — nenhum motivo sobrevive a um número inventado; "tratar como BRL" pagaria 55 reais por um jantar de 55 libras (erro silencioso e caro); derrubar o lote contradiz o objetivo de decisão por despesa sem intervenção humana. A recusa documentada devolve ao financeiro a ação correta: cadastrar a cotação e reprocessar.
5. **`moeda` ausente = BRL (AMB-018).** e-010/f-001/f-002 vêm sem `moeda`; a política e o câmbio declaram `moeda_base: BRL`. Mesmo regime de AMB-005 (`em_viagem` ausente = false): default explícito e documentado, sem adivinhação e sem quebrar lotes legítimos por um campo que o formato do Dia 1 nem tinha.
6. **Converte primeiro, decide depois (AMB-019).** O gatilho de nota e os limites são expressos em BRL. Testá-los na moeda original permitiria burlá-los trivialmente: 40 USD "parece" R$ 40, mas é R$ 220,00 — e-005 é exatamente a armadilha plantada (recusada por falta de nota). Duplicata, estorno e totais idem: o fato financeiro do motor é o BRL. Totais somando moedas mistas não significam nada.
7. **Viagem não se infere (AMB-020).** O envelope traz almoços em Lisboa e jantar em Londres sem `periodo.em_viagem` — a tentação de inferir é máxima. Mantivemos AMB-005: ausente = false, sem acréscimo. Inferir de texto livre é a heurística rejeitada em AMB-010; despesa em moeda estrangeira não implica política de viagem (cartão corporativo internacional existe). O campo existe para transferir a responsabilidade a quem lança.
8. **Vigência é pré-condição do lote (AMB-021).** `vigencia: 2026-07-01` e período 2026-07 coincidem, mas o caso divergente é óbvio. Calcular um período com política que ainda não valia (ou que passa a valer no meio dele) produz números indefensáveis; misturar políticas num período é problema de orquestração, não do motor. Falha rápida e alta, nada calculado.
9. **Duplicata compara em BRL (AMB-022).** A chave da v3 comparava "valor"; com moeda, o mesmo fato financeiro pode estar lançado em moedas diferentes. Comparar o valor convertido (tolerância de R$ 0,01 mantida) pega o relançamento com moeda trocada; o falso-positivo teórico tem a natureza do risco já aceito em AMB-006.
10. **Catálogo, limites e periodicidade viram dado (RN-001/RN-002/RN-012).** A spec 1.1 fixava "exatamente alimentacao, transporte_urbano, hospedagem" e valores literais nas RNs. A v4 codifica os itens 1–3, 5 e 6 da v3 como dados — o motor não pode mais saber de antemão o que é reembolsável: `representacao` vale para CC-COMERCIAL (e-001 parcial em 300,00) e é recusada para CC-SUPORTE-N2 (f-003, ausente do `padrao`). A semântica dos dois valores de `periodicidade` fica definida na spec: `dia` = agregação diária (RN-006), `diaria` = por lançamento (RN-007).
11. **Saída rastreável (§4).** `valor_solicitado` passa a ser o valor em BRL (a unidade de toda decisão), o valor na moeda original preserva-se em `valor_original` + `moeda`, e a conversão publica a taxa aplicada e a data da publicação (`taxa_cambio`, `data_taxa`) — sem isso, o motivo "excedente cortado ao limite" não é verificável para despesas em moeda estrangeira (RN-011). Item sem cotação sai sem valor em BRL e contribui com 0 nos totais.
12. **D-002 reafirmada, não revisitada.** O envelope trouxe hospedagem "3 noites" (e-007, R$ 1.200,00) — o mesmo caso de AMB-010 — e a v4 declara periodicidade `diaria`. Confirmamos a leitura por lançamento: cortada em R$ 400,00 (CC-COMERCIAL), número de noites ignorado. Se a empresa quiser multi-diária, falta campo estruturado na entrada — registrado no aberto (§10).

**O que isso invalidou:**

- a lista fixa de RN-001 e os valores literais embutidos em RN-005/RN-006/RN-007/RN-008 — continuam válidos **enquanto dados** da política v4 (o `padrao` reproduz a v3), inválidos como constantes de comportamento do motor;
- o item "Não converte moedas — todos os valores estão em reais" do fora-de-escopo (§3) — revertido pelo envelope;
- o significado de `valor_solicitado` na saída (era o valor informado; agora é o valor em BRL) e o schema de 5 campos por despesa;
- as citações "Origem: política do RH, item N" — passam a citar também o campo correspondente da política v4;
- nenhuma AMB-001..013 foi revertida; AMB-005 e AMB-010 foram reafirmadas sob pressão de dados novos (AMB-020 e e-007/periodicidade `diaria`).

**Tasks afetadas:** reabrem ou estendem: T-003 (ingestão de política/câmbio + campo `moeda`), T-004 (catálogo dinâmico), T-006 (duplicata em BRL), T-008 (gatilho lido da política), T-009 (`Politica` deixa de ser constantes — `Politica.padrao()` morre), T-010/T-011 (limites por CC e periodicidade), T-012/T-014 (saída multi-moeda), T-015 (CLI recebe política/câmbio). T-016 (E2E dourado do exemplo base) permanece válido — todos os itens do exemplo original são BRL sob o `padrao`. A Fase 5 de `tasks.md` abre a partir daqui: ingestão da v4 (T-018), RN-012 (T-019), RN-013 (T-020), saída multi-moeda (T-021), E2E dos envelopes (T-022).

**Custo:** `spec.md` — §2 a §10 reescritas (~60% do arquivo; +2 RN, +9 AMB, +16 casos de borda); `DECISIONS.md` — esta entrada; ~1h de análise cruzando os 4 arquivos do envelope item a item antes de tocar na spec.

### Rol das decisões do envelope (resumo)

| ID | Ambiguidade | Decisão em uma linha |
|---|---|---|
| AMB-014 | CC fora da política | Resolve tudo pelo bloco `padrao`; categoria ausente no padrão → recusada |
| AMB-015 | Categoria ausente no bloco / limite 0,00 | Ausente herda o `padrao`; 0,00 = não reembolsável (recusa, sem ampliação por viagem) |
| AMB-016 | Taxa de qual data | Data da despesa; sem cotação na data, última publicada ≤ data (nunca futura) |
| AMB-017 | Moeda sem cotação (GBP) | Recusa do item com motivo; lote continua |
| AMB-018 | `moeda` ausente | = BRL (`moeda_base`); sem conversão |
| AMB-019 | Gatilhos sobre original ou convertido | Converte primeiro; nota, limites, duplicata, estorno e totais em BRL |
| AMB-020 | Viagem aparente, campo ausente | `em_viagem` ausente = false; sem inferência (AMB-005 reafirmada) |
| AMB-021 | Vigência da política | Pré-condição: vigencia ≤ periodo.inicio, senão erro do lote |
| AMB-022 | Duplicata entre moedas | Compara valor convertido em BRL; moeda não entra na chave |

---

## D-002 — Hospedagem limitada por lançamento, não por diária extraída da descrição · 2026-10-14

**Gatilho:** leitura cruzada da regra 3 do RH ("R$ 250 por diária") com d-010 ("Hotel Rio - 2 diarias", R$ 480) e d-013 ("Airbnb 3 noites", R$ 690). A descrição livre embute a quantidade de diárias, mas existe como texto não estruturado.

**O que mudou na spec:** AMB-010/RN-007 — de "limite por diária × número de diárias informado na descrição" para "limite de R$ 250 (ou R$ 375 em viagem) por lançamento, sem parsear texto".

**Por quê:** parsear "2 diarias" / "3 noites" é heurística instável que inventa precisão onde a entrada não a fornece (o dado de diárias não existe em campo estruturado). A regra simples é auditável e conservadora: no pior caso reembolsa menos, nunca mais.

**O que isso invalidou:** a leitura naive do item 3 da política, que faria d-010 (480 / 2 diárias = 240/diária) ser integralmente reembolsada. Casos de borda em §7 atualizados.

**Tasks afetadas:** nenhuma ainda (tasks não escritas); RN-007 deverá criar exactly uma tarefa à parte das agregações diárias.

**Custo:** 2 seções de `spec.md` (§6 AMB-010, §7 tabela); ~20 min de análise dos exemplos.

---

## D-001 — Baseline da spec: 13 ambiguidades resolve das 8+ exigidas · 2026-10-14

**Gatilho:** abertura do desafio. Análise linha a linha de `exemplos/despesas-exemplo.json` cruzada com os 9 itens da política do RH.

**O que mudou na spec:** spec.md sai do template vazio para a versão 1.1 com 11 regras de negócio (RN-001..RN-011) e 13 ambiguidades com decisão e justificativa (AMB-001..AMB-013). Esquema de saída definido pela primeira vez (seção 4 — era "definido por mim" no enunciado).

**Por quê:** o enunciado exige que a ambiguidade morra na spec, sem vazar decisão para chat/código. Cada d-011..d-014 do exemplo foi executar mentalmente a política; cada falha de determinismo virou uma AMB.

**O que isso invalidou:** nada — baseline.

**Tasks afetadas:** nenhuma ainda.

**Custo:** 1 arquivo (`spec.md`); sessão de análise da manhã.

### Rol das decisões iniciais (resumo)

| ID | Ambiguidade | Decisão em uma linha |
|---|---|---|
| AMB-001 | Limite por dia ou por despesa | Diário, sobre a soma elegível da categoria no dia |
| AMB-002 | "Parcial": corta ou recusa | Corta o excedente, paga até o limite |
| AMB-003 | Fronteira de R$ 100 | Estritamente maior exige nota |
| AMB-004 | Nota por item ou por dia | Por item individual |
| AMB-005 | O que é "em viagem" | Novo campo opcional `periodo.em_viagem`; gatilho de nota não amplia |
| AMB-006 | O que é duplicata | Mesmo data+categoria+fornecedor+valor → recusa a 2ª em diante |
| AMB-007 | Despesa fora do período | Recusada integral com motivo |
| AMB-008 | Valor negativo | Estorno abate a soma do dia; nunca vira crédito |
| AMB-009 | Case da categoria | Comparação case-insensitive com trim |
| AMB-010 | Hospedagem multi-diária | Limite por lançamento; sem parse de descrição |
| AMB-011 | Precisão decimal (33,333) | 2 casas, metade para cima, na entrada e na saída |
| AMB-012 | Recusadas gastam cota? | Não — só elegível consome o limite diário |
| AMB-013 | Fim de semana / plantão | Elegível normalmente |
