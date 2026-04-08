package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;

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
 *   <li>{@code "plan unlock the <Name> relic"} → RELIC</li>
 *   <li>{@code "plan unlock pact <Name>"} → PACT</li>
 *   <li>{@code "plan unlock <Name>"} → AREA (fall-through after relic/pact)</li>
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
     * Turn a phrase into a {@link GoalSpec}. Returns {@code FREEFORM} when no
     * shape matches; the caller's existing planner logic handles that case.
     *
     * <p>{@code repo} may be {@code null} (the plugin boots before the repo is
     * loaded) — in that case every lookup returns empty and the parser falls
     * through to {@link GoalType#FREEFORM}.
     */
    public static GoalSpec parse(String phrase, TaskRepository repo) {
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

        // No "plan unlock ..." shape at all. Fall through to task batch; the
        // caller (ChatService.maybeTriggerPlanner) will decide whether any of
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
