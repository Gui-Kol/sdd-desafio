# Relatório — Desafio SDD

**Aluno:** Guilherme Kolndorfer · **Repositório:** `Gui-Kol/sdd-desafio` · **Data:** 2026-10-22
**Ferramenta:** OpenCode (agente `build`) · **Modelos:** Dia 1 `moonshotai/kimi-k3` · Dia 2 `z-ai/glm-5.3`

> Isto não é redação. São **evidências**. Toda afirmação vem acompanhada de
> arquivo, hash de commit ou trecho de sessão exportada em `docs/sessions/`.
>
> Nota de método: o Dia 1 (especificação + T-001..T-017) foi executado com
> OpenCode + Kimi K3 (sessões 01–04, 14/10). O envelope do Dia 2 chegou em
> execução posterior (22/10, sessão 05) com o modelo GLM-5.3 — registrado no
> JSON do export (`"model": {"id": "z-ai/glm-5.3"}`). Detalhe de diligência: o
> cabeçalho redigido à mão na sessão 05 ainda diz `kimi-k3`; o dado correto é o
> do export. Aonde o template diz "Claude", leia-se "o agente".

---

## Delegação

*O que você fez, o que o agente fez, e por que dividi assim.*

**A divisão:**

| Atividade | Quem | Por quê |
|---|---|---|
| Identificar ambiguidades | Agente, com leitura dirigida por mim | Dia 1: pedi a leitura cruzada `DESAFIO.md` × `exemplos/despesas-exemplo.json` (sessão 01) → 13 AMBs. Dia 2: pedi a análise dos 4 arquivos do envelope contra a política v4 (sessão 05) → 9 AMBs novas (AMB-014..022) |
| Decidir as ambiguidades | Agente propôs, eu validei item a item | Dia 1: 13 decisões em `spec.md` §6 (D-001, commit `efaf9da`). Dia 2: 9 decisões em `spec.md` v2.0 §6 + racional decisão-a-decisão no D-003 (commit `a0d3144`) |
| Escrever a spec | Agente | v1.1 (Dia 1) e v2.0 (Dia 2): +2 RNs (RN-012/RN-013), §8 com passo de conversão, §9 com dourados numéricos dos envelopes |
| Desenhar a arquitetura | Agente, com decisão minha de stack | Mantive Java 17 do Dia 1; o Dia 2 não pediu nova stack — validou a existente (ver **O envelope**) |
| Implementar | Agente | Dia 1: T-001..T-017 (`f9f7d97`..`fc0ee73`). Dia 2: T-018..T-022 (`e07f904`..`877393d`), uma task = um commit, suíte verde como barreira de cada task |
| Escrever testes | Agente | Convenção `rnNNN_caso()`/`ambNNN_caso()` amarrando teste→regra; suíte 91 → 122 testes |
| Absorver o envelope | Agente executou, eu auditei por task | 5 tasks sequenciais; em cada uma eu exigia no prompt: "garanta que toda a suíte anterior continue passando" e conferia o contador subir (97→107→114→118→122) + `BUILD SUCCESS` |

**Onde deleguei e me arrependi:**

Mantenho o regret do Dia 1 (caixas `[x]` do `tasks.md` aceitas sem conferir
hash × texto do commit — ver Discernimento, Caso 2). No Dia 2 o equivalente:
aceitei a frase do D-003 *"T-016 permanece válido — todos os itens do exemplo
original são BRL sob o padrão"* sem re-executar o exemplo contra a política
v4. A frase é literalmente verdadeira (moeda), mas a inferência estava errada
— o exemplo roda com `CC-ENG-PLATAFORMA`, que **tem override na v4**. Custou
um susto de 10 minutos na abertura da T-019 e um fixture a mais (Caso 4,
abaixo). Barato, mas seria zero se a revisão da spec tivesse o critério
"re-executar os dourados contra a mudança antes de aprovar o documento".

**Onde não deleguei e deveria ter delegado:**

