package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a user chat message or a canonical "Set as goal" phrase from
 * {@code UnlockablesPanel} into a {@link GoalSpec}. Delegates the actual
 * planning to {@link GoalPlanner}.
 *
 * <p>Recognised phrase shapes (case-insensitive):
 * <ul>
 *   <li>{@code "plan unlock the <Name> relic"} → RELIC (strict regex)</li>
 *   <li>{@code "plan unlock pact <Name>"} → PACT (strict regex)</li>
 *   <li>{@code "plan unlock <Name>"} → AREA (strict regex, after relic/pact)</li>
 *   <li>Any phrase containing "relic" + a known relic name → RELIC (fuzzy)</li>
 *   <li>Any phrase containing "pact" + a known pact name → PACT (fuzzy)</li>
 *   <li>Any phrase containing "unlock"/"area" + a known area name → AREA (fuzzy)</li>
 *   <li>Anything else that {@code ChatService} already recognised as a plan
 *       trigger → TASK_BATCH (preserves the existing flat-resolver path)</li>
 *   <li>No trigger detected → FREEFORM</li>
 * </ul>
 *
 * <p>Target name lookup is fuzzy (case-insensitive exact, then substring) via
 * {@code TaskRepository.findRelicByName} etc. A phrase like
 * {@code "plan unlock the grimoire relic"} lowercased still matches "Grimoire"
 * even though the repo stores it capitalised.
 */
public final class GoalSpecParser {

