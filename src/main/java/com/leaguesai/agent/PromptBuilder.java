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
     * Two-arg overload kept for existing tests. Delegates to the three-arg
     * form with no repo (so relic/area/pact sections are omitted).
     */
    public static String buildSystemPrompt(PlayerContext ctx, List<Task> relevantTasks) {
        return buildSystemPrompt(ctx, relevantTasks, null);
    }

    /**
     * Builds a Markdown system prompt summarising the player's current state,
     * optionally including a list of relevant tasks retrieved from semantic
     * search (RAG context) and unlockables reference data (relics / areas /
     * pacts) from the repo.
     *
     * <p>When {@code repo} is non-null and has relics / areas / pacts loaded,
     * the prompt includes a condensed reference block for each category plus
     * a sentence explaining the 40-pact / 3-respec Demonic Pacts mechanic.
     * This is what lets the LLM answer questions like "what do I need to
     * unlock Grimoire?" with real costs instead of guesses.
     */
    public static String buildSystemPrompt(PlayerContext ctx,
                                           List<Task> relevantTasks,
                                           TaskRepository repo) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert OSRS Leagues VI (Demonic Pacts) coach.\n\n");

        sb.append(buildIronmanDoctrine());

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

        return sb.toString();
    }

    /**
     * Full system prompt with gear context block appended.
     * Use when a build is active or the player asked a gear question.
     */
    public static String buildSystemPrompt(PlayerContext ctx, List<Task> relevantTasks,
                                           TaskRepository repo, List<GearItem> relevantGear) {
        String base = buildSystemPrompt(ctx, relevantTasks, repo);
        String gearBlock = buildGearContext(relevantGear, ctx);
        return base + gearBlock;
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

    /**
     * The Ironman Coaching Doctrine — embedded near the top of every system
     * prompt so the LLM coaches in the style of B0aty / Faux / top UIM players.
     * Pure prompt engineering: no external knowledge files, no scraping.
     * Cheap to iterate when player feedback comes in.
     */
    static String buildIronmanDoctrine() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Ironman Coaching Doctrine\n");
        sb.append("You are coaching an Ironman in Leagues VI. Your style is shaped by these influences:\n");
        sb.append("- B0aty (HCIM): survivability first. Never recommend a step that risks death without a hard warning. ");
        sb.append("Prefer safer alternatives even at small XP/hour cost. Always name the safer alt.\n");
        sb.append("- Faux (efficient skiller): rates matter. When recommending training, name the GP/xp/hr and the bottleneck resource. ");
        sb.append("Prefer methods that double up on a Leagues task.\n");
        sb.append("- Top UIM (no-bank logistics): inventory is sacred. Group tasks geographically. Never recommend a return trip ");
        sb.append("when one tile-walk away there is a second task. Carry only what is needed for the next 3 tasks.\n");
        sb.append("- All three: ironman-only sources. NEVER suggest a GE/trade/group-ironman fix.\n");
        sb.append("- Best methods, not popular methods. If the wiki \"fastest\" route requires content the player has not unlocked, ");
        sb.append("fall back to the best available and explicitly say why.\n\n");
        sb.append("Behavioral rules:\n");
        sb.append("- When you mention an item, also state where an ironman gets it, in one phrase.\n");
        sb.append("- For makeable items, walk back the prereq chain to a gatherable.\n");
        sb.append("- For combat tasks, always state the recommended gear tier the player actually owns (read Equipment + Inventory above).\n");
        sb.append("- Never say \"buy X\" or \"trade for X\".\n\n");
        return sb.toString();
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

    /**
     * Prompt for {@link PersonaReviewer}. Asks the LLM to roleplay three
     * ironman archetypes adversarially reviewing the just-built plan.
     */
    public static String buildPersonaReviewPrompt(String goal, List<PlannedStep> plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are simultaneously THREE ironman experts adversarially reviewing the Leagues VI plan below.\n\n");
        sb.append("- B0aty (Hardcore Ironman): obsesses over route safety, death risk, and survivability.\n");
        sb.append("- Faux (efficient skiller): obsesses over xp/hr, gp/hr, and bottleneck resources.\n");
        sb.append("- Top UIM (no-bank logistics): obsesses over inventory pressure and route adjacency.\n\n");
        sb.append("Each picks the SINGLE biggest issue with this plan from their lens, in one sentence. ");
        sb.append("Then output a verdict line: \"Verdict: keep\" / \"Verdict: revise\" / \"Verdict: rebuild\".\n\n");
        sb.append("Output format (exact):\n");
        sb.append("B0aty: <one sentence>\n");
        sb.append("Faux: <one sentence>\n");
        sb.append("UIM: <one sentence>\n");
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
