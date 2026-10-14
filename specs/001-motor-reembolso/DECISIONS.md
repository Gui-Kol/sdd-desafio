# Log de Decisões e Mudanças de Spec

> Uma entrada **toda vez** que a spec mudar. Este arquivo é a prova de que a spec
> foi tratada como artefato vivo e não como cerimônia de abertura.
>
> Spec que não muda em dois dias é spec que ninguém consultou. Mudança não é
> demérito — mudança não registrada é.

Ordem cronológica inversa: a mais recente primeiro.

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
