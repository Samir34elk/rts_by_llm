#!/usr/bin/env bash
# =============================================================================
# RTS — Script d'installation et de configuration complète
# Installe Ollama, télécharge le modèle, compile le projet et crée le launcher
# =============================================================================
set -euo pipefail

# ── Couleurs ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; RESET='\033[0m'

ok()   { echo -e "${GREEN}✓${RESET} $*"; }
info() { echo -e "${BLUE}▶${RESET} $*"; }
warn() { echo -e "${YELLOW}⚠${RESET} $*"; }
die()  { echo -e "${RED}✗ ERREUR:${RESET} $*" >&2; exit 1; }
header() { echo -e "\n${BOLD}${BLUE}══ $* ══${RESET}"; }

# ── Constantes ────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OLLAMA_MODEL="qwen2.5-coder:7b"
OLLAMA_ENDPOINT="http://localhost:11434"
CLI_BIN="$SCRIPT_DIR/rts-cli/build/install/rts-cli/bin/rts-cli"
LAUNCHER="$SCRIPT_DIR/rts"
CONFIG_TEMPLATE="$SCRIPT_DIR/rts-config.yaml.template"

# =============================================================================
# 1. Vérification des prérequis système
# =============================================================================
header "Vérification des prérequis"

# Java 17+
if ! command -v java &>/dev/null; then
    die "Java n'est pas installé. Installe Java 17+ : sudo apt install openjdk-17-jdk"
fi
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
    die "Java $JAVA_VERSION détecté, Java 17+ requis."
fi
ok "Java $JAVA_VERSION détecté"

# curl
if ! command -v curl &>/dev/null; then
    die "curl est requis. Installe-le : sudo apt install curl"
fi
ok "curl disponible"

# =============================================================================
# 2. Installation d'Ollama
# =============================================================================
header "Ollama"

if command -v ollama &>/dev/null; then
    ok "Ollama déjà installé ($(ollama --version 2>/dev/null | head -1))"
else
    info "Installation d'Ollama..."
    curl -fsSL https://ollama.com/install.sh | sh
    ok "Ollama installé"
fi

# Démarrer le serveur Ollama si pas déjà actif
if curl -sf "$OLLAMA_ENDPOINT/api/tags" &>/dev/null; then
    ok "Serveur Ollama déjà actif sur $OLLAMA_ENDPOINT"
else
    info "Démarrage du serveur Ollama en arrière-plan..."
    nohup ollama serve > /tmp/ollama.log 2>&1 &
    OLLAMA_PID=$!
    echo "$OLLAMA_PID" > /tmp/ollama.pid

    # Attendre que le serveur soit prêt (max 30s)
    for i in $(seq 1 30); do
        if curl -sf "$OLLAMA_ENDPOINT/api/tags" &>/dev/null; then
            ok "Serveur Ollama démarré (PID $OLLAMA_PID)"
            break
        fi
        sleep 1
        if [[ $i -eq 30 ]]; then
            die "Le serveur Ollama n'a pas démarré. Vérifie /tmp/ollama.log"
        fi
    done
fi

# =============================================================================
# 3. Téléchargement du modèle
# =============================================================================
header "Modèle LLM : $OLLAMA_MODEL"

EXISTING_MODELS=$(curl -sf "$OLLAMA_ENDPOINT/api/tags" | grep -o '"name":"[^"]*"' | sed 's/"name":"//;s/"//')

if echo "$EXISTING_MODELS" | grep -q "$OLLAMA_MODEL"; then
    ok "Modèle '$OLLAMA_MODEL' déjà disponible"
else
    info "Téléchargement de '$OLLAMA_MODEL' (peut prendre plusieurs minutes selon la connexion)..."
    ollama pull "$OLLAMA_MODEL"
    ok "Modèle '$OLLAMA_MODEL' prêt"
fi

# =============================================================================
# 4. Compilation du projet RTS
# =============================================================================
header "Compilation du projet RTS"

cd "$SCRIPT_DIR"

if [[ ! -f "./gradlew" ]]; then
    die "gradlew introuvable. Lance ce script depuis la racine du projet."
fi

info "Compilation en cours..."
./gradlew :rts-cli:installDist -q
ok "Projet compilé : $CLI_BIN"

# =============================================================================
# 5. Configuration rts-config.yaml
# =============================================================================
header "Configuration LLM (Ollama)"

cat > "$CONFIG_TEMPLATE" << 'EOF'
# RTS — Configuration LLM
# Copier ce fichier dans le répertoire racine du projet Java à analyser
# sous le nom rts-config.yaml

