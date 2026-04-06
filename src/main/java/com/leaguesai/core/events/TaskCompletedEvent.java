package com.leaguesai.core.events;

import lombok.Value;

@Value
public class TaskCompletedEvent {
    String taskId;
    String taskName;
}