A checagem cruzada "exemplo do Dia 1 × política v4". Eu tinha as duas coisas
na tela quando revisei o D-003; delegar ao agente "processe
`despesas-exemplo.json` com `politica-v4.json` e me diga se os 585,43
sobrevivem" levaria um comando. Fiz a checagem só na T-019, quando o custo já
era contexto de implementação.

**Usei subagentes / skills / MCP / hooks?** Não — nos dois dias. A sessão 05
(do envelope) foi single-threaded com o agente principal. As "ferramentas"
do Dia 2 que valeram a pena não foram de plataforma: foram de **disciplina de
diagnóstico** (dump de codepoints, `javap`, um programa Java temporário de
verificação em runtime — tudo registrado na sessão 05). Subagentes teriam
fragmentado justamente o contexto que a spec de 514 linhas mantinha coeso.

---

## Descrição

*Como transformei requisito ambíguo em requisito verificável.*

Requisito escolhido: **a fronteira da nota fiscal** (política do RH, item 5) —
que ainda por cima evoluiu DUAS vezes, provando que a spec é artefato vivo.

**Versão 1 (redação literal da política do RH, v3):**
> ```
> 5. Nota fiscal é obrigatória acima de R$ 100.
> ```

**Versão 2 (spec.md v1.1, Dia 1 — requisito verificável):**
> ```
> RN-005 — Exigência de nota fiscal
> Regra: toda despesa individual com valor estritamente maior que R$ 100,00
> exige nota fiscal. [...] Despesa de exatamente R$ 100,00 (ou menos) não
> exige nota.
> Aceite: R$ 100,00 sem nota → elegível; R$ 100,01 sem nota → recusada.
> ```

**Versão final (spec.md v2.0, Dia 2 — o gatilho vira dado, o valor vira BRL):**
> ```
> RN-005 — Exigência de nota fiscal
> Regra: toda despesa individual com valor em BRL (RN-013) estritamente maior
> que o gatilho nota_fiscal_obrigatoria_acima_de da política (R$ 100,00 na
> v4) exige nota fiscal. [...]
> Aceite: R$ 100,00 sem nota → elegível; R$ 100,01 sem nota → recusada;
> 40 USD sem nota (R$ 220,00 após conversão) → recusada por nota.
> ```

**O que estava ambíguo:** três coisas numa frase de sete palavras — (a) "acima
de" inclui R$ 100,00 exato?; (b) o gatilho é por item ou pela soma do dia?;
(c) com moeda estrangeira, dispara sobre o valor original ou o convertido?

**Como percebi:** (a) e (b) no Dia 1, executando mentalmente o JSON de exemplo
— d-003 (R$ 100,00 sem nota) e d-004 (R$ 100,01 sem nota) plantados lado a
lado na fronteira. (c) no Dia 2, sem rodar nada: o próprio envelope planta a
armadilha — e-005 são "40 USD", que parecem R$ 40 e viram R$ 220,00. Se o
gatilho testasse a moeda original, a exigência de nota seria burlável
lançando em dólar; a decisão AMB-019 ("converte primeiro, decide depois")
mata isso por escrito antes de existir código.

**Commits da mudança:** `efaf9da` (spec v1.1, D-001) · `a0d3144` (spec v2.0 +
D-003, AMB-019) · `9f8d169` (T-020: `amb019_gatilhoNotaSobreValorConvertido`
afirma 40 USD = R$ 220,00 sem nota → recusada).

---

## Discernimento

*Onde o agente errou e você pegou.*

### Caso 1 — O agente pediu a spec de volta em vez de ler o arquivo (Dia 1)

**O que ele propôs:** ao abrir a sessão 03 para a T-009, o Kimi K3 pediu a mim
o conteúdo relevante — o título auto-gerado da sessão é literalmente
"Para implementar a tarefa T-009 corretamente, preciso que você compartilhe
o conteúdo relevante d...".

**Por que estava errado:** a spec está no repositório, a um `read` de
distância; agente que pede a spec no chat está tratando a conversa como fonte
— a falha que a regra 2 do desafio proíbe.