    // "plan unlock the Grimoire relic" / "plan unlock Grimoire relic"
    private static final Pattern RELIC_RE = Pattern.compile(
            "^\\s*plan\\s+unlock\\s+(?:the\\s+)?(.+?)\\s+relic\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "plan unlock pact Nature's Call"
    private static final Pattern PACT_RE = Pattern.compile(
            "^\\s*plan\\s+unlock\\s+pact\\s+(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "plan unlock Karamja" — broader, checked AFTER relic+pact so the more
    // specific shapes win.
    private static final Pattern AREA_RE = Pattern.compile(
            "^\\s*plan\\s+unlock\\s+(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private GoalSpecParser() {}

    /**
     * Turn a phrase into a {@link GoalSpec}. Backward-compat overload that
     * passes {@code null} for the item dependency graph (item detection disabled).
     */
    public static GoalSpec parse(String phrase, TaskRepository repo) {
        return parse(phrase, repo, null);
    }

    /**
     * Turn a phrase into a {@link GoalSpec}, with optional item detection.
     * Returns {@code FREEFORM} when no shape matches.
     */
    public static GoalSpec parse(String phrase, TaskRepository repo, ItemDependencyGraph itemGraph) {
        return parse(phrase, repo, itemGraph, true);
    }

    /**
     * Full overload with leagues mode gating. When {@code leaguesMode} is {@code false},
     * RELIC/PACT/AREA shapes short-circuit to FREEFORM so the ironman planner doesn't
     * attempt leagues-only goal resolution.
     */
    public static GoalSpec parse(String phrase, TaskRepository repo, ItemDependencyGraph itemGraph,
                                 boolean leaguesMode) {
        if (phrase == null || phrase.trim().isEmpty()) {
            return freeform(phrase);
        }
        if (!leaguesMode) {
            String lower = phrase.trim().toLowerCase();
            // Short-circuit leagues-only shapes in ironman mode
            if (lower.contains("relic") || lower.contains("pact")
                    || lower.matches("^\\s*plan\\s+unlock\\s+.*")) {
                return freeform(phrase);
            }
        }
        return parseInternal(phrase, repo, itemGraph);
    }

    private static GoalSpec parseInternal(String phrase, TaskRepository repo, ItemDependencyGraph itemGraph) {
        if (phrase == null || phrase.trim().isEmpty()) {
            return freeform(phrase);
        }
        String trimmed = phrase.trim();

        // Order matters: RELIC and PACT shapes are more specific than AREA.
        Matcher relicM = RELIC_RE.matcher(trimmed);
        if (relicM.matches()) {
            String name = relicM.group(1).trim();
            if (repo != null) {
                Optional<Relic> relic = repo.findRelicByName(name);
                if (relic.isPresent()) {
                    return GoalSpec.builder()
                            .type(GoalType.RELIC)
                            .targetId(relic.get().getId())
                            .targetName(relic.get().getName())
                            .rawPhrase(phrase)
                            .unlockCost(relic.get().getUnlockCost())
                            .build();
                }
            }
            // Shape matched but the target isn't in the repo — treat as task
            // batch so the old flat resolver at least tries a keyword match.
            return taskBatch(phrase);
        }

        Matcher pactM = PACT_RE.matcher(trimmed);
        if (pactM.matches()) {
            String name = pactM.group(1).trim();
            if (repo != null) {
                Optional<Pact> pact = repo.findPactByName(name);
                if (pact.isPresent()) {
                    return GoalSpec.builder()
                            .type(GoalType.PACT)
                            .targetId(pact.get().getId())
                            .targetName(pact.get().getName())
                            .rawPhrase(phrase)
                            .unlockCost(0)
                            .build();
                }
            }
            return taskBatch(phrase);
        }

        Matcher areaM = AREA_RE.matcher(trimmed);
        if (areaM.matches()) {
            String name = areaM.group(1).trim();
            if (repo != null) {
                Optional<Area> area = repo.findAreaByName(name);
                if (area.isPresent()) {
                    return GoalSpec.builder()
                            .type(GoalType.AREA)
                            .targetId(area.get().getId())
                            .targetName(area.get().getName())
                            .rawPhrase(phrase)
                            .unlockCost(area.get().getUnlockCost())
                            .build();
                }
            }
            return taskBatch(phrase);
        }

        // No "plan unlock ..." shape matched. Try a fuzzy name scan so that
        // natural-language phrases like "I want to unlock the Grimoire relic" or
        // "set goal Karamja" still resolve to the right GoalType.
        //
        // Guards: require a domain keyword ("relic", "pact", "unlock", "area")
        // before scanning to avoid false-positive matches on ordinary chat.
        if (repo != null) {
            String lower = trimmed.toLowerCase();

            // Relic: "... grimoire relic ..." / "unlock the grimoire"
            // Longest-match: pick the relic whose name is longest and contained in the phrase,
            // so "Greater Grimoire" beats "Grimoire" when both names appear in the list.
            if (lower.contains("relic")) {
                List<Relic> relics = repo.getAllRelics();
                if (relics != null) {
                    Relic best = null;
                    for (Relic r : relics) {
                        if (r != null && r.getName() != null
                                && lower.contains(r.getName().toLowerCase())) {
                            if (best == null || r.getName().length() > best.getName().length()) {
                                best = r;
                            }
                        }
                    }
                    if (best != null) {
                        return GoalSpec.builder()
                                .type(GoalType.RELIC)
                                .targetId(best.getId())
                                .targetName(best.getName())
                                .rawPhrase(phrase)
                                .unlockCost(best.getUnlockCost())
                                .build();
                    }
                }
            }

            // Pact: "... nature's call pact ..." / "select pact X"
            if (lower.contains("pact")) {
                List<Pact> pacts = repo.getAllPacts();
                if (pacts != null) {
                    Pact best = null;
                    for (Pact p : pacts) {
                        if (p != null && p.getName() != null
                                && lower.contains(p.getName().toLowerCase())) {
                            if (best == null || p.getName().length() > best.getName().length()) {
                                best = p;
                            }
                        }
                    }
                    if (best != null) {
                        return GoalSpec.builder()
                                .type(GoalType.PACT)
                                .targetId(best.getId())
                                .targetName(best.getName())
                                .rawPhrase(phrase)
                                .unlockCost(0)
                                .build();
                    }
                }
            }

            // Area: "unlock karamja" / "set goal for kourend area"
            if (lower.contains("unlock") || lower.contains("area")) {
                List<Area> areas = repo.getAllAreas();
                if (areas != null) {
                    Area best = null;
                    for (Area a : areas) {
                        if (a != null && a.getName() != null
                                && lower.contains(a.getName().toLowerCase())) {
                            if (best == null || a.getName().length() > best.getName().length()) {
                                best = a;
                            }
                        }
                    }
                    if (best != null) {
                        return GoalSpec.builder()
                                .type(GoalType.AREA)
                                .targetId(best.getId())
                                .targetName(best.getName())
                                .rawPhrase(phrase)
                                .unlockCost(best.getUnlockCost())
                                .build();
                    }
                }
            }
        }

        // Item/Craft: "I need barrows gloves" / "make a staff of air" / "smith rune platebody"
        // Guard: require a known acquisition/crafting keyword and a known item name.
        if (itemGraph != null && !itemGraph.isEmpty()) {
            String lowerPhrase = trimmed.toLowerCase();
            // Crafting verbs imply the player wants to MAKE the item, not buy it.
            boolean isCraftIntent = lowerPhrase.contains("make ") || lowerPhrase.contains("craft ")
                    || lowerPhrase.contains("smith ") || lowerPhrase.contains("fletch ")
                    || lowerPhrase.contains("brew ") || lowerPhrase.contains("cook ");
            boolean hasItemKeyword = isCraftIntent
                    || lowerPhrase.contains("get ") || lowerPhrase.contains("need ")
                    || lowerPhrase.contains("want ") || lowerPhrase.contains("farm ")
                    || lowerPhrase.contains("obtain ") || lowerPhrase.contains("i need")
                    || lowerPhrase.contains("i want") || lowerPhrase.startsWith("get ")
                    || lowerPhrase.startsWith("need ");
            if (hasItemKeyword) {
                // findLongestMatchingItem uses a pre-sorted (longest-first) name list
                // and returns on the first match — O(k) not O(n).
                com.leaguesai.data.model.ItemDependency found =
                        itemGraph.findLongestMatchingItem(lowerPhrase);
                if (found != null) {
                    // Emit CRAFT when crafting verbs are present — GoalPlanner will
                    // do a wiki recipe lookup and build material + craft steps.
                    GoalType goalType = isCraftIntent ? GoalType.CRAFT : GoalType.ITEM;
                    return GoalSpec.builder()
                            .type(goalType)
                            .targetId(found.getItemId())
                            .targetName(found.getItemName())
                            .rawPhrase(phrase)
                            .unlockCost(0)
                            .build();
                }
            }
        }

        // No shape matched. Fall through to task batch; the caller
        // (ChatService.maybeTriggerPlanner) will decide whether any of
        // its own trigger phrases fire.
        return taskBatch(phrase);
    }

    private static GoalSpec taskBatch(String phrase) {
        return GoalSpec.builder()
                .type(GoalType.TASK_BATCH)
                .targetId(null)
                .targetName(null)
                .rawPhrase(phrase)
                .unlockCost(0)
                .build();
    }

    private static GoalSpec freeform(String phrase) {
        return GoalSpec.builder()
                .type(GoalType.FREEFORM)
                .targetId(null)
                .targetName(null)
                .rawPhrase(phrase)
                .unlockCost(0)
                .build();
    }
}
