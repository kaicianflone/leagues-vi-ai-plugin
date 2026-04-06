package com.leaguesai.core.events;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
public class LocationChangedEvent {
    WorldPoint worldPoint;
    int regionId;
}
