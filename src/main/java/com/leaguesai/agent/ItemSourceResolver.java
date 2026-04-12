package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fires ONE LLM call per plan to resolve "where does an ironman get this?"
 * for every unique required item across the plan, then attaches the answers
 * back to each {@link PlannedStep} via {@code itemSourceNotes}.
 *
 * <p>One call per plan, not per item: dedupe across all steps first, then
 * ask the model in a single batch. Failures degrade gracefully — the plan
 * still loads, just without the source text.
 *
 * <p>Two-phase lookup: items found in the local {@link ItemDependencyGraph}
 * are resolved without an LLM call. Only the remainder is sent to the LLM.
 */
@Slf4j
public class ItemSourceResolver {

    /** Hard cap on a single source line so a chatty LLM can't blow up the UI. */
    static final int MAX_SOURCE_LEN = 240;

    private final LlmClient llmClient;
    private final ItemDependencyGraph itemDependencyGraph;

    public ItemSourceResolver(LlmClient llmClient) {
        this(llmClient, null);
    }

    public ItemSourceResolver(LlmClient llmClient, ItemDependencyGraph itemDependencyGraph) {
        this.llmClient = llmClient;
        this.itemDependencyGraph = itemDependencyGraph;
    }

    /**
     * Resolve sources for every unique required item in {@code steps}, then
     * return a NEW list of {@link PlannedStep}s with {@code itemSourceNotes}
     * populated. The input list is not mutated.
     *
     * <p>If the LLM call fails, returns the input list unchanged (with
     * {@code itemSourceNotes} as empty maps where missing).
     */
    public List<PlannedStep> resolveBatch(List<PlannedStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps == null ? Collections.emptyList() : steps;
        }

        Set<String> uniqueItems = collectUniqueItems(steps);
        if (uniqueItems.isEmpty()) {
            return steps;
        }

        // Phase 1: resolve items locally from the ItemDependencyGraph (no LLM cost).
        Map<String, String> dbSources = new LinkedHashMap<>();
        if (itemDependencyGraph != null && !itemDependencyGraph.isEmpty()) {
            for (String itemName : uniqueItems) {
                com.leaguesai.data.model.ItemDependency dep = itemDependencyGraph.findItemByName(itemName);
                if (dep != null) {
                    String src = buildSourceLine(dep);
                    if (src != null) dbSources.put(itemName, src);
                }
            }
            if (!dbSources.isEmpty()) {
                log.info("ItemSourceResolver: resolved {}/{} items from local DB",
                        dbSources.size(), uniqueItems.size());
            }
        }

        // Phase 2: for any items not covered by the DB, ask the LLM.
        Set<String> llmItems = new LinkedHashSet<>(uniqueItems);
        llmItems.removeAll(dbSources.keySet());

        Map<String, String> llmSources = Collections.emptyMap();
        if (!llmItems.isEmpty()) {
            try {
                String prompt = PromptBuilder.buildItemSourcePrompt(llmItems);
                String reply = llmClient.chatCompletion(
                        "You are a precise OSRS Leagues VI ironman acquisition guide.",
                        Collections.singletonList(new OpenAiClient.Message("user", prompt))
                );
                // Pass the requested-item set so we drop any keys the LLM made up.
                llmSources = parseReply(reply, llmItems);
                log.info("ItemSourceResolver: LLM resolved {} of {} remaining items",
                        llmSources.size(), llmItems.size());
            } catch (Exception e) {
                log.warn("ItemSourceResolver: LLM call failed, plan will load without item sources: {}", e.getMessage());
            }
        }

        // Merge: DB results first, then LLM results for any gaps.
        Map<String, String> sources = new LinkedHashMap<>(dbSources);
        sources.putAll(llmSources);
        log.info("ItemSourceResolver: resolved {} of {} items total", sources.size(), uniqueItems.size());

