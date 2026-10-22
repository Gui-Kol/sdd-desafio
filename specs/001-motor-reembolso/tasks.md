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

- [x] **T-005** — RN-003: período de competência (inclusive nas pontas)
  - **Atende:** RN-003, AMB-007
  - **Aceite:** testes `rn003_despesaAbrilEmJulho_recusada()` (d-008) e `rn003_dataNoUltimoDia_elegivel()` (2026-07-31 passa).
  - **Commit:**

- [x] **T-006** — RN-004: duplicatas (mesma data+categoria+fornecedor+valor, tolerância R$ 0,01)
  - **Atende:** RN-004, AMB-006, DT-002
  - **Aceite:** testes `rn004_duplicataExata_segundaRecusada()` (d-006 elegível, d-007 recusada citando `d-006`), `rn004_valorDiferente_naoDuplica()`, `rn004_chaveUsaValorArredondado()` (33,333 vs 33,33 = duplicata).
  - **Commit:**

- [x] **T-007** — RN-009: estornos (valor negativo)
  - **Atende:** RN-009, AMB-008, DT-004
  - **Aceite:** testes `rn009_estornoSozinho_diaZeradoSemCredito()` (d-009 recusada como estorno; categoria/dia = 0) e `rn009_estornoAbateSomaDoDia()` (90 − 40 = 50 elegível).
  - **Commit:**

- [x] **T-008** — RN-005: exigência de nota fiscal (fronteira estrita)
  - **Atende:** RN-005, AMB-003, AMB-004
  - **Aceite:** testes `rn005_fronteiraCemReaisSemNota_naoExige()` (d-003 passa), `rn005_cemVirgulaUmSemNota_recusa()` (d-004 recusada), `rn005_hospedagem690SemNota_recusaPorNota()` (d-013 morre por nota, não por teto — ordem §8).
  - **Commit:**

## Fase 3 — Limites, agregação e casos de borda

- [x] **T-009** — RN-008: objeto `Politica` + fator de viagem (+50%)
  - **Atende:** RN-008, AMB-005
  - **Aceite:** testes `PoliticaTest.limite_emViagem_amplia50porCento()` (60→90, 80→120, 250→375) e `PoliticaTest.gatilhoNota_emViagem_naoAmplia()`.
  - **Commit:**

- [x] **T-010** — RN-006: agregação diária alimentação/transporte, corte em ordem de entrada
  - **Atende:** RN-006, AMB-001, AMB-002, AMB-012, DT-003
  - **Aceite:** testes `rn006_duasAlimentacoesMesmoDia_cortaExcedenteEmOrdem()` (72,50 + 38,00 → primeira 60,00; segunda 0,00), `rn006_recusadaNaoGastaCota()` (d-004 recusada não reduz cota do d-003), `rn006_statusParcialQuandoCortado()`.
  - **Commit:**

- [x] **T-011** — RN-007: hospedagem limitada por lançamento
  - **Atende:** RN-007, AMB-010
  - **Aceite:** testes `rn007_hospedagem480_cortaEm250()` (d-010 parcial) e `rn007_hospedagemNaoAgregaPorDia()` (dois lançamentos de hospedagem não dividem cota).
  - **Commit:**

- [x] **T-012** — RN-011 + RN-010 na materialização: motivos obrigatórios e saída arredondada
  - **Atende:** RN-010, RN-011
  - **Aceite:** testes `rn011_nenhumMotivoVazio()` (todas as 14 despesas do exemplo saem com motivo) e `rn010_saidaDuasCasasDecimais()` (33,33 na saída do d-011; totais com 2 casas).
  - **Commit:**

- [x] **T-013** — Cobertura dos casos de borda restantes da spec §7 (tabela)
  - **Atende:** AMB-013 e linhas não cobertas acima
  - **Aceite:** testes `amb013_sabadoPlantao_elegivel()` (d-012) e o caso "estorno sobre cota já cortada" da tabela §7; cada linha da tabela mapeia para um teste com RN/AMB no nome.
  - **Commit:**

## Fase 4 — Saída e CLI

- [x] **T-014** — Materializar `Saida` e escrever JSON de saída (io/saida)
  - **Atende:** spec §4 saída
  - **Aceite:** teste de integração `SaidaTest`: entrada pequena → JSON com `colaborador_id`, `competencia`, `total_solicitado`, `total_reembolsado`, `despesas[]` com os 5 campos; números serializados com 2 casas.
  - **Commit:**

- [x] **T-015** — CLI `calcular --input --output` (args à mão, DT-005)
  - **Atende:** interface fixa do enunciado
  - **Aceite:** teste ponta a ponta `CliE2ETest`: roda binário/wrapper com `--input exemplos/despesas-exemplo.json --output out.json`; arquivo criado; código de saída 0; uso errado → código 2 com ajuda.
  - **Commit:**

