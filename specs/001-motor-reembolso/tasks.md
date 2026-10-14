# Tasks — Motor de Cálculo de Reembolso

> Cada task é pequena o bastante para virar **um commit**. Se você não consegue
> descrever o critério de aceite como "o teste X passa", a task está grande demais.
>
> Marque `[x]` conforme conclui — ao longo do caminho, não tudo no fim. O histórico
> de quando cada task foi marcada é lido na correção.

**Formato do commit:** `feat(T-003): <descrição>` · `test(T-003): <descrição>`

---

## Fase 1 — Fundação

- [x] **T-001** — Scaffold Maven + JDK 17 + JUnit 5 funcionando
  - **Atende:** base das demais
  - **Aceite:** `mvn -q test` executa um teste-trivial verde; `pom.xml` declara Java 17, JUnit 5 e Jackson; estrutura `src/main/java/com/reembolso/{cli,io,dominio,motor,materializacao}` e `tests/` criadas.
  - **Commit:** `<hash>`

- [x] **T-002** — Tipo monetário `Dinheiro` (BigDecimal, scale 2, HALF_UP)
  - **Atende:** RN-010, AMB-011 (implementação; DT-001)
  - **Aceite:** testes `DinheiroTest`: `de("33.333")` → `33.33`; `de("0.005")` → `0.01`; adicionar/subtrair/comparar com precisão; `ZERO` existe; imutabilidade.
  - **Commit:**

- [x] **T-003** — Modelos de entrada + leitura de JSON (io/ingestao)
  - **Atende:** spec §4 entrada; AMB-005 (default de `em_viagem`)
  - **Aceite:** teste `IngestaoTest`: lê `exemplos/despesas-exemplo.json` em memória; `periodo.em_viagem` ausente → `false`; `valor` preservado como string no record; erro legível em JSON malformado.
  - **Commit:**

## Fase 2 — Regras de negócio (pipeline do motor, na ordem da spec §8)

- [x] **T-004** — RN-001 + RN-002: normalização de categoria e recusa de categoria fora da política
  - **Atende:** RN-001, RN-002, AMB-009
  - **Aceite:** testes `MotorReembolsoTest.rn001_caseInsensitive_alimentacaoReconhecida()` (entrada `"ALIMENTACAO"` ≡ alimentacao) e `rn002_coworking_recusada()` (d-005 análoga recusada com motivo "Categoria fora da política").
  - **Commit:**

- [ ] **T-005** — RN-003: período de competência (inclusive nas pontas)
  - **Atende:** RN-003, AMB-007
  - **Aceite:** testes `rn003_despesaAbrilEmJulho_recusada()` (d-008) e `rn003_dataNoUltimoDia_elegivel()` (2026-07-31 passa).
  - **Commit:**

- [ ] **T-006** — RN-004: duplicatas (mesma data+categoria+fornecedor+valor, tolerância R$ 0,01)
  - **Atende:** RN-004, AMB-006, DT-002
  - **Aceite:** testes `rn004_duplicataExata_segundaRecusada()` (d-006 elegível, d-007 recusada citando `d-006`), `rn004_valorDiferente_naoDuplica()`, `rn004_chaveUsaValorArredondado()` (33,333 vs 33,33 = duplicata).
  - **Commit:**

- [ ] **T-007** — RN-009: estornos (valor negativo)
  - **Atende:** RN-009, AMB-008, DT-004
  - **Aceite:** testes `rn009_estornoSozinho_diaZeradoSemCredito()` (d-009 recusada como estorno; categoria/dia = 0) e `rn009_estornoAbateSomaDoDia()` (90 − 40 = 50 elegível).
  - **Commit:**

