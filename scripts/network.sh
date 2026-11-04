#!/usr/bin/env bash
# Rede base do 2PC multichain.
#
#   ./scripts/network.sh up        gera cripto e sobe orderer + peers
#   ./scripts/network.sh channels  cria masterchain, channela, channelb e junta os peers
#   ./scripts/network.sh deploy    empacota/instala/aprova/commita txmanager e twopc
#   ./scripts/network.sh setup     RegisterChain, ConfigureCoordinator e saldos iniciais
#   ./scripts/network.sh all       up + channels + deploy + setup
#   ./scripts/network.sh down      derruba tudo e apaga artefatos
set -euo pipefail
source "$(dirname "$0")/env.sh"

up() {
  command -v cryptogen >/dev/null || fatal "binários do Fabric não encontrados (defina FABRIC_BIN ou veja scripts/env.sh)"
  if [ ! -d "$ORGS_DIR/peerOrganizations" ]; then
    info "Gerando material criptográfico (cryptogen)"
    cryptogen generate --config="$NET/crypto-config.yaml" --output="$ORGS_DIR"
  fi
  info "Subindo containers"
  docker compose -f "$NET/compose.yaml" up -d
  sleep 3
}

channels() {
  mkdir -p "$ARTIFACTS"
  local ch org
  for ch in "${CHANNELS[@]}"; do
    info "Canal $ch (${CHANNEL_ORGS[$ch]})"
    FABRIC_CFG_PATH="$NET" configtxgen -profile "${CHANNEL_PROFILE[$ch]}" \
      -outputBlock "$ARTIFACTS/$ch.block" -channelID "$ch"
    osnadmin channel join --channelID "$ch" --config-block "$ARTIFACTS/$ch.block" \
      -o "$ORDERER_ADMIN" --ca-file "$ORDERER_CA" \
      --client-cert "$ORDERER_DIR/tls/server.crt" --client-key "$ORDERER_DIR/tls/server.key" >/dev/null
    for org in ${CHANNEL_ORGS[$ch]}; do
      setOrg "$org"
      retry 5 peer channel join -b "$ARTIFACTS/$ch.block"
    done
  done
}

retry() { local n=$1; shift; local i; for ((i=1;i<=n;i++)); do "$@" && return 0; sleep 2; done; return 1; }

# Empacota um chaincode Java a partir do jar já compilado: sem pom/gradle no pacote,
# a imagem fabric-javaenv apenas copia o jar e o executa com o ContractRouter, usando
# shim/protos/gson do próprio /root/chaincode-java/lib.
package_cc() {
  local name=$1 label=$2 dir="$ARTIFACTS/cc/$name"
  rm -rf "$dir" && mkdir -p "$dir"
  cp "$ROOT/$name/target/$name.jar" "$dir/"
  peer lifecycle chaincode package "$ARTIFACTS/$name.tar.gz" --path "$dir" --lang java --label "$label"
}

deploy() {
  mkdir -p "$ARTIFACTS"
  build

  local name ch org seq
  for name in txmanager twopc; do
    # sequência = commitada atual + 1 (permite redeploy após alterar o código)
    setOrg orgcoord
    ch=$([ "$name" = txmanager ] && echo masterchain || echo channela)
    seq=$(peer lifecycle chaincode querycommitted -C "$ch" -n "$name" -O json 2>/dev/null | jq -r '.sequence // 0' || echo 0)
    seq=$((seq + 1))
    info "Empacotando $name (sequência $seq)"
    package_cc "$name" "${name}_$seq"

    # instala em todo peer que participa de algum canal que roda esse chaincode
    local -A installed=()
    for ch in "${CHANNELS[@]}"; do
      [ "${CHANNEL_CC[$ch]}" = "$name" ] || continue
      for org in ${CHANNEL_ORGS[$ch]}; do
        [ -n "${installed[$org]:-}" ] && continue
        info "Instalando $name em peer0.$org"
        setOrg "$org"
        peer lifecycle chaincode install "$ARTIFACTS/$name.tar.gz" 2>&1 | grep -v "already successfully installed" || true
        installed[$org]=1
      done
    done
    setOrg orgcoord
    local pkgid
    pkgid=$(peer lifecycle chaincode calculatepackageid "$ARTIFACTS/$name.tar.gz")

    for ch in "${CHANNELS[@]}"; do
      [ "${CHANNEL_CC[$ch]}" = "$name" ] || continue
      for org in ${CHANNEL_ORGS[$ch]}; do
        info "Aprovando $name em $ch por $org"
        setOrg "$org"
        peer lifecycle chaincode approveformyorg -o "$ORDERER_ADDR" --ordererTLSHostnameOverride orderer.example.com \
          --tls --cafile "$ORDERER_CA" --channelID "$ch" --name "$name" --version "$seq" \
          --package-id "$pkgid" --sequence "$seq"
      done
      info "Commit de $name em $ch"
      setOrg orgcoord
      # shellcheck disable=SC2046
      peer lifecycle chaincode commit -o "$ORDERER_ADDR" --ordererTLSHostnameOverride orderer.example.com \
        --tls --cafile "$ORDERER_CA" --channelID "$ch" --name "$name" --version "$seq" --sequence "$seq" \
        $(peerArgs "$ch")
    done
  done
}

setup() {
  info "Setup inicial (RegisterChain, ConfigureCoordinator, saldos)"
  app setup
}

down() {
  info "Derrubando a rede"
  docker compose -f "$NET/compose.yaml" down -v --remove-orphans || true
  # containers/imagens de chaincode criados pelos peers
  docker ps -aq --filter name='dev-peer0.org' | xargs -r docker rm -f >/dev/null
  docker images -q --filter reference='dev-peer0.org*' | xargs -r docker rmi -f >/dev/null
  rm -rf "$ORGS_DIR" "$ARTIFACTS"
}

case "${1:-}" in
  up) up ;;
  channels) channels ;;
  deploy) deploy ;;
  setup) setup ;;
  all) up; channels; deploy; setup ;;
  down) down ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
