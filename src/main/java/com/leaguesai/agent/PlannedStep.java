package com.leaguesai.agent;

import com.leaguesai.data.model.Task;
import com.leaguesai.overlay.OverlayData;
import com.leaguesai.ui.AnimationType;
import lombok.Builder;
import lombok.Data;
import net.runelite.api.coords.WorldPoint;
import java.util.List;

@Data
@Builder
public class PlannedStep {
    private final Task task;
    private final WorldPoint destination;
    private final List<Integer> requiredItems;
    private final String instruction;
    private final OverlayData overlayData;
    private final AnimationType animation;
}