- [ ] **T-008** — RN-005: exigência de nota fiscal (fronteira estrita)
  - **Atende:** RN-005, AMB-003, AMB-004
  - **Aceite:** testes `rn005_fronteiraCemReaisSemNota_naoExige()` (d-003 passa), `rn005_cemVirgulaUmSemNota_recusa()` (d-004 recusada), `rn005_hospedagem690SemNota_recusaPorNota()` (d-013 morre por nota, não por teto — ordem §8).
  - **Commit:**

## Fase 3 — Limites, agregação e casos de borda

- [ ] **T-009** — RN-008: objeto `Politica` + fator de viagem (+50%)
  - **Atende:** RN-008, AMB-005
  - **Aceite:** testes `PoliticaTest.limite_emViagem_amplia50porCento()` (60→90, 80→120, 250→375) e `PoliticaTest.gatilhoNota_emViagem_naoAmplia()`.
  - **Commit:**

- [ ] **T-010** — RN-006: agregação diária alimentação/transporte, corte em ordem de entrada
  - **Atende:** RN-006, AMB-001, AMB-002, AMB-012, DT-003
  - **Aceite:** testes `rn006_duasAlimentacoesMesmoDia_cortaExcedenteEmOrdem()` (72,50 + 38,00 → primeira 60,00; segunda 0,00), `rn006_recusadaNaoGastaCota()` (d-004 recusada não reduz cota do d-003), `rn006_statusParcialQuandoCortado()`.
  - **Commit:**

- [ ] **T-011** — RN-007: hospedagem limitada por lançamento
  - **Atende:** RN-007, AMB-010
  - **Aceite:** testes `rn007_hospedagem480_cortaEm250()` (d-010 parcial) e `rn007_hospedagemNaoAgregaPorDia()` (dois lançamentos de hospedagem não dividem cota).
  - **Commit:**

- [ ] **T-012** — RN-011 + RN-010 na materialização: motivos obrigatórios e saída arredondada
  - **Atende:** RN-010, RN-011
  - **Aceite:** testes `rn011_nenhumMotivoVazio()` (todas as 14 despesas do exemplo saem com motivo) e `rn010_saidaDuasCasasDecimais()` (33,33 na saída do d-011; totais com 2 casas).
  - **Commit:**

- [ ] **T-013** — Cobertura dos casos de borda restantes da spec §7 (tabela)
  - **Atende:** AMB-013 e linhas não cobertas acima
  - **Aceite:** testes `amb013_sabadoPlantao_elegivel()` (d-012) e o caso "estorno sobre cota já cortada" da tabela §7; cada linha da tabela mapeia para um teste com RN/AMB no nome.
  - **Commit:**

## Fase 4 — Saída e CLI

- [ ] **T-014** — Materializar `Saida` e escrever JSON de saída (io/saida)
  - **Atende:** spec §4 saída
  - **Aceite:** teste de integração `SaidaTest`: entrada pequena → JSON com `colaborador_id`, `competencia`, `total_solicitado`, `total_reembolsado`, `despesas[]` com os 5 campos; números serializados com 2 casas.
  - **Commit:**

- [ ] **T-015** — CLI `calcular --input --output` (args à mão, DT-005)
  - **Atende:** interface fixa do enunciado
  - **Aceite:** teste ponta a ponta `CliE2ETest`: roda binário/wrapper com `--input exemplos/despesas-exemplo.json --output out.json`; arquivo criado; código de saída 0; uso errado → código 2 com ajuda.
  - **Commit:**

- [ ] **T-016** — E2E dourado: totais esperados do exemplo
  - **Atende:** spec §9 critérios de aceite
  - **Aceite:** `ExemploE2ETest` processa o arquivo exemplo e afirma o estado de cada despesa — d-001 parcial 60,00; d-002 parcial 0,00; d-003 parcial 80,00; d-004 recusada; d-005 recusada; d-006 aprovada 54,90; d-007 recusada duplicata; d-008 recusada período; d-009 recusada estorno; d-010 parcial 250,00; d-011 aprovada 33,33; d-012 aprovada 47,20; d-013 recusada nota; d-014 parcial 60,00 — e `total_reembolsado = 585,43`.
  - **Commit:**

