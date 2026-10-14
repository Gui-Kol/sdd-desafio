# Relatório — Desafio SDD

**Aluno:** Guilherme Kolndorfer · **Repositório:** `Gui-Kol/sdd-desafio` · **Data:** 2026-10-14
**Ferramenta:** OpenCode (agente `build`) · **Modelo:** `moonshotai/kimi-k3`

> Isto não é redação. São **evidências**. Toda afirmação vem acompanhada de
> arquivo, hash de commit ou trecho de sessão exportada em `docs/sessions/`.
> Nota de método: o desafio foi executado com **OpenCode + Kimi K3** (não
> Claude Code); aonde o template diz "Claude", leia-se "o agente".

---

## Delegação

*O que você fez, o que o agente fez, e por que dividiu assim.*

**A divisão:**

| Atividade | Quem | Por quê |
|---|---|---|
| Identificar ambiguidades | Agente, com leitura dirigida por mim | Pedi explicitamente a leitura cruzada `DESAFIO.md` × `exemplos/despesas-exemplo.json` (sessão 01, prompt inicial); o agente produziu 13 ambiguidades; eu validei cada uma contra o JSON exemplo antes de aceitar a spec |
| Decidir as ambiguidades | Agente propôs, eu aceitei com ressalvas | As decisões estão justificadas em `spec.md` §6 (AMB-001..AMB-013). Aceitei as 13, mas a de hospedagem (AMB-010) gerou a entrada D-002 no `DECISIONS.md` ainda na sessão de especificação — sinal de que a decisão foi feita com consciência do custo |
| Escrever a spec | Agente | Redação integral da `spec.md` v1.1 (commit `efaf9da`); minha revisão foi sobre critérios de aceite verificáveis |
| Desenhar a arquitetura | Agente, com decisão minha de stack | O agente propôs Python/Node/Go; detectei que a máquina não tinha runtime (sessão 01: `node`/`python`/`py` falhando) e respondi **Java 17** na pergunta de stack do agente — decisão minha, registrada em `plan.md` §1 |
| Implementar | Agente | 14 tasks (T-001..T-017), um commit por task (`9bf6d6e`..`fc0ee73`) |
| Escrever testes | Agente | Convenção de nomes `rnNNN_caso()` amarrando teste→regra (visível em `tests/com/reembolso/motor/MotorReembolsoTest.java`) |
| Absorver o envelope | Não houve | O envelope do Dia 2 não chegou nesta execução; ver seção **O envelope** |

**Onde deleguei e me arrependi:**

Delegar o preenchimento das caixas `[x]` do `tasks.md`. O próprio template avisa: "Marque `[x]` conforme conclui — ao longo do caminho, não tudo no fim." Ao escrever este relatório, constatei que todas as tasks estão marcadas mas os campos `**Commit:**` estão vazios (ver `specs/001-motor-reembolso/tasks.md`, T-001..T-017), e as mensagens de commit de T-004..T-008 (`fea0eb0`..`e6744b9`) descrevem fatias por categoria ("regras de alimentação", "hospedagem", "validações globais") que **não batem** com a descrição das tasks (RN-001/002, RN-003, RN-004, RN-009, RN-005). Eu aceitei os `[x]` sem conferir o casamento texto-da-task × conteúdo-do-commit. Custo: uma lacuna real de rastreabilidade que só apareceu na hora do relatório.

**Onde não deleguei e deveria ter delegado:**

A escolha de stack. O agente ainda propôs um menu (Python recomendado) e eu respondi "Java 17" por texto livre sem discutir; em compensação o agente já tinha detectado que a máquina não tinha nem Node nem Python instalados (sessão 01, chamadas `node --version` / `python --version` falhando), e Java estava disponível (`pom.xml`, `target/` e `mvn test` verde são a prova de que a escolha era executável). Delegar a sondagem de ambiente ao agente *antes* de decidir a stack teria sido o mesmo resultado com menos idas e vindas.

**Usei subagentes / skills / MCP / hooks?** Não. A única intervenção de plataforma foi a ferramenta de pergunta interativa do OpenCode (usada pelo agente na sessão 01 para a stack). Não configurei MCP/hooks: o projeto é deliberadamente pequeno (17 tasks, 4 sessões) e a rastreabilidade já era garantida por spec→tasks→commits; subagentes teriam fragmentado o contexto de uma spec de 336 linhas sem ganho proporcional.

