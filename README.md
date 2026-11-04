# Transações atômicas multichain com 2PC — cenário base (2 cadeias)

Implementação da arquitetura do diagrama sobre Hyperledger Fabric 2.5, **toda em Java**
(chaincodes + aplicações), usando o `fabric-samples` como fonte de binários, imagens e padrões.

```
                 ┌──────────────── masterchain (OrgCoord, OrgA, OrgB) ───────────────┐
 Cliente ──────▶ │ txmanager: RegisterChain, Begin, RecordVote, Decide, Timeout,      │
 (Alice/Bob)     │            RecordCompletion, GetGtx/GetSub/GetDecision/GetChain     │
                 └───────────────▲────────────────────────────────────▲───────────────┘
                     provas P1/P3│ (consultas endossadas)  provas P2/P4│
                 ┌───────────────┴─── Relayer (escuta eventos Begin / Decided) ───────┐
                 └───────▼───────────────────────────────────────────▼───────────────┘
  channela (OrgCoord, OrgA)  twopc                 channelb (OrgCoord, OrgB)  twopc
  SALDO~Alice, SALDO~Bob                            IMOVEL~10
```

## O que veio do fabric-samples (e o que não)

| Usado | Para quê |
|---|---|
| `fabric-samples/bin` (v2.5.16) | `cryptogen`, `configtxgen`, `osnadmin`, `peer` |
| imagens `fabric-peer/orderer/javaenv:2.5` | rede e execução do chaincode Java |
| `test-network/compose/docker/peercfg/core.yaml` | copiado para `network/peercfg/` |
| padrões da `test-network` | channel participation (sem system channel), lifecycle approve/commit |

A `test-network` **não** é usada diretamente: ela só tem Org1/Org2 e todo canal criado
junta as duas, enquanto aqui são 3 orgs e 3 canais com composições diferentes.
Para estudar: `asset-transfer-basic/chaincode-java` (contract API) e
`asset-transfer-events/application-java` (eventos com Gateway) são as referências mais próximas.

> As imagens `:latest` instaladas na máquina são Fabric **3.1.5**, mas os binários são 2.5.16;
> por isso o `compose.yaml` fixa as tags em `2.5`.

## Estrutura

```
network/      crypto-config.yaml, configtx.yaml (perfis MasterChain/ChannelA/ChannelB), compose.yaml
scripts/      env.sh (topologia + helpers), network.sh (up/channels/deploy/setup/down), app.sh
common/       modelo (POJOs/JSON) + mc2pc.common.cc.Proof (verificação de provas) + testes
txmanager/    chaincode da masterchain (TxManager.java)
twopc/        chaincode das cadeias participantes (TwoPC.java) — o mesmo jar em channela e channelb
app/          Setup, Relayer, Client (Fabric Gateway Java) — jar único mc2pc-app.jar
```

## Como rodar

Pré-requisitos: Docker e Java 21+. O Maven vem pelo wrapper (`./mvnw`, baixado em `~/.m2` na 1ª vez).

```bash
./scripts/network.sh all          # up + channels + deploy + setup
```

Em um terminal, o relayer:

```bash
./scripts/app.sh relayer
```

Em outro, o cliente:

```bash
./scripts/app.sh client swap --gtx 123                 # Alice 100 tokens (A) <-> imóvel 10 do Bob (B)
./scripts/app.sh client state                          # saldos e imóvel
./scripts/app.sh client status --gtx 123               # GTX, SUBs e STAGEs
./scripts/app.sh client swap --tokens 1000             # voto NÃO (saldo insuficiente) -> ABORT
./scripts/app.sh client timeout --gtx <id>             # ABORT por prazo vencido
./scripts/app.sh client prove masterchain txmanager GetDecision <id>   # gera uma prova (base64)
```

Após alterar um chaincode: `./scripts/network.sh deploy` (incrementa a sequência automaticamente).
Para destruir tudo: `./scripts/network.sh down`.

## Mapeamento diagrama → código

| Passo | Onde |
|---|---|
| 0 intenção assinada | `Client.swap` — Alice = `User1@orga`, Bob = `User1@orgb` assinam SHA-256 do JSON |
| 2 Begin | `TxManager.begin`: valida assinaturas contra as CAs da cadeia onde cada parte cede |
| 4–6 GetSub → Prepare | `Relayer.onBegin` + `TwoPC.prepare` (prova P1, regras de negócio, travas) |
| 7–10 GetStage → RecordVote | prova P2; `REFUSED` vira SUB `RECUSADA` |
| 11 Decide / Timeout | `TxManager.decide` / `timeout` → evento `Decided` |
| 13–15 GetDecision → Commit/Abort | `Relayer.onDecided` + `TwoPC.commit/abort` (prova P3) |
| 16–20 GetStage → RecordCompletion | prova P4 → evento `Completed` → cliente avisa Alice e Bob |

## Decisões de projeto

- **Prova = consulta endossada.** O relayer chama `endorse()` numa função de leitura e entrega o
  `Envelope` sem submetê-lo. `Proof.verify` confere canal, chaincode, função/argumentos, o hash
  da proposta, status 200, e cada endosso (assinatura ECDSA, certificado emitido pela CA da org,
  OU=peer) contra a política (`MAJORITY` = 2 de 3 na masterchain; 2 de 2 nas cadeias).
  O relayer não é confiável: provas falsas, de outra GTX, de outro canal ou da decisão errada
  são rejeitadas on-chain (testado).
- **Voto NÃO é uma transação válida**, não um erro: regra de negócio violada grava `STAGE=REFUSED`
  com o motivo, para que exista algo a provar na masterchain.
- **Abort sem Prepare grava `ABORTED`** (tombstone) e um Prepare atrasado devolve esse estado.
- **Idempotência**: RecordVote, Decide, Commit, Abort e Prepare repetidos não alteram nada,
  então o relayer pode reexecutar etapas.
- **Determinismo**: o "relógio" é o timestamp da proposta; JSON com Gson + `TreeMap`.
  Cada endossante roda sua própria JVM e `Map.of`/`HashMap` têm ordem diferente entre JVMs —
  isso causou `ProposalResponsePayloads do not match` durante o desenvolvimento.
- **Chaincode Java como jar pré-compilado**: o pacote contém só o jar; a `fabric-javaenv` o
  executa com o `ContractRouter` usando shim/protos/gson da própria imagem (por isso são `provided`).
- Votos e conclusões são enviados **sequencialmente** na masterchain (`RecordCompletion` lê
  as outras SUBs; em paralelo causaria conflitos MVCC). Prepare/Commit são paralelos entre cadeias.

## Simplificações conhecidas / próximos passos

- **N cadeias**: o protocolo e o relayer já são genéricos (iteram `participantes`). Falta
  parametrizar a rede: nova org/canal em `network/*.yaml`, `scripts/env.sh` e `Topology.java`
  (um gerador de topologia a partir de um arquivo único é o próximo passo natural).
- Identidade ↔ conta: o nome da parte (`Alice`) não está amarrado ao certificado; só se exige um
  certificado válido de uma org daquela cadeia.
- Funções administrativas aceitam qualquer admin (OU=admin) de qualquer org do canal.
- `Transfer` local não autentica o titular (só demonstra o respeito às travas).
- O relayer começa do bloco atual e não guarda checkpoint; se cair no meio, use
  `client status` e `client timeout` (ou adicione um `FileCheckpointer`).
- CAs intermediárias não são suportadas na verificação de certificados (cryptogen não usa).