llm:
  enabled: true
  provider: ollama
  endpoint: ${RTS_LLM_ENDPOINT:http://localhost:11434}
  api-key: ${RTS_LLM_API_KEY:ollama}        # ignoré par Ollama, requis par le protocole
  model: ${RTS_LLM_MODEL:qwen2.5-coder:7b}
  max-tokens: 2000
  temperature: 0.1                           # quasi-déterministe
EOF

ok "Template de config créé : $CONFIG_TEMPLATE"
info "  → Copie-le dans le projet cible : cp $CONFIG_TEMPLATE <project>/rts-config.yaml"

# =============================================================================
# 6. Création du launcher global
# =============================================================================
header "Launcher 'rts'"

cat > "$LAUNCHER" << LAUNCHER_EOF
#!/usr/bin/env bash
# Launcher RTS — configuré pour Ollama (généré par setup.sh)

export RTS_LLM_ENDPOINT="\${RTS_LLM_ENDPOINT:-http://localhost:11434}"
export RTS_LLM_API_KEY="\${RTS_LLM_API_KEY:-ollama}"
export RTS_LLM_MODEL="\${RTS_LLM_MODEL:-$OLLAMA_MODEL}"

# S'assurer qu'Ollama tourne
if ! curl -sf "\$RTS_LLM_ENDPOINT/api/tags" &>/dev/null; then
    echo "⚠  Ollama non joignable sur \$RTS_LLM_ENDPOINT — démarrage..."
    nohup ollama serve > /tmp/ollama.log 2>&1 &
    sleep 3
fi

exec "$CLI_BIN" "\$@"
LAUNCHER_EOF

chmod +x "$LAUNCHER"
ok "Launcher créé : $LAUNCHER"

# =============================================================================
# 7. Ajout au PATH (optionnel)
# =============================================================================
header "PATH"

SHELL_RC=""
if [[ -f "$HOME/.bashrc" ]]; then SHELL_RC="$HOME/.bashrc"; fi
if [[ -f "$HOME/.zshrc" ]];  then SHELL_RC="$HOME/.zshrc"; fi

PATH_LINE="export PATH=\"\$PATH:$SCRIPT_DIR\""

if [[ -n "$SHELL_RC" ]]; then
    if grep -qF "$SCRIPT_DIR" "$SHELL_RC" 2>/dev/null; then
        ok "PATH déjà configuré dans $SHELL_RC"
    else
        echo "" >> "$SHELL_RC"
        echo "# RTS — Regression Test Selection" >> "$SHELL_RC"
        echo "$PATH_LINE" >> "$SHELL_RC"
        ok "PATH ajouté dans $SHELL_RC"
        warn "Recharge ton shell : source $SHELL_RC"
    fi
fi

# =============================================================================
# 8. Test de smoke
# =============================================================================
header "Test de smoke"

info "Appel au modèle pour vérifier que tout fonctionne..."

SMOKE_RESPONSE=$(curl -sf "$OLLAMA_ENDPOINT/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"$OLLAMA_MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word: OK\"}],\"max_tokens\":5}" \
    2>/dev/null || echo "FAILED")

if echo "$SMOKE_RESPONSE" | grep -qi "ok"; then
    ok "Modèle répond correctement"
else
    warn "Le modèle n'a pas répondu comme attendu — il est peut-être encore en cours de chargement"
    warn "Réessaie dans quelques secondes"
fi

# =============================================================================
# Résumé
# =============================================================================
echo ""
echo -e "${BOLD}${GREEN}════════════════════════════════════════${RESET}"
echo -e "${BOLD}${GREEN}  Installation terminée !${RESET}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════${RESET}"
echo ""
echo -e "  ${BOLD}Commandes disponibles :${RESET}"
echo ""
echo -e "  ${YELLOW}# Analyser un projet local${RESET}"
echo -e "  ./rts analyze /chemin/vers/projet"
echo ""
echo -e "  ${YELLOW}# Analyser un dépôt distant${RESET}"
echo -e "  ./rts analyze --url https://github.com/org/repo.git"
echo ""
echo -e "  ${YELLOW}# Sélectionner les tests (mode statique)${RESET}"
echo -e "  ./rts select --project /chemin/vers/projet --diff HEAD~1..HEAD"
echo ""
echo -e "  ${YELLOW}# Sélectionner les tests (mode hybride LLM)${RESET}"
echo -e "  # 1. Copie le template dans ton projet :"
echo -e "  cp $CONFIG_TEMPLATE /chemin/vers/projet/rts-config.yaml"
echo -e "  # 2. Lance avec --mode hybrid :"
echo -e "  ./rts select --project /chemin/vers/projet --diff HEAD~1..HEAD --mode hybrid"
echo ""
echo -e "  ${YELLOW}# Via URL + LLM${RESET}"
echo -e "  ./rts select --url https://github.com/org/repo.git \\"
echo -e "               --changed-files 'src/Foo.java' --mode hybrid"
echo ""
echo -e "  ${BOLD}Modèle actif :${RESET} $OLLAMA_MODEL"
echo -e "  ${BOLD}Endpoint    :${RESET} $OLLAMA_ENDPOINT"
echo -e "  ${BOLD}Logs Ollama :${RESET} /tmp/ollama.log"
echo ""
