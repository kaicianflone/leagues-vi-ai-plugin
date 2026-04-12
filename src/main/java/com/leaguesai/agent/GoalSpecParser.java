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
            if (lower.contains("relic")) {
                List<Relic> relics = repo.getAllRelics();
                if (relics != null) {
                    for (Relic r : relics) {
                        if (r != null && r.getName() != null
                                && lower.contains(r.getName().toLowerCase())) {
                            return GoalSpec.builder()
                                    .type(GoalType.RELIC)
                                    .targetId(r.getId())
                                    .targetName(r.getName())
                                    .rawPhrase(phrase)
                                    .unlockCost(r.getUnlockCost())
                                    .build();
                        }
                    }
                }
            }

            // Pact: "... nature's call pact ..." / "select pact X"
            if (lower.contains("pact")) {
                List<Pact> pacts = repo.getAllPacts();
                if (pacts != null) {
                    for (Pact p : pacts) {
                        if (p != null && p.getName() != null
                                && lower.contains(p.getName().toLowerCase())) {
                            return GoalSpec.builder()
                                    .type(GoalType.PACT)
                                    .targetId(p.getId())
                                    .targetName(p.getName())
                                    .rawPhrase(phrase)
                                    .unlockCost(0)
                                    .build();
                        }
                    }
                }
            }

            // Area: "unlock karamja" / "set goal for kourend area"
            if (lower.contains("unlock") || lower.contains("area")) {
                List<Area> areas = repo.getAllAreas();
                if (areas != null) {
                    for (Area a : areas) {
                        if (a != null && a.getName() != null
                                && lower.contains(a.getName().toLowerCase())) {
                            return GoalSpec.builder()
                                    .type(GoalType.AREA)
                                    .targetId(a.getId())
                                    .targetName(a.getName())
                                    .rawPhrase(phrase)
                                    .unlockCost(a.getUnlockCost())
                                    .build();
                        }
                    }
                }
            }
        }

        // Item: "I need barrows gloves" / "how do I get dragon scimitar" / "get rune platebody"
        // Guard: require "get", "need", "make", "craft", "smith", "fletch", "brew", "cook"
        // and the itemGraph must have data. Fuzzy name match against graph content.
        if (itemGraph != null && !itemGraph.isEmpty()) {
            String lowerPhrase = trimmed.toLowerCase();
            boolean hasItemKeyword = lowerPhrase.contains("get ") || lowerPhrase.contains("need ")
                    || lowerPhrase.contains("make ") || lowerPhrase.contains("craft ")
                    || lowerPhrase.contains("smith ") || lowerPhrase.contains("fletch ")
                    || lowerPhrase.contains("brew ") || lowerPhrase.contains("cook ");
            if (hasItemKeyword) {
                // Scan all known item names and check if the phrase contains them.
                // findItemByName() does an exact key lookup and can't match partial phrases
                // like "i need dragon scimitar" against the key "dragon scimitar".
                com.leaguesai.data.model.ItemDependency found = null;
                int bestLen = 0;
                for (String knownName : itemGraph.knownNames()) {
                    if (knownName.length() > bestLen && lowerPhrase.contains(knownName)) {
                        com.leaguesai.data.model.ItemDependency candidate = itemGraph.findItemByName(knownName);
                        if (candidate != null) {
                            found = candidate;
                            bestLen = knownName.length();
                        }
                    }
                }
                if (found != null) {
                    return GoalSpec.builder()
                            .type(GoalType.ITEM)
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
