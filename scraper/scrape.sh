#!/bin/bash
# Usage: ./scrape.sh [openai-api-key]
# If no API key, embeddings are skipped (vector search disabled, but tasks still load).
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$HOME/.runelite/leagues-ai/data/leagues-vi-tasks.db"
mkdir -p "$(dirname "$OUTPUT")"
cd "$SCRIPT_DIR/.."
if [ -n "$1" ]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home ./gradlew :scraper:run --args="$OUTPUT $1"
else
    JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home ./gradlew :scraper:run --args="$OUTPUT"
fi
echo "Database written to: $OUTPUT"