- [ ] **T-017** — README.md (como rodar e como testar) + CLAUDE.md (convenções)
  - **Atende:** estrutura de entrega do desafio
  - **Aceite:** leigo consegue `mvn test` e rodar a CLI seguindo só o README; CLAUDE.md lista os pactos (arquivos de spec, formato de commit, proibição de regra de negócio fora de spec).
  - **Commit:**

---

## Fase 5 — Envelope (criar no Dia 2)

<Novas tasks a partir da mudança de requisito. Numeração continua de onde parou —
não reinicie e não renumere as antigas: a numeração é o eixo da rastreabilidade.
Ponto de atenção já sinalizado na spec §10: hospedagem multi-diária.>

---

## Cobertura

Preencha ao fechar cada fase. É a sua própria checagem de rastreabilidade — e é
exatamente a matriz que a correção vai montar.

| Regra da spec | Task | Teste |
|---|---|---|
| RN-001 | T-004 | `rn001_caseInsensitive_alimentacaoReconhecida` |
| RN-002 | T-004 | `rn002_coworking_recusada` |
| RN-003 | T-005 | `rn003_despesaAbrilEmJulho_recusada`, `rn003_dataNoUltimoDia_elegivel` |
| RN-004 | T-006 | `rn004_duplicataExata_segundaRecusada`, `rn004_valorDiferente_naoDuplica`, `rn004_chaveUsaValorArredondado` |
| RN-005 | T-008 | `rn005_fronteiraCemReaisSemNota_naoExige`, `rn005_cemVirgulaUmSemNota_recusa`, `rn005_hospedagem690SemNota_recusaPorNota` |
| RN-006 | T-010 | `rn006_duasAlimentacoesMesmoDia_cortaExcedenteEmOrdem`, `rn006_recusadaNaoGastaCota`, `rn006_statusParcialQuandoCortado` |
| RN-007 | T-011 | `rn007_hospedagem480_cortaEm250`, `rn007_hospedagemNaoAgregaPorDia` |
| RN-008 | T-009 | `PoliticaTest.limite_emViagem_amplia50porCento`, `gatilhoNota_emViagem_naoAmplia` |
| RN-009 | T-007 | `rn009_estornoSozinho_diaZeradoSemCredito`, `rn009_estornoAbateSomaDoDia` |
| RN-010 | T-002, T-012 | `DinheiroTest`, `rn010_saidaDuasCasasDecimais` |
| RN-011 | T-012 | `rn011_nenhumMotivoVazio` |
| AMB-001 | T-010 | `rn006_duasAlimentacoesMesmoDia_cortaExcedenteEmOrdem` |
| AMB-002 | T-010 | `rn006_statusParcialQuandoCortado` |
| AMB-003 | T-008 | `rn005_fronteiraCemReaisSemNota_naoExige` |
| AMB-004 | T-008 | `rn005_cemVirgulaUmSemNota_recusa` |
| AMB-005 | T-003, T-009 | `IngestaoTest.emViagem_ausente_defaultFalse`, `gatilhoNota_emViagem_naoAmplia` |
| AMB-006 | T-006 | `rn004_duplicataExata_segundaRecusada` |
| AMB-007 | T-005 | `rn003_despesaAbrilEmJulho_recusada` |
| AMB-008 | T-007 | `rn009_estornoSozinho_diaZeradoSemCredito` |
| AMB-009 | T-004 | `rn001_caseInsensitive_alimentacaoReconhecida` |
| AMB-010 | T-011 | `rn007_hospedagem480_cortaEm250` |
| AMB-011 | T-002 | `DinheiroTest.de_33333_vira_3333` |
| AMB-012 | T-010 | `rn006_recusadaNaoGastaCota` |
| AMB-013 | T-013 | `amb013_sabadoPlantao_elegivel` |