- [x] **T-016** — E2E dourado: totais esperados do exemplo
  - **Atende:** spec §9 critérios de aceite
  - **Aceite:** `ExemploE2ETest` processa o arquivo exemplo e afirma o estado de cada despesa — d-001 parcial 60,00; d-002 parcial 0,00; d-003 parcial 80,00; d-004 recusada; d-005 recusada; d-006 aprovada 54,90; d-007 recusada duplicata; d-008 recusada período; d-009 recusada estorno; d-010 parcial 250,00; d-011 aprovada 33,33; d-012 aprovada 47,20; d-013 recusada nota; d-014 parcial 60,00 — e `total_reembolsado = 585,43`.
  - **Commit:**

- [x] **T-017** — README.md (como rodar e como testar) + CLAUDE.md (convenções)
  - **Atende:** estrutura de entrega do desafio
  - **Aceite:** leigo consegue `mvn test` e rodar a CLI seguindo só o README; CLAUDE.md lista os pactos (arquivos de spec, formato de commit, proibição de regra de negócio fora de spec).
  - **Commit:**

---

## Fase 5 — Envelope do Dia 2: política v4, centro de custo e multi-moeda

> Origem: envelope lacrado do Dia 2, absorvido pela spec v2.0 e registrado em
> D-003 (`DECISIONS.md`). Numeração continua de T-018; as tasks anteriores não
> mudam — suas extensões obrigatórias (T-003, T-004, T-006, T-008, T-009,
> T-010, T-011, T-012, T-014, T-015, listadas em D-003) são absorvidas pelos
> testes desta fase, sem reescrever histórico. O ponto de atenção do §10 da
> spec (hospedagem multi-diária) não abre task: D-002 foi reafirmada pelo
> envelope (periodicidade `diaria`) e e-007 é afirmada no E2E da T-022.

- [ ] **T-018** — Ingestão da política v4 e do câmbio + campo `moeda` (io/ingestao)
  - **Atende:** spec §4 entrada (arquivos de política e câmbio); AMB-018 (default `BRL` na leitura); base de RN-012/RN-013; extensão de T-003 (D-003)
  - **Aceite:** testes `IngestaoTest`: `politicaV4_lidaEmMemoria()` (padrão 60/80/250; CC-COMERCIAL 90/150/400 + representacao 300; gatilho 100,00; acréscimo 50; vigência 2026-07-01); `cambio_lidoEmMemoria()` (USD 5,42 em 2026-07-13; EUR 6,03 em 2026-07-27); `moeda_ausente_defaultBrl()` (ausente ≡ `"BRL"` explícito); `politicaSemCampoObrigatorio_erroLegivel()` (sem `padrao` ou sem `vigencia` → erro de processamento legível, não exceção crua); JSON malformado → erro legível (padrão de T-003).
  - **Commit:**

- [ ] **T-019** — RN-012: limites por centro de custo, vigência e catálogo dinâmico
  - **Atende:** RN-012; AMB-014, AMB-015, AMB-020, AMB-021; rework de RN-001/RN-002 (catálogo derivado da política), RN-005 (gatilho lido da política) e RN-008 (percentual lido da política) — extensões de T-004/T-008/T-009 listadas em D-003
  - **Aceite:** testes `PoliticaTest`/`MotorReembolsoTest`: `rn012_ccComercial_overrides()` (resolve 90/150/400/representacao 300); `rn012_ccDesconhecido_resolvePadrao()` (CC-SUPORTE-N2 → 60/80/250); `rn012_categoriaAusenteNoPadrao_recusada()` (f-003: representacao com CC sem bloco → recusada "Categoria fora da política"); `rn012_categoriaAusenteNoBlocoCc_herdadoDoPadrao()` (CC-ADM, hospedagem → 250 por lançamento); `rn012_limiteZero_naoReembolsavel()` (CC-ENG-PLATAFORMA, hospedagem recusada como não reembolsável, mesmo com `em_viagem: true`); `rn012_vigenciaPosterior_erroDeProcessamento()` (política v4 + competência 2026-06 → erro, sem saída); `rn012_periodicidadeDesconhecida_erroDeProcessamento()` (`periodicidade: "mes"` → erro); `rn005_gatilhoLidoDaPolitica()` (política alternativa com gatilho 80,00: R$ 80,00 sem nota elegível, R$ 80,01 recusada); `rn008_percentualLidoDaPolitica()` (política com acréscimo 100: limite 60 → 120 em viagem); `amb020_semEmViagem_semAcrescimo()` (alimentação 100,00 BRL com nota, sem `em_viagem`, CC-COMERCIAL → parcial 90,00, não 135,00).
  - **Commit:**