**Como eu detectei:** no título da sessão exportada e no fluxo — em vez de
chamadas `read`, sinalização de dependência do meu input.

**O que eu fiz:** não colei conteúdo; apontei os arquivos e exigi a leitura
prévia. Os logs mostram os `read` em seguida.

**Onde está a evidência:** `docs/sessions/03-fase3-limites-e-casos-de-borda.md`
— título no cabeçalho; `read` de `tasks.md` (~linha 107) e `spec.md`
(~linha 3826).

### Caso 2 — As tasks escritas não são as tasks commitadas (Dia 1)

**O que ele propôs:** T-004..T-008 planejadas por regra (RN-001/002, RN-003,
RN-004, RN-009, RN-005 — commit `4e9cb09`); os commits saíram por tema:
`feat(T-004): regras de alimentacao`, `feat(T-005): regras de transporte`,
`feat(T-006): regras de hospedagem`... (`fea0eb0`..`e6744b9`).

**Por que estava errado:** "nenhum commit sem task" só funciona se o ID no
commit aponta para o escopo real do diff. Um corretor auditando T-006
("duplicatas") encontra um commit que diz "hospedagem".

**Como eu detectei:** só ao escrever o primeiro relatório, cruzando `git log
--oneline` com `tasks.md` linha a linha. A verificação que era para ser por
task virou verificação de fim de projeto.

**O que eu fiz:** registrei o desvio em vez de maquiar. **No Dia 2 o padrão
não se repetiu**: os cinco commits da Fase 5 (`e07f904`..`877393d`) casam
task × escopo × teste — a lição do Dia 1 foi absorvida na prática.

**Onde está a evidência:** `git log --format="%h %s" e07f904^..877393d` —
cada mensagem cita a task e o conteúdo correspondente em `tasks.md` Fase 5.

### Caso 3 — O teste com `List.contains` e a hipótese errada de encoding (Dia 2)

**O que ele propôs:** na T-019, o agente escreveu o teste
`rn012_limiteZero_naoReembolsavel` com
`r.motivos().contains("não reembolsável para o centro de custo")` — needle
**substring** do motivo `"Categoria não reembolsável para o centro de
custo"`. O teste falhou (16:50). Aí o erro compôs: diante da falha, o agente
formulou a hipótese de **problema de encoding Unicode** e gastou várias
ferramentas nela — dump de codepoints dos fontes, busca por bytes NFC no pool
de constantes dos `.class`, `javap` do bytecode, hipóteses de JAR obsoleto e
de compilação com encoding trocado.

**Por que estava errado (duas vezes):** (1) `List.contains` verifica
**igualdade de elementos**, não substring — o bug estava no próprio teste do
agente, não no motor; os outros testes passavam porque usavam a string
completa. (2) A hipótese de encoding era falsa e *inverificável pelos meios
que ele usava primeiro* — dumps estáticos de arquivos que a evidência já
tinha eliminado. O que fechou o caso foi um experimento em runtime.

**Como eu detectei:** a suíte vermelha era barreira de aceite da task (meu
prompt em cada task: "garanta que toda a suíte anterior continue passando") —
o bug não podia passar despercebido. O ponto em que eu interfiri foi o
critério de parada do diagnóstico: em vez de aceitar mais uma teoria, exigir
**evidência em runtime** — o agente adicionou os codepoints da needle e do
motivo à própria mensagem de asserção e re-executou. O resultado foi o
smoking gun:

```
NEEDLE_CPS=006E 00E3 006F 0020 0072 ... (= "não reembolsável..." — NFC perfeito)
MOTIVO_CPS=0043 0061 0074 ... 006E 00E3 006F ... (= "Categoria não..." — NFC perfeito)
==> expected: <true> but was: <false>
```

Duas strings idênticas e limpas — e `contains` falso. Nesse ponto a única
explicação possível era semântica, não bytes: `List.contains` ≠
`String.contains`.

