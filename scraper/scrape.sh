#!/bin/bash
# Usage: ./scrape.sh [openai-api-key]
# If no API key, embeddings are skipped (vector search disabled, but tasks still load).
#
# Runs both scrapers in sequence:
#   1. WikiScraper         — Trailblazer Reloaded tasks (placeholder, phase 2 swaps to Demonic Pacts)
#   2. DemonicPactsScraper — Leagues VI relics, areas, pacts (phase 1)
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$HOME/.runelite/leagues-ai/data/leagues-vi-tasks.db"
mkdir -p "$(dirname "$OUTPUT")"
cd "$SCRIPT_DIR/.."

export JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home

echo "=== [1/2] Tasks scraper (Trailblazer Reloaded placeholder) ==="
if [ -n "$1" ]; then
    ./gradlew :scraper:run --args="$OUTPUT $1"
else
    ./gradlew :scraper:run --args="$OUTPUT"
fi

echo ""
echo "=== [2/2] Demonic Pacts scraper (Leagues VI relics / areas / pacts) ==="
./gradlew :scraper:runPacts --args="$OUTPUT"

echo ""
echo "Database written to: $OUTPUT"