        return attach(steps, sources);
    }

    /** Collects every unique item name across all steps. */
    static Set<String> collectUniqueItems(List<PlannedStep> steps) {
        Set<String> items = new LinkedHashSet<>();
        for (PlannedStep step : steps) {
            if (step == null || step.getTask() == null) continue;
            Task t = step.getTask();
            if (t.getItemsRequired() != null) {
                items.addAll(t.getItemsRequired().keySet());
            }
            if (t.getTargetItems() != null) {
                for (Task.ItemTarget it : t.getTargetItems()) {
                    if (it != null && it.getName() != null) {
                        items.add(it.getName());
                    }
                }
            }
        }
        return items;
    }

    /**
     * Parses the LLM reply expected as one line per item formatted as
     * {@code ITEM_NAME :: source line}.
     *
     * <p>Lines that don't match the format are skipped silently. If
     * {@code allowedNames} is non-null, ONLY entries whose name appears in
     * the set (case-insensitive) are kept — this drops hallucinated items
     * the LLM invents. Source values are truncated to {@link #MAX_SOURCE_LEN}.
     */
    static Map<String, String> parseReply(String reply, Set<String> allowedNames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (reply == null || reply.isEmpty()) return out;

        // Build a case-insensitive lookup so "steel longsword" matches "Steel longsword".
        Map<String, String> caseFold = null;
        if (allowedNames != null) {
            caseFold = new java.util.HashMap<>();
            for (String n : allowedNames) {
                if (n != null) caseFold.put(n.toLowerCase(java.util.Locale.ROOT), n);
            }
        }

        for (String rawLine : reply.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            // Strip common list bullets
            if (line.startsWith("- ")) line = line.substring(2).trim();
            if (line.startsWith("* ")) line = line.substring(2).trim();

            int idx = line.indexOf("::");
            if (idx <= 0 || idx >= line.length() - 2) continue;
            String name = line.substring(0, idx).trim();
            String source = line.substring(idx + 2).trim();
            if (name.isEmpty() || source.isEmpty()) continue;

            // Whitelist filter — drop hallucinated keys.
            if (caseFold != null) {
                String canonical = caseFold.get(name.toLowerCase(java.util.Locale.ROOT));
                if (canonical == null) continue;
                name = canonical;
            }

            // Length cap so a chatty LLM can't blow up the UI.
            if (source.length() > MAX_SOURCE_LEN) {
                source = source.substring(0, MAX_SOURCE_LEN - 3) + "...";
            }

            out.put(name, source);
        }
        return out;
    }

    /** Backward-compat overload used by tests that don't care about whitelisting. */
    static Map<String, String> parseReply(String reply) {
        return parseReply(reply, null);
    }

    /**
     * Builds a concise human-readable source line from a local {@link com.leaguesai.data.model.ItemDependency}.
     * Returns {@code null} if {@code dep} is null or produces an empty result.
     */
    static String buildSourceLine(com.leaguesai.data.model.ItemDependency dep) {
        if (dep == null) return null;
        StringBuilder sb = new StringBuilder();
        String methodName = dep.getObtainMethod().name();
        sb.append(methodName.charAt(0))
          .append(methodName.substring(1).toLowerCase());
        if (dep.getSkillRequired() != null && !dep.getSkillRequired().isEmpty()) {
            sb.append(" (").append(dep.getSkillRequired())
              .append(" lv ").append(dep.getSkillLevel()).append(")");
        }
        if (dep.getSourceName() != null && !dep.getSourceName().isEmpty()) {
            sb.append(" from ").append(dep.getSourceName());
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Returns a NEW list where each step's {@code itemSourceNotes} is the
     * subset of {@code sources} relevant to that step's task.
     */
    static List<PlannedStep> attach(List<PlannedStep> steps, Map<String, String> sources) {
        List<PlannedStep> out = new ArrayList<>(steps.size());
        for (PlannedStep step : steps) {
            if (step == null) {
                out.add(null);
                continue;
            }
            Map<String, String> notes = new LinkedHashMap<>();
            Task t = step.getTask();
            if (t != null) {
                if (t.getItemsRequired() != null) {
                    for (String name : t.getItemsRequired().keySet()) {
                        String src = sources.get(name);
                        if (src != null) notes.put(name, src);
                    }
                }
                if (t.getTargetItems() != null) {
                    for (Task.ItemTarget it : t.getTargetItems()) {
                        if (it != null && it.getName() != null && !notes.containsKey(it.getName())) {
                            String src = sources.get(it.getName());
                            if (src != null) notes.put(it.getName(), src);
                        }
                    }
                }
            }
            // Wrap unmodifiable so callers (UI accordion, prompt builder) can't
            // mutate the map and corrupt the immutable PlannedStep contract.
            Map<String, String> safeNotes = notes.isEmpty()
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(notes);
            out.add(PlannedStep.builder()
                    .task(step.getTask())
                    .destination(step.getDestination())
                    .requiredItems(step.getRequiredItems())
                    .instruction(step.getInstruction())
                    .overlayData(step.getOverlayData())
                    .animation(step.getAnimation())
                    .itemSourceNotes(safeNotes)
                    .build());
        }
        return out;
    }
}