**O que eu fiz:** aceitei a correção **na minha convenção**: comparar contra
a constante simbólica
(`MotorReembolso.MOTIVO_CATEGORIA_NAO_REEMBOLSAVEL`), como fazem os testes
vizinhos — e não consertar a substring. Custo total do episódio: ~1h10 entre
a primeira falha (16:50) e o verde (18:01), dentro da T-019 (commit `19faa06`).

**Onde está a evidência:** `docs/sessions/05-envelope-lacrado-dia2.md` — a
sequência completa do diagnóstico; o smoking gun está no trecho
`NEEDLE_CPS=...| MOTIVO_CPS=...` (resultado `mvn -q clean test` reproduzido na
sessão, ~linha 8395), e o patch da correção (~linha 8471). Relatório surefire
correspondente: `target/surefire-reports/` (run das 16:50).

### Caso 4 — A alegação sutilmente falsa do D-003 sobre o dourado (Dia 2)

**O que ele propôs:** ao redigir o D-003 (commit `a0d3144`), o agente escreveu:
*"T-016 (E2E dourado do exemplo base) permanece válido — todos os itens do
exemplo original são BRL sob o `padrao`."*

**Por que estava errado:** verdade sobre a moeda, inferência falsa. O exemplo
do Dia 1 roda com `centro_custo = CC-ENG-PLATAFORMA` — e a política v4 traz
**override para esse CC** (alimentação 75,00/dia; hospedagem 0,00 "nao
reembolsavel"). Processar o exemplo com a v4 mudaria os números dourados
(d-010 deixaria de ser parcial 250,00 e viraria recusada; d-001 seria cortada
em 75,00, não 60,00; o total 585,43 não existiria). A spec, escrita pelo
agente na mesma manhã, continha uma afirmação que a spec mesma refutava.

**Como eu detectei:** na abertura da T-019, antes de aprovar o plano de
implementação — cruzei o CC do exemplo (`IngestaoTest`, linha 32) com o bloco
`centros_custo` da `politica-v4.json`. O plano que o agente apresentou já
trazia a constatação e a solução; eu validei o raciocínio (d-010: 480,00 de
hospedagem com limite 0,00 → recusada) antes de liberar a implementação.

**O que eu fiz:** mantive o dourado do Dia 1 como regressão sob a **v3
codificada como dado** (fixture `tests/com/reembolso/PoliticaV3.java`, commit
`19faa06`), com javadoc explicando o porquê — e o E2E do Dia 1 segue verde
(585,43) ao lado do E2E do Dia 2 (2457,52/1143,26). Registro honesto: a
correção formal do texto do D-003 ficou devendo; a nuance vive no fixture,
nos javadocs e aqui.

**Onde está a evidência:** `specs/001-motor-reembolso/DECISIONS.md` (D-003,
tasks afetadas) × `exemplos/politica-v4.json` (bloco `CC-ENG-PLATAFORMA`) ×
`tests/com/reembolso/PoliticaV3.java` (javadoc: "a política v4 real traz
override para o CC-ENG-PLATAFORMA... o que mudaria os números dourados").

**Padrão que eu notei:** os erros **mudaram de natureza entre os dias**. No
Dia 1 foram falhas de *continuidade*: agente que não lia os arquivos, deriva
entre texto de task e commit. No Dia 2, nenhum erro de **regra de negócio**
vingou — a spec v2.0 segurou todas as decisões antes do código; os erros
foram no próprio **código de verificação** (o teste do Caso 3) e em
**afirmações meta** sobre a spec (o Caso 4). O sinal recorrente que me deixou
em alerta: resposta que teoriza antes de medir. A contramedida que funcionou:
exigir evidência executável (runtime, não dump estático) e, para afirmações
sobre comportamento, a pergunta "que teste verde ou vermelho prova isso?".

---

## Diligência

*O que você verifiquei antes de aceitar.*

**Meu procedimento de verificação (Dia 2):** por task, (1) o agente rodava
`mvn test` e eu conferia três coisas no resumo: zero falhas, o **contador de
testes** subir o número previsto pela task (97→107→114→118→122) e
`BUILD SUCCESS` — um teste novo que não subisse o contador seria suspeito;
(2) conferia que cada critério de aceite da task tinha um teste com o nome
prometido (`rn012_ccDesconhecido_resolvePadrao`, `amb019_...`) — a matriz de
cobertura em `tasks.md` fecheia sem abrir o código; (3) os números dourados
eram **meus**, não do agente: 2457,52/1143,26 e 623,76/373,76 estavam
escritos na spec §9 (que eu revisei de manhã) antes de qualquer
implementação; (4) o E2E do Dia 1 (585,43) tinha que continuar verde em todas
as cinco tasks — meu canário de regressão.

**Li o diff inteiro em que porcentagem das entregas?** No Dia 2, mais do que
no Dia 1: li por completo os diffs do motor (`MotorReembolso`, `Politica`,
`ResultadoDespesa`) e da materialização (`Saida`, `EscritorSaida`) — onde uma
regra podia vazar. Passei o olho, sem linha a linha, nos diffs de
`Ingestao.java` (+213 linhas de parsing campo a campo) e dos testes — e foi
justamente num teste que o Caso 3 estourou. A lição: "verde" não é
verificação; a verificação que pegou o bug foi a **barreira de aceite**
(suíte inteira) e a revisão da *correção*, não a revisão *prévia* do teste.

**O que aceitei sem verificar direito, e o que me custou:** (1) a frase do
D-003 sobre o dourado (Caso 4) — custou o fixture `PoliticaV3` e atraso de
contexto na T-019; (2) o cabeçalho manual da sessão 05 (diz `kimi-k3`; o
export registra `z-ai/glm-5.3`) — corrigido no cabeçalho deste relatório;
custo zero além do próprio relatório perceber. (3) No fechamento da T-021,
aceitei o resumo do agente afirmando que `tasks.md` modificado era "ruído do
usuário" — era: minhas marcações `[x]`. Dessa vez a confiança foi certa,
mas o padrão "aceitar a explicação sem diff" é o mesmo que me custou caro no
Dia 1.

**Testes: quem escreveu, e como eu sei que eles testam a coisa certa?** O
agente escreveu código e testes. As travas: (a) os critérios de aceite em
`tasks.md` nomeiam teste E valor esperado antes da implementação; (b) os
valores vêm da spec §9, que eu escrevi/revi; (c) dois E2E dourados
independentes em execução permanente (Dia 1: 14 despesas, 585,43; Dia 2:
14 despesas nos dois envelopes, 2457,52/1143,26 e 623,76/373,76) — um teste
verde com número errado não fecha a task; (d) o Caso 3 mostra o limite:
teste escrito por quem escreveu o código pode conter bug do próprio autor —
o que salvou foi a suíte-como-barreira + o diagnóstico por evidência. A
convenção `rnNNN_`/`ambNNN_` tornou a auditoria barata: qualquer nome de
teste sem ID de regra é cheiro de teste órfão.

---

## O envelope

*A mudança de requisito do Dia 2.*

**O que chegou** (commit `3378ce2`, 22/10 11:21, +213 linhas de dados):
`politica-v4.json` (limites por centro de custo, categoria `representacao`,
gatilho/acréscimo configuráveis, `vigencia`), `despesas-envelope.json`
(CC-COMERCIAL, despesas em EUR/USD/GBP, campo `moeda` às vezes ausente),
`despesas-envelope-cc-desconhecido.json` (CC fora da política) e
`cambio.json` (PTAX só em dias úteis, cobrindo só USD e EUR — sem GBP).

**Quantos arquivos toquei na mão:** **24** — 11 em `src/main` (2 novos:
`io/PoliticaV4.java`, `io/Cambio.java`; 9 modificados), 8 em `tests/` (2
novos: `PoliticaV3.java`, `EnvelopeE2ETest.java`; 6 modificados), 5 de
documentação (`spec.md`, `DECISIONS.md`, `tasks.md`, `README.md`,
`CLAUDE.md`). Os 4 arquivos de dados do envelope chegaram prontos.
**Quanto tempo levou:** ~9h de relógio (dados 11:21 → último feat 20:06),
sendo ~3h de análise/decisão antes da primeira linha de código (spec v2.0 +
D-003 + Fase 5: `a0d3144`, `45408fc`) e ~1h10 consumidos pelo episódio do
Caso 3.
**Diff de absorção:** 29 arquivos, +2400/−390 linhas
(`git diff f2955ac..877393d --stat`; os 29 incluem os 4 dados recebidos e o
ruído da IDE).

**Absorveu de graça** — o que a arquitetura já suportava:

- **`dominio/Dinheiro` — zero mudanças.** A conversão cambial (valor × taxa,
  HALF_UP 2 casas) só *consumiu* o tipo que existia desde a T-002. A decisão
  mais importante do Dia 1 (BigDecimal scale 2 em vez de `double`) pagou o
  envelope inteiro sem tocar numa linha.
- **A ordem do pipeline (spec §8).** O envelope *inseriu* o passo 4
  (conversão) exatamente entre período e duplicata — onde a spec v2.0 disse
  que ele estaria. Nenhum passo foi reordenado; as consequências da ordem
  ("recusa mais fundamental primeiro"; "item sem cotação não gasta cota nem
  entra em totais") estavam escritas antes de existirem em código.
- **O E2E dourado do Dia 1** — os 585,43 continuam verdes, item a item,
  intactos (sob o fixture v3-como-dado).
- **A suíte anterior inteira**: 91 testes do Dia 1 sobreviveram todos;
  nenhum foi reescrito semanticamente — só adaptações de assinatura
  (aridade de `DespesaIn`, parâmetro de política).
- **`Saida.de(entrada, resultados)`**: a assinatura da materialização segurou
  a multi-moeda; `Main` e o E2E do Dia 1 não mudaram na T-021.

**Resistiu** — o que teve que quebrar e por quê:

- **O catálogo fechado de categorias.** `Categoria` era um `enum` com 3
  valores — a codificação viva da RN-001 v1.1. A política v4 tornou o
  catálogo *dado* (`representacao` só existe para um CC); o enum virou value
  type de nome normalizado e "pertencer à política" mudou para a RN-012. A
  única peça de domínio genuinamente quebrada.
- **A "interface fixa" da CLI.** O `plan.md` (DT-005) dizia "a assinatura é
  fixa e tem exatos 2 flags". O envelope transformou política em entrada
  obrigatória e câmbio em opcional — a suposição mais errada do Dia 1.
  Custou uma ponte transitória (v3-como-dado dentro do `Main` na T-019,
  removida na T-022 conforme registrado no D-003).
- **Os valores literais nas regras.** "60/80/250/100/50%" escritos na spec
  v1.1 e como constantes no código — o D-003 os rebaixou a dados; o custo
  concentrou-se na T-019, como previsto.

**Ordem em que fiz:** spec → `DECISIONS.md` → tasks → código, sem desvio:
`a0d3144` (14:08) e `45408fc` (14:27) precedem `e07f904` (15:23). Nenhuma
decisão de negócio foi tomada dentro de código ou chat — a regra 2 foi
obrigação, não etiqueta.

**Se eu tivesse escrito a spec original sabendo desta mudança:** (1) limites,
gatilho e acréscimo como **dado de arquivo desde o Dia 1** — nunca
constantes, nunca valores literais no corpo das RNs (a v1.1 os embutia); (2)
o campo `moeda` previsto na spec §4 original (teria poupado a mudança de
aridade em ~15 call sites de teste); (3) nada de "exatos 2 flags" no
`plan.md` — interface de CLI é a parte mais provável de mudar; (4) o critério
"re-executar os dourados contra qualquer mudança de spec antes de aprovar o
documento" como passo fixo de revisão.

**O que a spec me poupou, em concreto:** as nove ambiguidades do envelope
(AMB-014..022) foram identificadas, decididas e justificadas **antes** de
qualquer código — quando a T-019 abriu, perguntas como "CC sem bloco resolve
pelo padrão?", "limite 0,00 é proibição ou corte?", "taxa de qual data?",
"GBP sem cotação: derruba o lote?" já tinham resposta com ID, justificativa e
caso de teste. A implementação foi *execução de decisões escritas*: em cinco
tasks, nenhuma decisão de negócio surgiu no meio do código. E o palpite do
§10 da v1.1 ("a resposta provável virá com o envelope") acertou a direção e
errou o conteúdo, do jeito mais instrutivo: o envelope trouxe hospedagem
multi-diária (e-007, "3 noites") e a resposta **confirmou** a D-002 — a
política v4 declara `periodicidade: "diaria"`; a decisão conservadora não foi
desfeita, foi ratificada por escrito.

**Como a arquitetura reagiu, no mapa:** o custo caiu exatamente onde o
`plan.md` §2 disse que estaria. `io` cresceu para os novos dados
(`PoliticaV4`/`Cambio`, ingestão +213 linhas — fronteira de I/O); `motor`
cresceu onde a regra mora (`Politica` de 68 linhas de constantes para o
resolver RN-012; `MotorReembolso` ganhou o passo 4 e a semântica de
periodicidade); `materializacao` só **estendeu** (campos multi-moeda);
`dominio` quase intocado (só `Categoria`); `cli` só no fim (T-022). As
fronteiras por pacote impediram que a mudança virasse refactory geral: 24
arquivos num projeto de 16 arquivos principais + 11 de teste é "cada
fronteira tocou o seu pedaço", não "reescrita".

---

## Fechamento

**Para qual tamanho de projeto isto valeu a pena?**
A resposta agora é empírica, não hipotética: o envelope mudou limites por
centro de custo, catálogo, moeda, câmbio, vigência e interface de CLI — e a
absorção foi 2 commits de documento, 1 commit de tasks e 5 commits de
implementação, sem que um único teste do Dia 1 fosse reescrito. A spec v2.0
(+284 linhas) e o D-003 foram escritos em ~3h; sem elas, essas mesmas
decisões teriam sido tomadas dentro de código, sem rastreabilidade e sob
conflito de interpretação. SDD paga a si mesmo na primeira mudança de
requisito — foi o que o desafio prometeu e o que o envelope entregou.

**Para qual não valeria?**
Um script de uma sessão sem fronteiras numéricas nem mudança prevista. O Dia
1 já tinha mostrado o custo fixo (13 AMBs documentadas para um motor de uma
tela); o Dia 2 mostrou o retorno. Quem não espera mudança paga o fixo sem
receber o retorno.

**O que eu faria diferente:**
1. Limites como dado desde o Dia 1 (ver O envelope — item 1 do
   "se soubesse").
2. Critério fixo de revisão de spec: **toda mudança de spec re-executa os
   dourados antes de ser aprovada** — teria pego o Caso 4 em 14:08 em vez
   de 16:34.
3. Em diagnóstico, a regra que o Caso 3 me ensinou: nenhuma segunda hipótese
   antes da primeira evidência executável. O agente teoriza bem e rápido —
   exatamente por isso a resposta padrão a um vermelho deveria ser "meça de
   novo em runtime", não "deixe eu listar causas possíveis".
4. Hash de commit no `tasks.md` na hora do commit (dívida do Dia 1 que o
   Dia 2 pagou corretamente na Fase 5 — mantido como hábito).

**A coisa mais desconfortável que aprendi sobre como eu trabalho com IA:**
No Dia 1 aprendi que meu ponto fraco é detectar **deriva de plano** quando
tudo sai verde. O Dia 2 adicionou a volta da chave: o episódio mais caro não
foi o agente errar — foi o agente errar *no teste que deveria me proteger* e
eu quase acompanhar a hipótese errada dele por cortesia de diagnóstico
bem-escrito. A IA que trabalha comigo não me protege dos meus critérios
frouxos; ela os **amplifica com fluência**. A única defesa que funcionou foi
transformar critério em barreira mecânica: suíte inteira verde, contador de
testes conferido, dourados numéricos vindos de mim, e "mostre o runtime"
antes de "explique a teoria".
