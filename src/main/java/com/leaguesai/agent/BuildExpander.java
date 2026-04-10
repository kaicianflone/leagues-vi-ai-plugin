package com.leaguesai.agent;

import com.leaguesai.data.GearRepository;
import com.leaguesai.data.TaskRepository;
import com.leaguesai.data.model.GearItem;
import com.leaguesai.data.model.GearSlot;
import com.leaguesai.data.model.Task;
import com.leaguesai.data.model.Build;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Singleton
public class BuildExpander {

    private final GearRepository gearRepository;
    private final TaskRepository taskRepository;
    private final GoalPlanner goalPlanner;

    @Inject
    public BuildExpander(GearRepository gearRepository,
                         TaskRepository taskRepository,
                         GoalPlanner goalPlanner) {
        this.gearRepository = gearRepository;
        this.taskRepository = taskRepository;
        this.goalPlanner = goalPlanner;
    }

    /**
     * Expand a Build into a CompositeGoal.
     *
     * Strategy:
     * 1. For each gear slot item in build.getGear():
     *    a. Look up GearItem via gearRepository.findById(itemId)
     *    b. If GearItem has taskOverrides (non-empty list), use those IDs directly
     *    c. Otherwise, call taskRepository.findByTargetItemId(gearItem.getWikiItemId())
     *       and collect their IDs
     * 2. If no terminal task IDs found → return CompositeGoal.goalsOnly (empty batch)
     * 3. Build GoalSpec(type=BUILD, terminalTaskIds=collected, rawPhrase=build.getName())
     * 4. Return goalPlanner.resolveCompositeGoal(spec, ctx)
     */
    public CompositeGoal expand(Build build, PlayerContext ctx) {
        if (build == null) {
            return goalsOnlyResult(null);
        }

        Set<String> terminalTaskIds = new LinkedHashSet<>();

        if (build.getGear() != null) {
            for (Map.Entry<GearSlot, String> entry : build.getGear().entrySet()) {
                String itemId = entry.getValue();
                if (itemId == null || itemId.isEmpty()) continue;

                GearItem gearItem = gearRepository.findById(itemId);
                if (gearItem == null) {
                    log.warn("BuildExpander: gear item '{}' not found in GearRepository (slot {})",
                             itemId, entry.getKey());
                    continue;
                }

                // taskOverrides wins over SQL lookup
                List<String> overrides = gearItem.getTaskOverrides();
                if (overrides != null && !overrides.isEmpty()) {
                    terminalTaskIds.addAll(overrides);
                } else if (gearItem.getWikiItemId() > 0) {
                    List<Task> tasks = taskRepository.findByTargetItemId(gearItem.getWikiItemId());
                    for (Task t : tasks) {
                        terminalTaskIds.add(t.getId());
                    }
                }
            }
        }

        if (terminalTaskIds.isEmpty()) {
            log.info("BuildExpander: no terminal tasks found for build '{}' — goals-only mode",
                     build.getName());
            return goalsOnlyResult(build.getName());
        }

        log.info("BuildExpander: build '{}' resolved {} terminal task(s)",
                 build.getName(), terminalTaskIds.size());

        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .targetId(build.getId())
                .targetName(build.getName())
                .rawPhrase(build.getName())
                .unlockCost(0)
                .terminalTaskIds(terminalTaskIds)
                .build();

        return goalPlanner.resolveCompositeGoal(spec, ctx);
    }

    private CompositeGoal goalsOnlyResult(String buildName) {
        GoalSpec spec = GoalSpec.builder()
                .type(GoalType.BUILD)
                .targetName(buildName != null ? buildName : "Unknown build")
                .rawPhrase(buildName != null ? buildName : "Unknown build")
                .unlockCost(0)
                .build();
        return CompositeGoal.builder()
                .root(spec)
                .pointsGap(0)
                .coveredBy(0)
                .reachable(true)
                .build();
    }
}