---

## Descrição

*Como transformei requisito ambíguo em requisito verificável.*

Requisito ambíguo escolhido: **a fronteira da nota fiscal** (política do RH, item 5).

**Versão 1 (redação literal da política do RH):**
> ```
> 5. Nota fiscal é obrigatória acima de R$ 100.
> ```

**Versão final (spec.md, RN-005):**
> ```
> RN-005 — Exigência de nota fiscal
> Regra: toda despesa individual com valor estritamente maior que R$ 100,00
> exige nota fiscal. Despesa sem nota acima desse valor é recusada
> integralmente. Despesa de exatamente R$ 100,00 (ou menos) não exige nota.
> Aceite: R$ 100,00 sem nota → elegível; R$ 100,01 sem nota → recusada.
> ```
> (`specs/001-motor-reembolso/spec.md` §5 RN-005 e §6 AMB-003/AMB-004)

**O que estava ambíguo:** três coisas numa frase de sete palavras: (a) "acima de" inclui R$ 100,00 exato ou não?; (b) o gatilho é por item ou pela soma do dia?; (c) a recusa por falta de nota acontece antes ou depois do limite de categoria?

**Como percebi:** executando mentalmente o JSON de exemplo — d-003 (transporte, R$ 100,00, sem nota) e d-004 (R$ 100,01, sem nota) foram plantados lado a lado exatamente na fronteira. Com a redação original, eu não conseguiria dizer qual das duas deveria ser recusada — e qualquer implementação escolheria uma resposta sem registrar que escolheu. O par d-003/d-004 tornou a ambiguidade impossível de ignorar.

**Commit da mudança:** `efaf9da` (`docs(spec): define especificacao inicial do motor de reembolso e registra decisoes`). A decisão é AMB-003 (fronteira estrita) + AMB-004 (por item), materializada na RN-005 com caso de aceite numérico, e verificada pelos testes `rn005_fronteiraCemReaisSemNota_naoExige` e `rn005_cemVirgulaUmSemNota_recusa` em `tests/com/reembolso/motor/MotorReembolsoTest.java` (commit `e6744b9`).

---

## Discernimento

*Onde o agente errou e você pegou.*

### Caso 1 — O agente pediu a spec de volta em vez de ler o arquivo

**O que ele propôs:** ao abrir a sessão 03 para implementar a T-009, a primeira reação do Kimi K3 foi pedir a mim o conteúdo relevante — o título auto-gerado da sessão 03 é literalmente `"Para implementar a tarefa T-009 corretamente, preciso que você compartilhe o conteúdo relevante d..."`.

**Por que estava errado:** a spec e o `tasks.md` estão no repositório, a um `read` de distância. O coração do desafio é justamente que a spec seja a fonte da verdade; um agente que pede a spec "porque criou uma conversa nova" está tratando o chat como fonte — exatamente a falha que a regra 2 do desafio proíbe ("explicação no chat que não está na spec é bug de spec").

**Como eu detectei:** estava no título da sessão exportada e no fluxo — em vez de chamadas `read` para `spec.md`/`tasks.md`, o modelo sinalizou dependência do meu input. (Contraste com a sessão 01, onde o mesmo modelo leu autonomamente `DESAFIO.md`, o JSON de exemplo e os templates.)

**O que eu fiz:** não colei conteúdo no chat; apontei os arquivos e exigi que o agente lesse `specs/001-motor-reembolso/spec.md` e `tasks.md` antes de codar. Os logs da sessão 03 mostram em seguida os `read` dos dois arquivos e da spec §7/§10.

**Onde está a evidência:** `docs/sessions/03-fase3-limites-e-casos-de-borda.md` — título da sessão no cabeçalho e as chamadas `read` de `tasks.md` (linha ~107) e `spec.md` (linha ~3826).

### Caso 2 — As tasks escritas não são as tasks commitadas

