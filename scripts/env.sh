#!/usr/bin/env bash
# Variáveis e funções comuns. Use com: source scripts/env.sh

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Única dependência externa: binários do Fabric 2.5 (peer, configtxgen, cryptogen, osnadmin).
# Ordem de busca: $FABRIC_BIN, ./bin do projeto, ../fabric-samples/bin, PATH.
# Para baixar no projeto: curl -sSL https://raw.githubusercontent.com/hyperledger/fabric/main/scripts/install-fabric.sh | bash -s -- --fabric-version 2.5.16 binary
for d in "${FABRIC_BIN:-}" "$ROOT/bin" "$ROOT/../fabric-samples/bin"; do
  if [ -n "$d" ] && [ -x "$d/peer" ]; then FABRIC_BIN="$(cd "$d" && pwd)"; export PATH="$FABRIC_BIN:$PATH"; break; fi
done

NET="$ROOT/network"
ORGS_DIR="$NET/organizations"
ARTIFACTS="$NET/channel-artifacts"
export FABRIC_CFG_PATH="$NET/peercfg"

# ---- Topologia ---------------------------------------------------------------
# Para N cadeias: acrescente a org em ORGS/ORG_*, o canal em CHANNELS/CHANNEL_*
# e uma entrada em app/src/main/java/mc2pc/app/Topology.java.
ORGS=(orgcoord orga orgb)
declare -A ORG_MSP=([orgcoord]=OrgCoordMSP [orga]=OrgAMSP [orgb]=OrgBMSP)
declare -A ORG_PORT=([orgcoord]=7051 [orga]=9051 [orgb]=11051)

MASTER_CHANNEL=masterchain
CHANNELS=(masterchain channela channelb)
declare -A CHANNEL_PROFILE=([masterchain]=MasterChain [channela]=ChannelA [channelb]=ChannelB)
declare -A CHANNEL_ORGS=([masterchain]="orgcoord orga orgb" [channela]="orgcoord orga" [channelb]="orgcoord orgb")
declare -A CHANNEL_CC=([masterchain]=txmanager [channela]=twopc [channelb]=twopc)

ORDERER_ADDR=localhost:7050
ORDERER_ADMIN=localhost:7053
ORDERER_DIR="$ORGS_DIR/ordererOrganizations/example.com/orderers/orderer.example.com"
ORDERER_CA="$ORDERER_DIR/tls/ca.crt"

# ---- Helpers -----------------------------------------------------------------
info()  { printf '\033[1;34m==> %s\033[0m\n' "$*"; }
fatal() { printf '\033[1;31mERRO: %s\033[0m\n' "$*" >&2; exit 1; }

# Configura o CLI `peer` como Admin da org informada.
setOrg() {
  local org=$1
  export CORE_PEER_TLS_ENABLED=true
  export CORE_PEER_LOCALMSPID=${ORG_MSP[$org]}
  export CORE_PEER_ADDRESS=localhost:${ORG_PORT[$org]}
  export CORE_PEER_TLS_ROOTCERT_FILE="$ORGS_DIR/peerOrganizations/$org.example.com/peers/peer0.$org.example.com/tls/ca.crt"
  export CORE_PEER_MSPCONFIGPATH="$ORGS_DIR/peerOrganizations/$org.example.com/users/Admin@$org.example.com/msp"
}

peerArgs() { # --peerAddresses/--tlsRootCertFiles para todas as orgs de um canal
  local ch=$1 org
  for org in ${CHANNEL_ORGS[$ch]}; do
    printf -- '--peerAddresses localhost:%s --tlsRootCertFiles %s ' "${ORG_PORT[$org]}" \
      "$ORGS_DIR/peerOrganizations/$org.example.com/peers/peer0.$org.example.com/tls/ca.crt"
  done
}

APP_JAR="$ROOT/app/target/mc2pc-app.jar"

# Compila chaincodes e apps (Maven Wrapper; usa o Java local, baixa o Maven em ~/.m2 na 1ª vez).
build() {
  command -v java >/dev/null || fatal "Java 21+ não encontrado"
  info "Build Maven (chaincodes + apps)"
  (cd "$ROOT" && ./mvnw -B -q clean install)
}

# Executa a aplicação Java (setup | relayer | client ...).
app() {
  [ -f "$APP_JAR" ] || build
  MC2PC_ROOT="$ROOT" java -jar "$APP_JAR" "$@"
}
