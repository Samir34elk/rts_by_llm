# Guide d'utilisation — RTS (Regression Test Selection)

RTS sélectionne le sous-ensemble minimal de tests à relancer après une modification du code source Java. Il combine analyse statique de dépendances, couverture JaCoCo, et raffinement optionnel par LLM.

---

## Table des matières

1. [Installation](#1-installation)
2. [Commande `analyze`](#2-commande-analyze)
3. [Commande `select`](#3-commande-select)
4. [Mode hybride (LLM)](#4-mode-hybride-llm)
5. [Analyse d'un dépôt distant](#5-analyse-dun-dépôt-distant)
6. [Plugin Gradle natif](#6-plugin-gradle-natif)
7. [Couverture JaCoCo](#7-couverture-jacoco)
8. [Cache du graphe de dépendances](#8-cache-du-graphe-de-dépendances)
9. [Projets multi-modules](#9-projets-multi-modules)
10. [Intégration CI/CD](#10-intégration-cicd)
11. [Référence des options](#11-référence-des-options)

---

## 1. Installation

### Prérequis

- Java 17+
- Git

### Installation rapide (avec Ollama)

Le script `setup.sh` installe Ollama, télécharge le modèle `qwen2.5-coder:7b`, compile le projet et crée le launcher `./rts` :

```bash
git clone git@github.com:Samir34elk/rts_by_llm.git RTSbyLLM
cd RTSbyLLM
./setup.sh
```

### Installation manuelle

```bash
# Compiler le projet
./gradlew :rts-cli:installDist

# Le binaire est disponible ici :
./rts-cli/build/install/rts-cli/bin/rts-cli --help

# Ou créer un alias
alias rts='./rts-cli/build/install/rts-cli/bin/rts-cli'
```

---

## 2. Commande `analyze`

Analyse un projet Java, construit le graphe de dépendances et liste les tests découverts. Utile pour vérifier que l'outil comprend bien la structure du projet.

### Syntaxe

```bash
rts analyze <chemin-projet> [--output fichier.json]
```

### Exemple

```bash
rts analyze ~/mon-projet
```

**Sortie JSON :**

```json
{
  "projectPath": "/home/user/mon-projet",
  "totalElements": 312,
  "discoveredTests": 47,
  "tests": [
    {
      "class": "com.example.service.UserServiceTest",
      "method": "createUser_withValidData_savesUser",
      "coveredElements": 8
    },
    ...
  ]
}
```

| Champ | Description |
|-------|-------------|
| `totalElements` | Nombre de classes + méthodes dans le graphe |
| `discoveredTests` | Nombre de méthodes de test détectées |
| `coveredElements` | Nombre d'éléments de production couverts par ce test |

### Écrire la sortie dans un fichier

```bash
rts analyze ~/mon-projet --output graph.json
```

---

## 3. Commande `select`

Sélectionne les tests impactés par des changements de code.

### Par diff Git

```bash
rts select --project ~/mon-projet --diff HEAD~1..HEAD
```

Tout range Git valide est accepté :

```bash
# Deux derniers commits
rts select --project . --diff HEAD~2..HEAD

# Entre deux branches
rts select --project . --diff main..feature/my-feature

# Commit spécifique
rts select --project . --diff abc123..def456
```

### Par liste de fichiers modifiés

Utile en CI quand le diff Git n'est pas disponible directement :

```bash
rts select --project ~/mon-projet \
  --changed-files "src/main/java/com/example/UserService.java,src/main/java/com/example/Order.java"
```

### Sortie JSON

```json
{
  "source": "STATIC",
  "selectedTestCount": 3,
  "selectedTests": [
    { "class": "com.example.UserServiceTest", "method": "createUser_works" },
    { "class": "com.example.UserServiceTest", "method": "deleteUser_removesFromDb" },
    { "class": "com.example.OrderServiceTest", "method": "placeOrder_callsUserService" }
  ],
  "detectedChanges": [
    { "file": "src/main/java/com/example/UserService.java",
      "class": "com.example.UserService",
      "type": "MODIFIED" }
  ],
  "reasoning": {
    "com.example.UserServiceTest#createUser_works": "Covers impacted elements: [com.example.UserService]"
  }
}
```

### Écrire le résultat dans un fichier

```bash
rts select --project . --diff HEAD~1..HEAD --output rts-result.json
```

---

## 4. Mode hybride (LLM)

En mode hybride, le sélecteur statique produit d'abord l'ensemble conservatif, puis un LLM identifie les tests qui peuvent être **ignorés** en toute sécurité. Le LLM ne peut que supprimer des tests, jamais en ajouter — la sécurité est donc garantie.

### Activer le mode hybride

```bash
rts select --project . --diff HEAD~1..HEAD --mode hybrid
```

### Configuration du LLM

Créer `rts-config.yaml` à la racine du projet cible :

```yaml
llm:
  enabled: true
  endpoint: ${RTS_LLM_ENDPOINT:http://localhost:11434}
  api-key: ${RTS_LLM_API_KEY:ollama}
  model: ${RTS_LLM_MODEL:qwen2.5-coder:7b}
  max-tokens: 2000
  temperature: 0.1
```

Définir les variables d'environnement :

```bash
# Ollama local (gratuit)
export RTS_LLM_ENDPOINT=http://localhost:11434
export RTS_LLM_MODEL=qwen2.5-coder:7b

# OpenAI
export RTS_LLM_ENDPOINT=https://api.openai.com
export RTS_LLM_API_KEY=sk-...
export RTS_LLM_MODEL=gpt-4o-mini
```

> Le flag `--mode hybrid` force `llm.enabled = true` même sans `rts-config.yaml`. Si le LLM est inaccessible, l'outil bascule automatiquement sur le mode statique avec un avertissement.

### Comparaison des modes

| | Statique | Hybride |
|---|---|---|
| **Garantie de non-régression** | Oui (conservatif) | Oui (le LLM ne peut que retirer) |
| **Réduction supplémentaire** | Non | Oui (~10-30% supplémentaire) |
| **Dépendance externe** | Aucune | LLM accessible |
| **Temps d'exécution** | Rapide | +2-10s selon le LLM |

---

## 5. Analyse d'un dépôt distant

Cloner et analyser un dépôt Git en une seule commande :

```bash
# Analyser la branche par défaut
rts analyze --url https://github.com/spring-projects/spring-petclinic.git

# Analyser une branche spécifique
rts analyze --url https://github.com/spring-projects/spring-petclinic.git --branch develop

# Sélectionner les tests sur un dépôt distant avec des fichiers modifiés connus
rts select --url https://github.com/org/repo.git \
           --changed-files "src/main/java/com/example/Foo.java" \
           --mode hybrid
```

Le dépôt est cloné dans un répertoire temporaire automatiquement supprimé après l'analyse.

---

## 6. Plugin Gradle natif

Le plugin `com.rts.plugin` ajoute une tâche `rtsTest` qui sélectionne et exécute uniquement les tests impactés, directement depuis Gradle.

### Intégrer le plugin

Dans le `settings.gradle.kts` du projet cible :

```kotlin
pluginManagement {
    includeBuild("/chemin/vers/RTSbyLLM")
}
```

Dans le `build.gradle.kts` :

```kotlin
plugins {
    java
    id("com.rts.plugin")
}

// Optionnel : valeurs par défaut
rts {
    mode = "static"   // ou "hybrid"
}
```

### Utilisation

```bash
# Sélectionner par diff Git
./gradlew rtsTest -PrtsDiff=HEAD~1..HEAD

# Sélectionner par liste de fichiers
./gradlew rtsTest -PrtsChangedFiles=src/main/java/com/example/Foo.java

# Mode hybride (LLM)
./gradlew rtsTest -PrtsDiff=HEAD~1..HEAD -PrtsMode=hybrid
```

**Sortie Gradle :**

```
> Task :rtsTest
[RTS] 2 test class(es) selected:
[RTS]   + com.example.UserServiceTest
[RTS]   + com.example.OrderServiceTest
BUILD SUCCESSFUL in 4s
```

### Priorité de configuration

Les flags `-P` en ligne de commande ont toujours la priorité sur le bloc `rts { }` dans `build.gradle.kts`.

---

## 7. Couverture JaCoCo

Quand un rapport JaCoCo est présent dans le projet, RTS l'utilise pour enrichir la précision de la sélection du niveau **classe** au niveau **méthode**.

### Emplacements détectés automatiquement

| Build tool | Chemin |
|------------|--------|
| Gradle | `build/reports/jacoco/test/jacocoTestReport.xml` |
| Maven | `target/site/jacoco/jacoco.xml` |

### Générer le rapport JaCoCo

**Gradle** — ajouter dans `build.gradle.kts` :

```kotlin
plugins {
    jacoco
}
tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}
tasks.jacocoTestReport {
    reports { xml.required = true }
}
```

```bash
./gradlew test jacocoTestReport
# → build/reports/jacoco/test/jacocoTestReport.xml
```

**Maven** — ajouter dans `pom.xml` :

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution><goals><goal>prepare-agent</goal></goals></execution>
        <execution>
            <id>report</id><phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
    </executions>
</plugin>
```

```bash
mvn test
# → target/site/jacoco/jacoco.xml
```

### Impact sur la sélection

Sans JaCoCo, RTS sait que `UserServiceTest` couvre la classe `UserService`.  
Avec JaCoCo, RTS sait exactement quelles méthodes sont couvertes :

```
coveredElements: [UserService#findById, UserService#save]
```

Si seule `UserService#delete` est modifiée et qu'aucun test ne la couvre, zéro test est sélectionné — au lieu de sélectionner `UserServiceTest` entier.

---

## 8. Cache du graphe de dépendances

Le graphe de dépendances est automatiquement mis en cache dans `.rts-cache/graph.json` à la racine du projet.

- **Cache hit** : si aucun fichier `.java` n'est plus récent que le cache, le graphe est chargé depuis le disque.
- **Cache miss** : dès qu'un fichier `.java` est modifié, le graphe est recalculé et le cache mis à jour.
- **Aucune configuration requise** : le cache est transparent.

```
.rts-cache/
  graph.json    ← graphe sérialisé (JSON)
```

> Ajouter `.rts-cache/` au `.gitignore` pour ne pas versionner le cache.

Pour forcer un recalcul, supprimer le répertoire :

```bash
rm -rf .rts-cache/
```

---

## 9. Projets multi-modules

RTS détecte automatiquement les structures multi-modules Maven et Gradle.

### Maven

```
mon-projet/
  pom.xml          ← contient <modules><module>api</module>...
  api/
    src/main/java/
    src/test/java/
  impl/
    src/main/java/
    src/test/java/
```

```bash
rts analyze ~/mon-projet      # analyse tous les modules
rts select --project ~/mon-projet --diff HEAD~1..HEAD
```

### Gradle

```
mon-projet/
  settings.gradle.kts   ← include("api", "impl", "service")
  api/src/...
  impl/src/...
  service/src/...
```

```bash
rts analyze ~/mon-projet      # 4 modules détectés (root + 3)
```

Les tests de **tous les sous-modules** sont découverts et inclus dans la sélection.

---

## 10. Intégration CI/CD

### GitHub Actions

```yaml
name: RTS — Tests ciblés

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0   # nécessaire pour le diff Git

      - name: Télécharger RTS
        run: |
          git clone https://github.com/Samir34elk/rts_by_llm.git /opt/rts
          cd /opt/rts && ./gradlew :rts-cli:installDist -q

      - name: Sélectionner les tests impactés
        run: |
          /opt/rts/rts-cli/build/install/rts-cli/bin/rts-cli select \
            --project . \
            --diff ${{ github.event.before }}..${{ github.sha }} \
            --output rts-result.json
          cat rts-result.json

      - name: Exécuter uniquement les tests sélectionnés
        run: |
          # Extraire les classes de test sélectionnées
          TESTS=$(jq -r '.selectedTests[].class' rts-result.json | sort -u | tr '\n' ',')
          echo "Tests sélectionnés : $TESTS"

          # Avec Maven
          mvn test -Dtest="$TESTS" -DfailIfNoTests=false

          # Avec Gradle (plugin RTS)
          ./gradlew rtsTest -PrtsDiff=${{ github.event.before }}..${{ github.sha }}
```

### GitLab CI

```yaml
test:rts:
  stage: test
  script:
    - git clone https://github.com/Samir34elk/rts_by_llm.git /opt/rts
    - cd /opt/rts && ./gradlew :rts-cli:installDist -q
    - |
      /opt/rts/rts-cli/build/install/rts-cli/bin/rts-cli select \
        --project $CI_PROJECT_DIR \
        --diff $CI_MERGE_REQUEST_DIFF_BASE_SHA..$CI_COMMIT_SHA \
        --output rts-result.json
    - |
      TESTS=$(jq -r '.selectedTests[].class' rts-result.json | sort -u | tr '\n' ',')
      mvn test -Dtest="$TESTS" -DfailIfNoTests=false
```

---

## 11. Référence des options

### `rts analyze`

| Option | Description | Défaut |
|--------|-------------|--------|
| `<chemin>` | Chemin vers le projet Java | — |
| `--url`, `-u` | URL Git d'un dépôt distant | — |
| `--branch`, `-b` | Branche à checkout (avec `--url`) | branche par défaut |
| `--output`, `-o` | Fichier de sortie JSON | stdout |

### `rts select`

| Option | Description | Défaut |
|--------|-------------|--------|
| `--project`, `-p` | Chemin vers le projet local | — |
| `--url`, `-u` | URL Git d'un dépôt distant | — |
| `--branch`, `-b` | Branche à checkout (avec `--url`) | branche par défaut |
| `--diff` | Range Git, ex. `HEAD~1..HEAD` | — |
| `--changed-files` | Liste de fichiers modifiés (séparés par `,`) | — |
| `--mode` | Mode de sélection : `static` ou `hybrid` | `static` |
| `--output`, `-o` | Fichier de sortie JSON | stdout |

### Plugin Gradle (`rtsTest`)

| Propriété `-P` | Bloc `rts { }` | Description |
|----------------|----------------|-------------|
| `-PrtsDiff=<range>` | `diff = "..."` | Range Git |
| `-PrtsChangedFiles=<files>` | `changedFiles = "..."` | Fichiers modifiés |
| `-PrtsMode=<mode>` | `mode = "..."` | `static` ou `hybrid` |

### Variables d'environnement LLM

| Variable | Description | Exemple |
|----------|-------------|---------|
| `RTS_LLM_ENDPOINT` | URL base du serveur LLM | `http://localhost:11434` |
| `RTS_LLM_API_KEY` | Clé API (ignorée par Ollama) | `sk-...` |
| `RTS_LLM_MODEL` | Nom du modèle | `qwen2.5-coder:7b` |
