#!/usr/bin/env bash
# Atalho para as aplicações Java:
#   ./scripts/app.sh relayer
#   ./scripts/app.sh client swap [--tokens 100] [--imovel 10] [--prazo 120]
#   ./scripts/app.sh client status --gtx <id> | client state | client timeout --gtx <id>
#   ./scripts/app.sh explorer --serve [--port 8080]   (visualização do ledger ao vivo)
#   ./scripts/app.sh explorer [arquivo.html]          (retrato estático)
set -euo pipefail
source "$(dirname "$0")/env.sh"
app "$@"
