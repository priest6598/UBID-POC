package com.karnataka.ubid.model;

import java.time.LocalDate;

public record ActivityEvent(
        String sourceSystem,
        String sourceRecordId,
        EventType eventType,
        LocalDate eventDate,
        String metadata
) {
    public enum EventType {
        RENEWAL,
        INSPECTION,
        CONSUMPTION,
        FILING,
        CLOSURE,
        ADDRESS_CHANGE
    }
}
