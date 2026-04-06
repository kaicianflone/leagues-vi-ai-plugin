#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
API_KEY="${1:?Usage: ./scrape.sh <openai-api-key>}"
OUTPUT="$HOME/.runelite/leagues-ai/data/leagues-vi-tasks.db"
mkdir -p "$(dirname "$OUTPUT")"
cd "$SCRIPT_DIR"
../gradlew :scraper:run --args="$API_KEY $OUTPUT"
echo "Database written to: $OUTPUT"