- [ ] **T-020** — RN-013: moeda e conversão cambial
  - **Atende:** RN-013; AMB-016, AMB-017, AMB-018, AMB-019, AMB-022; rework de RN-004 (duplicata compara valor em BRL) e RN-009 (estorno em BRL); ordem da spec §8 (conversão no passo 4)
  - **Aceite:** testes `MotorReembolsoTest`: `rn013_moedaAusente_tratadaComoBrl()` (sem conversão e sem consulta de taxa); `rn013_convertePelaTaxaDataDaDespesa()` (22 EUR em 2026-07-14 → R$ 130,46); `rn013_diaSemCotacao_usaUltimaPublicada()` (30 EUR em 2026-07-18, sábado → taxa 5,96 de 2026-07-17 → R$ 178,80); `rn013_moedaSemCotacao_recusaItemLoteSegue()` (55 GBP → recusada "Moeda sem cotação disponível"; demais itens do lote processados); `rn013_semCambioFornecido_recusaMoedaEstrangeira()` (despesa em EUR sem arquivo de câmbio → recusada como sem cotação); `amb019_gatilhoNotaSobreValorConvertido()` (40 USD = R$ 220,00 sem nota → recusada por nota, e-005); `amb022_duplicataComparaValorEmBrl()` (100 USD e 548,00 BRL no mesmo dia/fornecedor/categoria → segunda recusada como duplicata da primeira).
  - **Commit:**

- [ ] **T-021** — Saída multi-moeda (materialização)
  - **Atende:** spec §4 saída (`moeda`, `valor_original`, `valor_solicitado` em BRL, `taxa_cambio`, `data_taxa`, totais em BRL); RN-010, RN-011; extensão de T-012/T-014 (D-003)
  - **Aceite:** testes `SaidaTest`: `saida_despesaEmMoedaEstrangeira_expoeTaxaEData()` (e-004: moeda EUR, valor_original 30,00, valor_solicitado 178,80, taxa_cambio 5,96, data_taxa 2026-07-17); `saida_despesaBrl_semCamposDeTaxa()` (moeda "BRL", valor_original = valor_solicitado, sem taxa_cambio/data_taxa); `saida_despesaSemCotacao_semValorBrl()` (valor_solicitado nulo, reembolsado 0,00, motivo "Moeda sem cotação disponível", contribui 0 nos totais); `rn010_totaisEmBrlDuasCasas()` (totais somam valores em BRL com 2 casas).
  - **Commit:**

- [ ] **T-022** — CLI com `--politica`/`--cambio` + E2E dourado dos envelopes
  - **Atende:** spec §9 (critérios do envelope); interface da CLI estendida (extensão de T-015/T-016, D-003); integração AMB-014..AMB-022
  - **Aceite:** `CliE2ETest`: `calcular --input exemplos/despesas-envelope.json --politica exemplos/politica-v4.json --cambio exemplos/cambio.json --output out.json` termina com código 0 e gera o arquivo; sem `--politica` → código 2 com ajuda; sem `--cambio` com despesa em moeda ≠ BRL → lote processado, itens sem cotação recusados (RN-013). `EnvelopeE2ETest`: processa `despesas-envelope.json` e afirma e-001 parcial 300,00; e-002 parcial 90,00 (valor BRL 130,46, taxa 5,93); e-003 aprovada 85,26; e-004 parcial 90,00 (taxa 5,96 de 2026-07-17); e-005 recusada por nota; e-006 recusada sem cotação; e-007 parcial 400,00; e-008 parcial 90,00; e-009 recusada por categoria; e-010 aprovada 88,00; `total_solicitado` 2457,52 e `total_reembolsado` 1143,26. Processa `despesas-envelope-cc-desconhecido.json` e afirma f-001 aprovada 58,00; f-002 parcial 250,00; f-003 recusada; f-004 aprovada 65,76; totais 623,76 / 373,76. README atualizado com os flags novos (`DocsTest` continua verde).
  - **Commit:**

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
| RN-012 | T-019 | `rn012_ccDesconhecido_resolvePadrao`, `rn012_categoriaAusenteNoPadrao_recusada`, `rn012_limiteZero_naoReembolsavel`, `rn012_vigenciaPosterior_erroDeProcessamento` |
| RN-013 | T-018, T-020 | `IngestaoTest.moeda_ausente_defaultBrl`, `rn013_convertePelaTaxaDataDaDespesa`, `rn013_moedaSemCotacao_recusaItemLoteSegue` |
| AMB-014 | T-019 | `rn012_ccDesconhecido_resolvePadrao`, `rn012_categoriaAusenteNoPadrao_recusada` |
| AMB-015 | T-019 | `rn012_categoriaAusenteNoBlocoCc_herdadoDoPadrao`, `rn012_limiteZero_naoReembolsavel` |
| AMB-016 | T-020 | `rn013_diaSemCotacao_usaUltimaPublicada` |
| AMB-017 | T-020 | `rn013_moedaSemCotacao_recusaItemLoteSegue`, `rn013_semCambioFornecido_recusaMoedaEstrangeira` |
| AMB-018 | T-018, T-020 | `IngestaoTest.moeda_ausente_defaultBrl`, `rn013_moedaAusente_tratadaComoBrl` |
| AMB-019 | T-020 | `amb019_gatilhoNotaSobreValorConvertido` |
| AMB-020 | T-019, T-022 | `amb020_semEmViagem_semAcrescimo`, `EnvelopeE2ETest` (e-002 parcial 90,00) |
| AMB-021 | T-019 | `rn012_vigenciaPosterior_erroDeProcessamento` |
| AMB-022 | T-020 | `amb022_duplicataComparaValorEmBrl` |
