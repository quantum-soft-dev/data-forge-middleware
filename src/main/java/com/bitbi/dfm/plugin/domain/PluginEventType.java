package com.bitbi.dfm.plugin.domain;

/**
 * Types of events that can be dispatched to plugins.
 * Plugins subscribe to specific event types they want to receive.
 */
public enum PluginEventType {
    BATCH_COMPLETED("batch.completed"),
    BATCH_FAILED("batch.failed"),
    BATCH_EXPIRED("batch.expired"),
    FILE_UPLOADED("file.uploaded");

    private final String eventName;

    PluginEventType(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }

    /**
     * Find event type by its string name.
     * @param eventName the event name (e.g., "batch.completed")
     * @return the matching PluginEventType
     * @throws IllegalArgumentException if no match found
     */
    public static PluginEventType fromEventName(String eventName) {
        for (PluginEventType type : values()) {
            if (type.eventName.equals(eventName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + eventName);
    }
}
