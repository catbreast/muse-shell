#!/bin/bash
# Phone container agent setup, phase 1: Node + codex + dsh binaries + codex config
exec >> /root/agent-setup.log 2>&1
set -x
date
cd /root
# --- Node 22 LTS (arm64) from npmmirror ---
VER=$(python3 -c "
import json,urllib.request
d=json.load(urllib.request.urlopen('https://registry.npmmirror.com/-/binary/node/index.json',timeout=30))
print([x['version'] for x in d if x.get('lts') and x['version'].startswith('v22.')][0])
")
echo "NODE_VER=$VER"
curl -fsSL -m 300 -o /tmp/node.tar.xz "https://registry.npmmirror.com/-/binary/node/${VER}/node-${VER}-linux-arm64.tar.xz"
mkdir -p /usr/local
tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
rm -f /tmp/node.tar.xz
node -v; npm -v
npm config set registry https://registry.npmmirror.com/
# --- global agents ---
npm i -g @openai/codex @deepseek-ai/dsh
which codex dsh; codex --version; dsh --version
# --- codex config (WorkBuddy hub, public entry) ---
mkdir -p /root/.codex
cat > /root/.codex/config.toml <<'TOML'
model_provider = "custom"
model = "deepseek-v4.1-flash"
model_reasoning_effort = "high"

[model_providers.custom]
name = "WorkBuddy Hub"
base_url = "https://wb2api.ruisnow.de/v1"
wire_api = "responses"
requires_openai_auth = true
experimental_bearer_token = "__WBKEY__"
TOML
chmod 600 /root/.codex/config.toml
date
echo PHASE1_DONE
