package com.karnataka.ubid.model;

import java.util.List;

public record ActivityStatus(
        String ubid,
        Status status,
        String reasoning,
        List<ActivityEvent> evidenceEvents
) {
    public enum Status {
        ACTIVE,
        DORMANT,
        CLOSED,
        UNKNOWN
    }
}
