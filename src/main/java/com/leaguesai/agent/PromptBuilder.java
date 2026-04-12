package com.leaguesai.agent;

import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.Area;
import com.leaguesai.data.model.GearItem;
import com.leaguesai.data.model.Pact;
import com.leaguesai.data.model.Relic;
import com.leaguesai.data.model.Task;
import net.runelite.api.Skill;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PromptBuilder {

    // Utility class — no instances
    private PromptBuilder() {}

    /**
     * Builds a Markdown system prompt summarising the player's current state.
     * Backward-compatible single-arg form — delegates to the overload below
     * with no relevant tasks.
     */
    public static String buildSystemPrompt(PlayerContext ctx) {
        return buildSystemPrompt(ctx, Collections.emptyList(), null);
    }

    /**
     * Two-arg overload kept for existing tests. Delegates to three-arg.
     */
    public static String buildSystemPrompt(PlayerContext ctx, List<Task> relevantTasks) {
        return buildSystemPrompt(ctx, relevantTasks, null);
    }

    /**
     * Three-arg overload — all personas active, no gear block (backward compat).
     */
    public static String buildSystemPrompt(PlayerContext ctx,
                                           List<Task> relevantTasks,
                                           TaskRepository repo) {
        return buildSystemPromptImpl(ctx, relevantTasks, repo, null, null);
    }

    /**
     * Full system prompt with gear context block appended, all personas active.
     */
    public static String buildSystemPrompt(PlayerContext ctx, List<Task> relevantTasks,
                                           TaskRepository repo, List<GearItem> relevantGear) {
        return buildSystemPromptImpl(ctx, relevantTasks, repo, relevantGear, null);
    }

    /**
     * Full system prompt with both gear context and persona filtering.
     *
     * @param selectedPersonas persona IDs to include; null = all personas
     */
    public static String buildSystemPrompt(PlayerContext ctx, List<Task> relevantTasks,
                                           TaskRepository repo, List<GearItem> relevantGear,
                                           java.util.List<String> selectedPersonas) {
        return buildSystemPromptImpl(ctx, relevantTasks, repo, relevantGear, selectedPersonas);
    }

    /**
     * Master implementation. All public overloads delegate here.
     */
    private static String buildSystemPromptImpl(PlayerContext ctx,
                                                List<Task> relevantTasks,
                                                TaskRepository repo,
                                                List<GearItem> relevantGear,
                                                java.util.List<String> selectedPersonas) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert OSRS Leagues VI (Demonic Pacts) coach.\n\n");

        sb.append(buildIronmanDoctrine(selectedPersonas));
        sb.append(buildEchoBossesSection());

        sb.append("## How To Help The Player\n");
        sb.append("1. **Read what you already know.** The Player State, Skills, Inventory, ");
        sb.append("Equipment, Unlocked Areas, and Completed Tasks sections below contain LIVE ");
        sb.append("data from the player's RuneLite client. Always check these BEFORE asking ");
        sb.append("the player to repeat that information. If you can answer from context, do so.\n");
        sb.append("2. **Ask clarifying questions when the goal is ambiguous.** Examples: ");
        sb.append("\"Do you want max points per hour, or fastest area unlock?\" ");
        sb.append("\"Are you OK with combat tasks or skilling only?\" ");
        sb.append("\"Which relics are you targeting?\" Get the player's intent clear before planning.\n");
        sb.append("3. **Propose plans in plain English first.** Walk through the suggested ");
        sb.append("approach conversationally so the player can correct course.\n");
        sb.append("4. **To commit a real plan,** the player needs to use a phrase the ");
        sb.append("planner recognises. The plugin watches for natural phrasings like ");
        sb.append("\"make me a plan for karamja easy tasks\", \"complete all the karamja easy tasks\", ");
        sb.append("\"plan out karamja easy\", \"do all the karamja easy tasks\", \"i want to finish all karamja easy\", ");
        sb.append("or the explicit `/plan complete all karamja easy tasks`. Any of these will trigger the ");
        sb.append("deterministic planner over the local task database and load an Active Plan ");
        sb.append("that you'll see in subsequent messages. When suggesting next steps, naturally ");
        sb.append("encourage one of these phrasings — don't insist on the slash command. Do NOT ");
        sb.append("pretend to have a plan loaded unless the \"Active Plan\" section below shows one.\n");
        sb.append("5. **Never invent tasks.** Only reference tasks that appear in the Active ");
        sb.append("Plan or Relevant Tasks sections, or that you know from genuine OSRS knowledge. ");
        sb.append("If you're unsure whether a task exists in Leagues VI, say so instead of guessing.\n\n");

        // Player State
        sb.append("## Player State\n");
        sb.append("- Combat Level: ").append(ctx.getCombatLevel()).append("\n");
        sb.append("- League Points: ").append(ctx.getLeaguePoints()).append("\n");
        if (ctx.getLocation() != null) {
            sb.append("- Location: ").append(ctx.getLocation().toString()).append("\n");
        }
        sb.append("\n");

        // Skills
        sb.append("## Skills\n");
        if (ctx.getLevels() != null && !ctx.getLevels().isEmpty()) {
            for (Map.Entry<Skill, Integer> entry : ctx.getLevels().entrySet()) {
                sb.append("- ").append(entry.getKey().name()).append(": ")
                        .append(entry.getValue()).append("\n");
            }
        }
        sb.append("\n");

        // Inventory
        sb.append("## Inventory\n");
        appendItemSection(sb, ctx.getInventory(), 28);
        sb.append("\n");

        // Equipment
        sb.append("## Equipment\n");
        appendItemSection(sb, ctx.getEquipment(), Integer.MAX_VALUE);
        sb.append("\n");

        // Unlocked Areas
        sb.append("## Unlocked Areas\n");
        if (ctx.getUnlockedAreas() != null && !ctx.getUnlockedAreas().isEmpty()) {
            for (String area : ctx.getUnlockedAreas()) {
                sb.append("- ").append(area).append("\n");
            }
        } else {
            sb.append("- None\n");
        }
        sb.append("\n");

        // Completed Tasks
        int completedCount = ctx.getCompletedTasks() != null ? ctx.getCompletedTasks().size() : 0;
        sb.append("## Completed Tasks: ").append(completedCount).append("\n\n");

        // Current Goal
        sb.append("## Current Goal\n");
        sb.append(ctx.getCurrentGoal() != null ? ctx.getCurrentGoal() : "None").append("\n\n");

        // Current Plan
        List<PlannedStep> plan = ctx.getCurrentPlan();
        if (plan != null && !plan.isEmpty()) {
            int limit = Math.min(20, plan.size());
            sb.append("## Active Plan (").append(plan.size()).append(" tasks total, showing first ")
                    .append(limit).append(")\n");
            for (int i = 0; i < limit; i++) {
                PlannedStep step = plan.get(i);
                String instruction = step.getInstruction() != null ? step.getInstruction() : "(no instruction)";
                sb.append(i + 1).append(". ").append(instruction);
                if (step.getTask() != null && step.getTask().getArea() != null) {
                    sb.append(" [").append(step.getTask().getArea()).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n**IMPORTANT: An active plan is loaded above. When the user asks about tasks, ");
            sb.append("walk them through this plan in order. Do NOT invent tasks — only reference the ");
            sb.append("ones in the Active Plan list. Use the exact names shown.**\n");
        } else {
            sb.append("## Current Plan\n- No plan set. User can trigger planning by saying things like ");
            sb.append("\"complete all karamja easy tasks\".\n");
        }

        // Unlockables reference: relics / areas / pacts. Only included when
        // the repo is present AND has data. This is the LLM's source of
        // truth for "what does Grimoire cost?" questions — without these
        // sections the model guesses.
        if (repo != null) {
            appendRelicsSection(sb, repo);
            appendAreasSection(sb, repo, ctx);
            appendPactsSection(sb, repo);
        }

        // Relevant Tasks (from RAG retrieval) — only included when non-empty
        if (relevantTasks != null && !relevantTasks.isEmpty()) {
            sb.append("\n## Relevant Tasks\n");
            for (Task task : relevantTasks) {
                if (task == null) continue;
                sb.append("- ").append(task.getName())
                        .append(" [").append(task.getDifficulty())
                        .append(", ").append(task.getPoints()).append("pts")
                        .append(", ").append(task.getArea()).append("]")
                        .append(" (id: ").append(task.getId()).append(")");
                if (task.getDescription() != null && !task.getDescription().isEmpty()) {
                    sb.append(" — ").append(task.getDescription());
                }
                sb.append("\n");
            }
        }

        // Gear context block — only when gear items are provided
        if (relevantGear != null && !relevantGear.isEmpty()) {
            sb.append(buildGearContext(relevantGear, ctx));
        }

        return sb.toString();
    }

    /**
     * Builds a Markdown gear context block for injection into the system prompt.
     * Called when a build is activated or gear-related questions are detected.
     * Renders each item's slot, key stats, and skill requirements so the LLM
     * can reason about gear choices without hallucinating stats.
     *
     * @param relevantItems items to include (ordered by relevance — caller filters/ranks)
     * @param ctx           player context for cross-referencing equipped items
     * @return non-null Markdown string; empty string if no items
     */
    public static String buildGearContext(List<GearItem> relevantItems, PlayerContext ctx) {
        if (relevantItems == null || relevantItems.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n## Gear Reference (RAG context — verified wiki stats)\n");
        sb.append("Use these stats when advising on gear choices. Stats are factual and should not be second-guessed.\n");

        for (GearItem item : relevantItems) {
            if (item == null) continue;
            sb.append("### ").append(item.getName())
              .append(" [").append(item.getSlot()).append("]");

            // Flag if player currently has it equipped
            if (ctx != null && ctx.getEquipment() != null
                    && ctx.getEquipment().containsKey(item.getName())) {
                sb.append(" (currently equipped)");
            }
            sb.append("\n");

            // Key offensive stats (only show non-zero)
            appendStatIfNonZero(sb, "Stab", item.getAttackStab());
            appendStatIfNonZero(sb, "Slash", item.getAttackSlash());
            appendStatIfNonZero(sb, "Crush", item.getAttackCrush());
            appendStatIfNonZero(sb, "Magic atk", item.getAttackMagic());
            appendStatIfNonZero(sb, "Range atk", item.getAttackRanged());
            appendStatIfNonZero(sb, "Str bonus", item.getMeleeStrength());
            appendStatIfNonZero(sb, "Magic dmg", item.getMagicDamage());
            appendStatIfNonZero(sb, "Range str", item.getRangedStrength());
            appendStatIfNonZero(sb, "Prayer", item.getPrayerBonus());

            // Key defensive stats (only non-zero)
            appendStatIfNonZero(sb, "Def stab", item.getDefenceStab());
            appendStatIfNonZero(sb, "Def slash", item.getDefenceSlash());
            appendStatIfNonZero(sb, "Def crush", item.getDefenceCrush());
            appendStatIfNonZero(sb, "Def magic", item.getDefenceMagic());
            appendStatIfNonZero(sb, "Def range", item.getDefenceRanged());

            // Skill requirements
            if (item.getSkillRequirements() != null && !item.getSkillRequirements().isEmpty()) {
                sb.append("- Requires: ");
                item.getSkillRequirements().forEach((skill, level) ->
                    sb.append(skill).append(" ").append(level).append(" "));
                sb.append("\n");
            }

            if (item.getWikiUrl() != null) {
                sb.append("- Wiki: ").append(item.getWikiUrl()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void appendStatIfNonZero(StringBuilder sb, String label, int value) {
        if (value != 0) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    /**
     * Append a condensed relic reference: grouped by tier, name + cost +
     * one-line effect. Skipped entirely when the repo has no relics loaded
     * (e.g. scraper hasn't run).
     */
    private static void appendRelicsSection(StringBuilder sb, TaskRepository repo) {
        List<Relic> relics = repo.getAllRelics();
        if (relics == null || relics.isEmpty()) return;
        sb.append("\n## Relics (Demonic Pacts League rewards)\n");
        sb.append("Pick one relic per tier. Cost is in league points.\n");
        for (Relic r : relics) {
            if (r == null || r.getName() == null) continue;
            sb.append("- Tier ").append(r.getTier())
                    .append(" — ").append(r.getName());
            if (r.getUnlockCost() > 0) {
                sb.append(" (").append(r.getUnlockCost()).append(" pts)");
            }
            if (r.getDescription() != null && !r.getDescription().isEmpty()) {
                String desc = r.getDescription();
                if (desc.length() > 120) desc = desc.substring(0, 117) + "...";
                sb.append(": ").append(desc);
            }
            sb.append("\n");
        }
    }

    /**
     * Append a condensed area reference with unlock cost and whether the
     * player already has it. Uses {@code ctx.unlockedAreas} as the source of
     * truth for unlock state. Missing unlock costs render as "cost TBD" so
     * the LLM knows the data is incomplete (wiki hasn't published costs yet
     * pre-launch).
     */
    private static void appendAreasSection(StringBuilder sb, TaskRepository repo, PlayerContext ctx) {
        List<Area> areas = repo.getAllAreas();
        if (areas == null || areas.isEmpty()) return;
        Set<String> unlocked = ctx != null && ctx.getUnlockedAreas() != null
                ? ctx.getUnlockedAreas() : Collections.emptySet();
        sb.append("\n## Areas\n");
        sb.append("Varlamore and Karamja are the universal starting areas. Up to 3 more can be unlocked during the league.\n");
        for (Area a : areas) {
            if (a == null || a.getName() == null) continue;
            boolean isUnlocked = unlocked.contains(a.getId()) || unlocked.contains(a.getName());
            sb.append("- ").append(a.getName());
            if (a.getUnlockCost() > 0) {
                sb.append(" (").append(a.getUnlockCost()).append(" pts)");
            } else {
                sb.append(" (cost TBD)");
            }
            sb.append(isUnlocked ? " — UNLOCKED" : " — locked");
            sb.append("\n");
        }
    }

    /**
     * Append the pacts reference plus the 40-slot / 3-respec doctrine. Pact
     * effects are the verbatim wiki text from the scraper (single line per
     * pact is fine since the side panel truncates anyway).
     */
    private static void appendPactsSection(StringBuilder sb, TaskRepository repo) {
        List<Pact> pacts = repo.getAllPacts();
        if (pacts == null || pacts.isEmpty()) return;
        sb.append("\n## Demonic Pacts\n");
        sb.append("The player can select up to **40 pacts total** and has **3 full respecs** ");
        sb.append("available during the league. Pact picks are the main strategic choice in ");
        sb.append("Leagues VI — treat them seriously and always explain trade-offs when the player asks.\n");
        int count = 0;
        for (Pact p : pacts) {
            if (p == null || p.getName() == null) continue;
            sb.append("- ").append(p.getName());
            if (p.getNodeType() != null && !p.getNodeType().isEmpty()) {
                sb.append(" [").append(p.getNodeType()).append("]");
            }
            if (p.getEffect() != null && !p.getEffect().isEmpty()) {
                String eff = p.getEffect();
                if (eff.length() > 140) eff = eff.substring(0, 137) + "...";
                sb.append(": ").append(eff);
            }
            sb.append("\n");
            count++;
            // Cap at 40 rows — enough to cover the whole pact list without
            // blowing the prompt budget.
            if (count >= 40) break;
        }
    }

    private static void appendItemSection(StringBuilder sb, Map<String, Integer> items, int cap) {
        if (items == null || items.isEmpty()) {
            sb.append("- (empty)\n");
            return;
        }
        int count = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (count >= cap) break;
            sb.append("- ").append(entry.getKey());
            Integer qty = entry.getValue();
            if (qty != null && qty > 1) {
                sb.append(" x").append(qty);
            }
            sb.append("\n");
            count++;
        }
    }

    // -------------------------------------------------------------------------
    // Personas
    // -------------------------------------------------------------------------

    /**
     * Returns the coaching doctrine block filtered to {@code selectedPersonas}.
     * Pass {@code null} or an empty list to include all personas (backward compat).
     */
    public static String buildIronmanDoctrine(java.util.List<String> selectedPersonas) {
        boolean all = selectedPersonas == null || selectedPersonas.isEmpty();
        java.util.Set<String> active = all ? null : new java.util.HashSet<>(selectedPersonas);

        StringBuilder sb = new StringBuilder();
        sb.append("## Coaching Personas\n");
        sb.append("You are coaching an OSRS Leagues VI (Demonic Pacts) player. ");
        sb.append("The following expert voices are active — apply each one's lens when relevant.\n\n");

        if (all || active.contains("points_chaser")) {
            sb.append("**The Points Chaser** — treats every minute as a pts/hr calculation. ");
            sb.append("Always name the points per task and tasks-per-hour at the player's level. ");
            sb.append("Pushes parallel completion: if two tasks share a location, name them together. ");
            sb.append("Flags low-value time sinks and surfaces the highest-pts tasks the player can realistically do next.\n\n");
        }

        if (all || active.contains("build_architect")) {
            sb.append("**Build Architect** — filters every recommendation through relic and pact synergy. ");
            sb.append("Before suggesting a task, asks 'does this serve the build?' Names which relics are boosted ");
            sb.append("by the activity and which pacts apply. Tracks BiS gear milestones for the player's combat ");
            sb.append("style and routes tasks toward those drops. Never recommends a detour that doesn't serve the active build.\n\n");
        }

        if (all || active.contains("explorer")) {
            sb.append("**The Explorer** — expert in Varlamore and area unlock strategy. ");
            sb.append("Treats Varlamore as home base — knows all Varlamore tasks and recommends exhausting ");
            sb.append("high-value ones before branching. Reasons about which 3 extra areas give the best ");
            sb.append("task density + echo boss + relic synergy for the player's build. Groups tasks by map ");
            sb.append("region to eliminate travel. When tasks overlap multiple areas, names the optimal unlock order.\n\n");
        }

        if (all || active.contains("sprinter")) {
            sb.append("**The Sprinter** — the league ends. Every suggestion comes with a pace check: ");
            sb.append("'at this rate, do you have time to hit X league points before the deadline?' ");
            sb.append("Deprioritises long grinds with small payoffs. Recommends skipping low-point tasks ");
            sb.append("when higher-value opportunities are available. Calls out honestly if the player is falling behind.\n\n");
        }

        if (all || active.contains("theorist")) {
            sb.append("**The Theorist** — numbers drive every decision. Names the exact DPS increase from ");
            sb.append("a relic, the XP multiplier from a pact, the break-even point for a gear grind. ");
            sb.append("When two approaches seem equivalent, works out which is strictly better and by how much. ");
            sb.append("If a tradeoff is genuinely close, says so and lists the deciding variable.\n\n");
        }

        if (all || active.contains("settled")) {
            sb.append("**Settled (patient grinder)** — methodical and thorough. Never recommends skipping ");
            sb.append("prerequisite chains. Always asks 'what does the player need before they can do this?' ");
            sb.append("Prefers systematic progression. Explicitly states when a suggested step has prerequisites ");
            sb.append("the player hasn't met and gives the fastest path to meeting them.\n\n");
        }

        if (all || active.contains("sirpugger")) {
            sb.append("**SirPugger (leagues meta expert)** — deep Leagues VI meta knowledge. Knows which tasks ");
            sb.append("are commonly skipped by top players and why, which echo bosses are highest priority, ");
            sb.append("and how the pact tree shapes the optimal task route. Corrects common misconceptions. ");
            sb.append("When suggesting echo boss content, always names the echo orb requirement and prep route.\n\n");
        }

        if (all || active.contains("pragmatist")) {
            sb.append("**The Pragmatist** — anchors every suggestion to what the player can do RIGHT NOW ");
            sb.append("given their current levels, gear, and unlocked areas (read the Player State sections above). ");
            sb.append("Never suggests content requiring skills or areas the player doesn't have. When the ideal ");
            sb.append("path is blocked, immediately names the best available alternative and the shortest path to readiness.\n\n");
        }

        if (all || active.contains("skill_spec")) {
            sb.append("**Skill Spec (number-cruncher)** — treats every skill level as a resource. When the player ");
            sb.append("needs a level for a task, names the fastest ironman method to get there and the XP required. ");
            sb.append("Optimizes skilling routes to double up on league tasks. Tracks skill milestones that unlock ");
            sb.append("new task categories and flags when leveling a skill opens a cluster of high-value tasks.\n\n");
        }

        sb.append("**Cross-persona rules (always apply):**\n");
        sb.append("- NEVER say 'buy X' or 'trade for X' — ironman-only sources only.\n");
        sb.append("- When you mention an item, state where an ironman gets it in one phrase.\n");
        sb.append("- For combat tasks, state the recommended gear tier the player actually owns (check Equipment above).\n");
        sb.append("- Best available method, not popular method — if the meta route requires content the player ");
        sb.append("hasn't unlocked, fall back to the best available and say why.\n\n");

        return sb.toString();
    }

    /**
     * Leagues VI Echo Boss reference — embedded in every system prompt so the
     * LLM knows which echo variant drops which unique item, the drop rates, and
     * the echo orb access mechanic. Each region has exactly one echo boss.
     *
     * <p>Key mechanic: obtain the echo orb from the NORMAL boss variant (it is
     * an additional drop on the regular drop table), then carry it in your
     * inventory to access the echo variant in that same region.
     */
    static String buildEchoBossesSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Echo Bosses & Echo Equipment (Leagues VI)\n");
        sb.append("Each region has one echo boss variant. To access it, you must first obtain ");
        sb.append("that region's **echo orb** as an additional drop from the normal boss, ");
        sb.append("then carry the orb in your inventory when entering the boss area. ");
        sb.append("Echo orbs are region-locked — Cerberus's orb only works for Cerberus (Echo), etc.\n\n");
        sb.append("| Region | Echo Boss | Unique Drop | Rate | Key Effect |\n");
        sb.append("|---|---|---|---|---|\n");
        sb.append("| Varlamore | Amoxliatl (Echo) | Infernal tecpatl | 1/30 | 2H melee weapon; 10% demonbane; SA hits 4x at 25% dmg boost |\n");
        sb.append("| Asgarnia | Cerberus (Echo) | Fang of the hound | 1/25 | Dagger; 5% proc Flames of Cerberus; SA guarantees proc |\n");
        sb.append("| Kandarin | Thermonuclear smoke devil (Echo) | Devil's element + Shadowflame quadrant | 1/25 each | Shield: +30% elemental weakness all elements; Staff: fires 2nd spell at 40% dmg, all spellbooks, infinite elemental runes |\n");
        sb.append("| Kourend | Hespori (Echo) | Nature's recurve | 1/15 | Bow; on hit 50% chance restore 10% HP and prayer from dmg dealt |\n");
        sb.append("| Fremennik | Dagannoth Kings (Echo) | V's helm | 1/25 | Helm; 5% reduce incoming hit to 0; next SA free within 60s; counts as imbued slayer helm |\n");
        sb.append("| Wilderness | King Black Dragon (Echo) | King's barrage | 1/25 | Crossbow; always shoots 2 bolts; 2nd bolt is ice attack (freeze 24 ticks); toggleable |\n");
        sb.append("| Morytania | Grotesque Guardians (Echo) | Lithic sceptre | 1/25 | Stacks 14 shatter charges/hit; SA consumes for burst damage; charged with earth+body runes |\n");
        sb.append("| Desert | Kalphite Queen (Echo) | Drygore blowpipe | 1/20 | Same stats as blowpipe; +25% chance to burn on hit |\n");
        sb.append("| Tirannwn | Corrupted Hunllef (Echo) | Crystal blessing | 1/15 | Increases dmg and accuracy for all combat styles; bonus synergy with crystal armour pieces |\n");
        sb.append("\n**When the player asks about echo items or echo bosses, use this table — do NOT guess drop rates or effects.**\n\n");
        return sb.toString();
    }

    /**
     * No-arg form — includes all personas. Used by tests and overloads that
     * don't have access to user preferences.
     */
    static String buildIronmanDoctrine() {
        return buildIronmanDoctrine(null);
    }

    /**
     * Prompt for the every-5-minute "coach pulse" — a single short sentence
     * the heartbeat displays in both panels. Must fit in ~110 chars.
     */
    public static String buildCoachPulsePrompt(PlayerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Give me ONE short sentence (max 110 characters) of in-the-moment coaching for the player ");
        sb.append("based on their current state above. Be encouraging if they're on pace, gently push if they're stalled, ");
        sb.append("and celebrate if they just hit a milestone. Use one emoji at the start. No preamble, just the sentence.");
        return sb.toString();
    }

    /**
     * Prompt for {@link ItemSourceResolver}. Asks for one line per item naming
     * the best ironman acquisition path, including the prereq chain for makeables.
     */
    public static String buildItemSourcePrompt(java.util.Collection<String> itemNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("For each item below, give the best ironman acquisition path in OSRS Leagues VI. ");
        sb.append("ONE line per item, naming the source NPC / skill method / drop. ");
        sb.append("For makeable items, include the prerequisite chain back to a gatherable resource. ");
        sb.append("Never recommend GE, trade, or group-ironman shortcuts. Be terse.\n\n");
        sb.append("Format strictly as:\n");
        sb.append("ITEM_NAME :: source line\n\n");
        sb.append("Items:\n");
        for (String name : itemNames) {
            sb.append("- ").append(name).append("\n");
        }
        return sb.toString();
    }

    // Persona display names + review focus for persona review prompt
    private static final String[][] PERSONA_REVIEW_DEFS = {
            {"points_chaser",  "Points Chaser",   "obsesses over pts/hr, task density, and parallel completion opportunities"},
            {"build_architect","Build Architect",  "obsesses over relic/pact synergy and whether each task serves the chosen build"},
            {"explorer",       "The Explorer",     "obsesses over area unlock timing, Varlamore task density, and geographic batching"},
            {"sprinter",       "The Sprinter",     "obsesses over pace — is this plan completable in the league window?"},
            {"theorist",       "The Theorist",     "obsesses over exact math: DPS gain, XP efficiency, and synergy numbers"},
            {"settled",        "Settled",          "obsesses over prerequisite completeness — no skipped steps"},
            {"sirpugger",      "SirPugger",        "obsesses over Leagues VI meta: echo boss priority, pact tree routing"},
            {"pragmatist",     "The Pragmatist",   "obsesses over whether the plan is achievable with the player's current stats and unlocks"},
            {"skill_spec",     "Skill Spec",       "obsesses over XP/hr bottlenecks and skill milestones unlocking new task clusters"},
    };

    /**
     * Prompt for {@link PersonaReviewer}. Uses the active personas from
     * {@code selectedPersonas} (null = all). Each persona critiques the plan
     * from its lens. Optional {@code priorCritique} lets personas acknowledge
     * prior feedback on re-review rounds.
     */
    public static String buildPersonaReviewPrompt(String goal, List<PlannedStep> plan,
                                                   java.util.List<String> selectedPersonas,
                                                   String priorCritique) {
        java.util.Set<String> active = selectedPersonas == null || selectedPersonas.isEmpty()
                ? null : new java.util.HashSet<>(selectedPersonas);

        // Collect which personas are active
        java.util.List<String[]> active_defs = new java.util.ArrayList<>();
        for (String[] def : PERSONA_REVIEW_DEFS) {
            if (active == null || active.contains(def[0])) {
                active_defs.add(def);
            }
        }
        if (active_defs.isEmpty()) {
            // Fallback: always include at least points_chaser + pragmatist
            for (String[] def : PERSONA_REVIEW_DEFS) {
                if (def[0].equals("points_chaser") || def[0].equals("pragmatist")) {
                    active_defs.add(def);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(active_defs.size())
          .append(" OSRS Leagues VI coaching experts adversarially reviewing the plan below.\n\n");

        for (String[] def : active_defs) {
            sb.append("- **").append(def[1]).append("**: ").append(def[2]).append("\n");
        }
        sb.append("\nEach expert picks the SINGLE biggest issue with this plan from their lens, in one sentence. ");
        sb.append("Then output a verdict: \"Verdict: keep\" (plan is solid), \"Verdict: revise\" (minor fixes), ");
        sb.append("or \"Verdict: rebuild\" (fundamental problems require a new approach).\n\n");

        if (priorCritique != null && !priorCritique.isEmpty()) {
            sb.append("**Prior review round feedback (acknowledge if addressed):**\n");
            sb.append(priorCritique).append("\n\n");
        }

        sb.append("Output format (exact — one line per expert, then Verdict):\n");
        for (String[] def : active_defs) {
            sb.append(def[1]).append(": <one sentence>\n");
        }
        sb.append("Verdict: <keep|revise|rebuild>\n\n");

        sb.append("Goal: ").append(goal == null ? "(none)" : goal).append("\n\n");
        sb.append("Plan:\n");
        if (plan != null) {
            int limit = Math.min(plan.size(), 25);
            for (int i = 0; i < limit; i++) {
                PlannedStep s = plan.get(i);
                String instr = s.getInstruction() != null ? s.getInstruction() : "(step)";
                sb.append(i + 1).append(". ").append(instr);
                if (s.getTask() != null && s.getTask().getArea() != null) {
                    sb.append(" [").append(s.getTask().getArea()).append("]");
                }
                sb.append("\n");
            }
            if (plan.size() > limit) {
                sb.append("(+ ").append(plan.size() - limit).append(" more tasks)\n");
            }
        }
        return sb.toString();
    }

    /** Backward-compat overload — all personas, no prior critique. */
    public static String buildPersonaReviewPrompt(String goal, List<PlannedStep> plan) {
        return buildPersonaReviewPrompt(goal, plan, null, null);
    }

    /**
     * Prompt for plan refinement: asks the LLM to reorder the existing task
     * list in response to persona critiques. Returns a JSON array of task IDs
     * in the revised recommended order.
     */
    public static String buildPlanRefinementPrompt(String goal, List<PlannedStep> plan,
                                                    String personaCritique) {
        StringBuilder sb = new StringBuilder();
        sb.append("The persona reviewers have critiqued the Leagues VI plan below and requested a rebuild.\n\n");
        sb.append("**Their critique:**\n").append(personaCritique).append("\n\n");
        sb.append("**Goal:** ").append(goal == null ? "(none)" : goal).append("\n\n");
        sb.append("**Current task order:**\n");
        if (plan != null) {
            int limit = Math.min(plan.size(), 30);
            for (int i = 0; i < limit; i++) {
                PlannedStep s = plan.get(i);
                String id = s.getTask() != null ? s.getTask().getId() : "?";
                String instr = s.getInstruction() != null ? s.getInstruction() : "(step)";
                String area = (s.getTask() != null && s.getTask().getArea() != null)
                        ? " [" + s.getTask().getArea() + "]" : "";
                sb.append("- id:\"").append(id).append("\" — ").append(instr).append(area).append("\n");
            }
        }
        sb.append("\nRe-sequence these tasks to address the critique. Respond with ONLY a JSON array ");
        sb.append("of task ID strings in the new recommended order, e.g. [\"id1\",\"id2\"]. ");
        sb.append("Do not add or remove tasks — only reorder them.\n");
        return sb.toString();
    }

    /**
     * Builds a planning prompt asking the model to sequence tasks toward a goal.
     */
    public static String buildPlanningPrompt(String goal, List<Task> candidateTasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan a task sequence to achieve this goal: ").append(goal).append("\n\n");
        sb.append("Available tasks:\n");
        if (candidateTasks != null) {
            for (Task task : candidateTasks) {
                sb.append("- ").append(task.getName())
                        .append(" [").append(task.getDifficulty())
                        .append(", ").append(task.getPoints()).append("pts")
                        .append(", ").append(task.getArea()).append("]")
                        .append(" (id: ").append(task.getId()).append(")")
                        .append("\n");
            }
        }
        sb.append("\n");
        sb.append("Instructions:\n");
        sb.append("- Optimize for proximity (prefer nearby tasks to minimise travel)\n");
        sb.append("- Prefer lower difficulty tiers first to unlock rewards early\n");
        sb.append("- Batch tasks that train the same skill together\n");
        sb.append("- Return a JSON array of task IDs in the recommended order, e.g. [\"id1\",\"id2\"]\n");
        return sb.toString();
    }

    /**
     * Static advice prompt — instructs the model to give concise next-step advice.
     */
    public static String buildAdvicePrompt() {
        return "Given the player's current state, goal, and plan progress, what should they focus on next "
                + "and why? Be concise (2-3 paragraphs). Consider: efficiency, upcoming unlocks, "
                + "skill milestones, and any opportunities to batch nearby tasks.";
    }
}
