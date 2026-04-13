#!/bin/bash
# Usage: ./scrape.sh [openai-api-key] [--skip-tasks]
# If no API key, embeddings are skipped (vector search disabled, but tasks still load).
# --skip-tasks: skip scraping Demonic Pacts League task pages (use pre-launch when wiki is incomplete).
#
# Runs two scrapers in sequence:
#   1. WikiScraper         — Demonic Pacts League tasks (skippable) + equipment items
#   2. DemonicPactsScraper — Leagues VI relics, areas, pacts
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$HOME/.runelite/leagues-ai/data/leagues-vi-tasks.db"
mkdir -p "$(dirname "$OUTPUT")"
cd "$SCRIPT_DIR/.."

export JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home

SKIP_TASKS_FLAG=""
API_KEY=""
for arg in "$@"; do
    if [ "$arg" = "--skip-tasks" ]; then
        SKIP_TASKS_FLAG="--skip-tasks"
    else
        API_KEY="$arg"
    fi
done

echo "=== [1/2] WikiScraper (tasks${SKIP_TASKS_FLAG:+ SKIPPED} + equipment items) ==="
WIKI_ARGS="$OUTPUT"
[ -n "$API_KEY" ] && WIKI_ARGS="$WIKI_ARGS $API_KEY"
[ -n "$SKIP_TASKS_FLAG" ] && WIKI_ARGS="$WIKI_ARGS $SKIP_TASKS_FLAG"
./gradlew :scraper:run --args="$WIKI_ARGS"

echo ""
echo "=== [2/2] Demonic Pacts scraper (Leagues VI relics / areas / pacts) ==="
./gradlew :scraper:runPacts --args="$OUTPUT"

echo ""
echo "Database written to: $OUTPUT"