**O que ele propôs:** o plano gerou T-004..T-008 como fatias por regra de negócio (RN-001/002, RN-003, RN-004, RN-009, RN-005 — commit `4e9cb09`). Os commits de implementação, porém, saíram fatiados por categoria/tema: `feat(T-004): regras de alimentacao`, `feat(T-005): regras de transporte`, `feat(T-006): regras de hospedagem`, `feat(T-007): validacoes globais e travas de comprovante`, `feat(T-008): orquestrador principal e saida json` — `fea0eb0`..`e6744b9`.

**Por que estava errado:** a regra 1 do jogo é "nenhum commit sem task", e isso só funciona se o ID da task no commit apontar para o escopo real do diff. Um corretor auditando T-006 ("duplicatas") encontra um commit que diz "hospedagem". A numeração continuou íntegra, mas o eixo de rastreabilidade ficou barato demais.

**Como eu detectei:** só ao escrever este relatório, cruzando `git log --oneline` com `specs/001-motor-reembolso/tasks.md` linha a linha. Esse é exatamente o tipo de verificação que eu deveria ter feito por task, antes do commit.

**O que eu fiz:** registrei o desvio aqui em vez de maquiar; a matriz de cobertura em `tasks.md` (Regra × Task × Teste) continua correta porque os testes queridos pelos critérios de aceite (`rn001_…`, `rn005_…` etc.) existem e passam — a rastreabilidade spec→teste segurou o que a rastreabilidade task→commit deixou vazar.

**Padrão que eu notei:** o Kimi K3 acerta muito bem quando o prompt carrega o contexto completo da task na mesma mensagem (sessões 01/02/04, uma task por prompt, critério de aceite explícito). Ele derrapa justamente nos pontos de continuidade entre sessões: abertura de sessão nova (caso 1) e coerência de longo prazo entre texto de task e mensagem de commit (caso 2). O sinal de alerta, em retrospecto, era qualquer resposta que começasse sem uma chamada `read`.

---

## Diligência

*O que você verificou antes de aceitar.*

**Meu procedimento de verificação:** por task, (1) o agente rodava `mvn test` e eu olhava o resumo verde; (2) conferi que os nomes dos testes carregavam o RN/AMB que a task prometia (a convenção `rnNNN_caso()` foi escolhida no `plan.md` §6 exatamente para tornar a auditoria barata); (3) no fechamento, rodei o E2E dourado: `ExemploE2ETest` afirma o status de cada uma das 14 despesas do exemplo e o `total_reembolsado = 585,43`, que bate com o `saida.json` gerado pela CLI (`{"total_reembolsado":585.43, ...}` — d-004 recusada, d-007 duplicata, d-010 cortada em 250,00).

**Li o diff inteiro em que porcentagem das entregas?** Honestamente: menos da metade. Li integralmente os diffs da fundação (T-001..T-003, onde o `Dinheiro` e a ingestão definem as fronteiras de arredondamento) e dos testes E2E; nas tasks T-004..T-013 passei o olho na lista de arquivos e confiei nos testes. Foi aí que o desvio de nomenclatura dos commits (Discernimento, Caso 2) passou batido.

**O que aceitei sem verificar direito, e o que me custou:** aceitei as caixas `[x]` do `tasks.md` marcadas sem preencher o campo `Commit:` com hash, e sem confrontar o texto do commit com o texto da task. Custou a segunda metade desta seção de Discernimento — e, se a correção montar a matriz de auditoria de verdade, custará pontos de rastreabilidade que teriam sido grátis.

**Testes: quem escreveu, e como eu sei que testam a coisa certa?** O agente escreveu; a garantia vem de três travas: (a) os critérios de aceite em `tasks.md` nomeiam o teste esperado e o valor esperado (ex.: T-016 exige `total_reembolsado = 585,43`), então um teste verde com outro número não fecha a task; (b) os casos de aceite vêm da spec §7/§9, que eu revisei antes de a implementação começar; (c) a saída real da CLI (`saida.json`) foi comparada por mim com a tabela de casos de borda da spec — d-001..d-014 batem item a item. Ainda assim, teste escrito por quem escreveu o código é fraco contra erro de interpretação; o que me protegeu foi que os números vieram da minha especificação, não da cabeça do agente.

---

