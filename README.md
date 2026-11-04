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
app/          Setup, Relayer, Client, Explorer (Fabric Gateway Java) — jar único mc2pc-app.jar
```

## Como rodar

Todos os comandos são executados a partir da raiz do projeto (`multichain-2pc/`).

### 0. Pré-requisitos

- Docker com Compose v2 e Java 21+ (o Maven vem pelo wrapper `./mvnw`, baixado em `~/.m2` na 1ª vez).
- Binários do Fabric 2.5 (`peer`, `configtxgen`, `cryptogen`, `osnadmin`). Os scripts procuram em
  `$FABRIC_BIN`, `./bin`, `../fabric-samples/bin` e no `PATH`. Para baixá-los dentro do projeto:

```bash
curl -sSL https://raw.githubusercontent.com/hyperledger/fabric/main/scripts/install-fabric.sh | bash -s -- --fabric-version 2.5.16 binary
```

```bash
docker compose version && java -version
```

### 1. Limpar o ambiente

Derruba containers, apaga volumes (ledgers), containers/imagens de chaincode, cripto e artefatos:

```bash
./scripts/network.sh down
```

```bash
./mvnw -q clean
```

### 2. Subir a rede do zero

Gera cripto, sobe os containers, cria os 3 canais, compila e instala os chaincodes e roda o setup
(`RegisterChain`, `ConfigureCoordinator`, Alice = 500 e Bob = 200 tokens em A, imóvel 10 do Bob em B):

```bash
./scripts/network.sh all
```

As etapas também podem ser executadas separadamente: `up`, `channels`, `deploy`, `setup`.

### 3. Terminais

| Terminal | Comando | Papel |
|---|---|---|
| 1 | `./scripts/app.sh relayer` | conduz o protocolo (**rode apenas um**) |
| 2 | `./scripts/app.sh explorer --serve` | visualização ao vivo em http://localhost:8080 (opcional) |
| 3 | comandos `client` abaixo | dispara os cenários |

> Dois relayers ao mesmo tempo não quebram a atomicidade (as funções são idempotentes e o Fabric
> rejeita escritas conflitantes), mas geram transações duplicadas e `MVCC_READ_CONFLICT` no ledger.

### 4. Cenários

Use um `--gtx` novo a cada execução (repetir um id faz o `Begin` falhar com "GTX já existe").
Os resultados esperados abaixo assumem a rede recém-criada e os cenários na ordem.

#### Cenário 1 — COMMIT (troca normal)

Alice cede 100 tokens na cadeia A; Bob cede o imóvel 10 na cadeia B:

```bash
./scripts/app.sh client swap --gtx c1
```

Esperado: `Completed COMMIT`; Alice = 400, Bob = 300, imóvel 10 de Alice, nenhuma trava.

#### Cenário 2 — ABORT, as duas cadeias votam NÃO

Alice tenta ceder 1000 tokens (tem 400) e o imóvel 10 já não é do Bob:

```bash
./scripts/app.sh client swap --gtx c2 --tokens 1000
```

Esperado: duas SUBs `RECUSADA`, `Completed ABORT`, nenhum estado alterado. O motivo de cada
recusa aparece no log do relayer e no `STAGE` (`client status --gtx c2`).

#### Cenário 3 — ABORT misto (A recusa, B prepara e trava)

Crie o imóvel 20 do Bob (como Admin da OrgCoord):

```bash
source scripts/env.sh && setOrg orgcoord && peer chaincode invoke -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "$ORDERER_CA" -C channelb -n twopc $(peerArgs channelb) --waitForEvent -c '{"Args":["CreateImovel","20","Bob"]}'
```

Rode a troca — A recusa por saldo, B prepara e trava `IMOVEL~20`:

```bash
./scripts/app.sh client swap --gtx c3 --tokens 1000 --imovel 20
```

Confira que o Abort liberou a trava e o imóvel continua do Bob (`"dono":"Bob"`, `"locked":false`):

```bash
source scripts/env.sh && setOrg orgcoord && peer chaincode query -C channelb -n twopc -c '{"Args":["GetImovel","20"]}' && peer chaincode query -C channelb -n twopc -c '{"Args":["IsLocked","IMOVEL~20"]}'
```

#### Cenário 4 — Timeout (GTX travada em PENDING)

1. **Pare o relayer** (Ctrl+C no terminal 1) — sem ele ninguém executa o Prepare.
2. Inicie uma troca com prazo de 15 s (o cliente desiste de acompanhar após ~45 s):

```bash
./scripts/app.sh client swap --gtx t1 --prazo 15
```

3. Confira que ficou presa (`PENDING`, STAGE `NONE` nas duas cadeias):

```bash
./scripts/app.sh client status --gtx t1
```

4. **Suba o relayer de novo** no terminal 1 e dispare o timeout:

```bash
./scripts/app.sh relayer
```

```bash
./scripts/app.sh client timeout --gtx t1
```

5. Após alguns segundos, confira: `decisao: ABORT`, `concluida: true` e STAGE `ABORTED` nas duas
   cadeias, mesmo sem ter havido Prepare (tombstone que recusa um Prepare atrasado):

```bash
./scripts/app.sh client status --gtx t1
```

#### Cenário 5 — Segurança: provas forjadas são rejeitadas on-chain

Todos estes comandos **devem falhar** com a mensagem indicada.

Prova que não é um envelope válido → `protobuf inválido`:

```bash
source scripts/env.sh && setOrg orgcoord && peer chaincode invoke -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "$ORDERER_CA" -C channelb -n twopc $(peerArgs channelb) -c '{"Args":["Commit","c3","AAAA"]}'
```

Prova verdadeira de ABORT usada para pedir Commit → `decisão provada ... é ABORT, esperado COMMIT`:

```bash
source scripts/env.sh && setOrg orgcoord && P=$(./scripts/app.sh client prove masterchain txmanager GetDecision c3) && peer chaincode invoke -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "$ORDERER_CA" -C channelb -n twopc $(peerArgs channelb) -c "{\"Args\":[\"Commit\",\"c3\",\"$P\"]}"
```

Prova de uma GTX reaproveitada em outra → `argumentos [c3], esperados [c1]`:

```bash
source scripts/env.sh && setOrg orgcoord && P=$(./scripts/app.sh client prove masterchain txmanager GetDecision c3) && peer chaincode invoke -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "$ORDERER_CA" -C channelb -n twopc $(peerArgs channelb) -c "{\"Args\":[\"Abort\",\"c1\",\"$P\"]}"
```

Prova de outro canal (channela no lugar da masterchain) → `canal 'channela', esperado 'masterchain'`:

```bash
source scripts/env.sh && setOrg orgcoord && Q=$(./scripts/app.sh client prove channela twopc GetStage c1) && peer chaincode invoke -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "$ORDERER_CA" -C channelb -n twopc $(peerArgs channelb) -c "{\"Args\":[\"Commit\",\"c1\",\"$Q\"]}"
```

### 5. Explorer (visualização do ledger)

Ao vivo — abra http://localhost:8080 no navegador:

```bash
./scripts/app.sh explorer --serve
```

Em outra porta:

```bash
./scripts/app.sh explorer --serve --port 9090
```

Retrato estático (gera `explorer.html` na raiz do projeto; gere de novo para ver transações novas):

```bash
./scripts/app.sh explorer
```

```bash
xdg-open explorer.html
```

O que a página mostra:

- **Transações globais** — lista de GTX com a decisão; para a GTX escolhida, um diagrama de raias
  (masterchain, channela, channelb) com cada transação como um ponto e cada prova como uma seta
  tracejada vinda da cadeia que a emitiu, e a linha do tempo fase a fase (1 · Begin → 6 · Conclusão).
  Clicando em uma linha: txId, bloco, criador, endossantes, argumentos, escritas/leituras, evento e
  resposta. As provas aparecem decodificadas (ex.: "Prova P1 · masterchain txmanager.GetSub(c1, A),
  endossada por OrgCoordMSP, OrgBMSP"), com o resultado provado.
- **Blocos por canal** — cada bloco com hash e hash anterior, transações classificadas como
  `2pc`, `setup`, `config` ou `lifecycle` (as duas últimas ocultas por padrão).

No modo `--serve`, o servidor consulta a altura dos canais a cada segundo e busca só os blocos
novos; a página se atualiza sozinha, segue a GTX mais recente (até você escolher outra) e mantém
abertas as linhas expandidas. O indicador "ao vivo" fica vermelho se o servidor cair.
O servidor escuta apenas em localhost.

### 6. Comandos úteis

| Comando | Para quê |
|---|---|
| `./scripts/app.sh client state` | saldos de Alice/Bob (A), imóvel 10 (B) e travas |
| `./scripts/app.sh client status --gtx <id>` | GTX, SUBs e STAGE em cada cadeia |
| `./scripts/app.sh client prove <canal> <cc> <função> [args]` | gera uma prova (base64) de qualquer consulta |
| `./mvnw -q -pl common test` | teste unitário da verificação de provas |
| `./scripts/network.sh deploy` | recompila e reinstala os chaincodes (sequência + 1) |
| `docker logs -f peer0.orgcoord.example.com` | logs de um peer |
| `docker ps --format '{{.Names}}' \| grep dev-peer` | containers dos chaincodes |

Opções do `client swap`: `--gtx ID`, `--tokens N` (padrão 100), `--imovel ID` (padrão 10),
`--prazo SEGUNDOS` (padrão 120).

> Depois de recompilar (`deploy` ou `./mvnw install`), **reinicie o relayer e o explorer**: o jar
> é substituído e um processo Java já em execução pode falhar ao carregar classes novas.

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
