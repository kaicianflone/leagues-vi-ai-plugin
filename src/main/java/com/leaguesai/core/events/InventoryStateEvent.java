package com.leaguesai.core.events;

import lombok.Value;

import java.util.Map;

@Value
public class InventoryStateEvent {
    Map<Integer, Integer> items;
}