## O envelope

*A mudança de requisito do Dia 2.*

**Fato honesto: o envelope não chegou nesta execução.** Todas as quatro sessões exportadas (`docs/sessions/01`..`04`) são de 2026-10-14; o `DECISIONS.md` contém apenas D-001 e D-002, ambas do dia da especificação; e a "Fase 5 — Envelope" em `tasks.md` permanece vazia. Em vez de fingir uma absorção, documento:

**Quantos arquivos toquei na mão:** 0 (não houve mudança a absorver)
**Quanto tempo levou:** —
**Diff de absorção:** —

**O que eu observei sobre preparação, com evidência:**

- A spec já carrega no §10 um palpite explícito sobre o envelope: "a interpretação de hospedagem multi-diária é suspeita de voltar no envelope lacrado do dia 2" — escrito antes de saber o conteúdo. Se o envelope fosse "hospedagem passa a ter campo `diarias`", a mudança seria: spec (RN-007/AMB-010), `Politica`, um método no motor, testes novos — tasks T-018+ em continuação de numeração, sem reescrever passado.
- **Absorveria de graça:** o gatilho de nota (isolado em `Politica.gatilhoNota`, `plan.md` §4) e o fator de viagem (RN-008) vivem em um único record de política; mudança de percentuais seria uma linha.
- **Resistiria:** o schema de entrada (AMB-005 adicionou `em_viagem` fora do formato original) mostra que qualquer campo novo exige mexer em ingestão + spec §4 + testes de ingestão — a fronteira de I/O é onde o custo se concentra, não no motor.

**Ordem em que faria:** spec → DECISIONS.md → tasks novas → testes → código. A regra 2 ("explicação no chat que não está na spec é bug de spec") faria disto obrigação, não etiqueta.

**Se eu tivesse escrito a spec original sabendo que hospedagem multi-diária voltaria:** eu teria modelado RN-007 já como "limite por diária × número de diárias, com número de diárias como campo explícito opcional (default 1)" — mantendo o comportamento atual como caso degenerado e evitando a decisão conservadora de ignorar a descrição, que hoje faz d-010 (2 diárias reais) ser cortada de forma discutível.

**O que a spec me poupou, em concreto:** sem envelope aplicado, a prova é indireta mas real: a D-002 (mudança de decisão sobre hospedagem *durante* a escrita da spec) custou "2 seções de spec.md, ~20 min" (`DECISIONS.md`). Se essa mudança tivesse chegado depois do código, teria custado refazer motor + testes. A spec absorveu a mudança quando ela ainda era barata — que é exatamente o experimento que o envelope mede.

---

## Fechamento

**Para qual tamanho de projeto isto valeu a pena?**
Projetos com regras de negócio ambíguas e múltiplas sessões de trabalho. A prova neste projeto: 13 ambiguidades registradas sobreviveram intactas entre 4 sessões de um agente stateless — quando o Kimi K3 "esqueceu" o contexto na sessão 03 (Caso 1), a spec estava lá para ser relida. SDD paga a si mesmo na segunda sessão.

**Para qual não valeria?**
Um script de uma sessão, uma regra só, sem fronteiras numéricas. Metade do esforço aqui foi documentar decisões que um CRUD trivial não tem.

**O que eu faria diferente:**
1. Preencher o hash de commit em cada task **na hora do commit** (o template já mandava; eu deixei para depois e "depois" virou o relatório).
2. Conferir texto-do-commit × texto-da-task antes de cada commit — o Caso 2 teria sido pego em segundos.
3. Rodar o JSON de exemplo *contra a política do RH* como primeiro teste manual, antes de delegar qualquer regra — foi isso que revelou d-003/d-004.

**A coisa mais desconfortável que aprendi sobre como eu trabalho com IA:**
Que o meu ponto fraco não é detectar alucinação de código — os testes pegam isso. É detectar **deriva de plano**: o agente entrega tudo verde, palavra por palavra convincente, e o recibo (task × commit × teste) não fecha — e eu só descubro quando vou escrever a prestação de contas. SDD não me protege do modelo errar; me protege de **eu não perceber** que ele errou — desde que eu de fato audite a trilha, e não apenas a exista.
