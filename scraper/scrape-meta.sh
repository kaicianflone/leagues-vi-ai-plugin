#!/bin/bash
# Usage: ./scrape-meta.sh
# Scrapes ONLY relics, areas, and pacts from the OSRS Wiki (no tasks).
# Tasks stay empty until launch day (2026-04-15) when the full scrape.sh should run.
#
# Safe to run repeatedly — upserts, won't duplicate.
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="$HOME/.runelite/leagues-ai/data/leagues-vi-tasks.db"
mkdir -p "$(dirname "$OUTPUT")"
cd "$SCRIPT_DIR/.."

export JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home

echo "=== Demonic Pacts scraper (relics / areas / pacts only) ==="
./gradlew :scraper:runPacts --args="$OUTPUT"

echo ""
echo "Database written to: $OUTPUT"
echo "Tasks table left empty — run ./scraper/scrape.sh on launch day (2026-04-15)."
