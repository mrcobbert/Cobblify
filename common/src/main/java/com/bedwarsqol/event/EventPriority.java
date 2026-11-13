package com.bedwarsqol.event;

/** Listener order for {@link EventDispatcher}, highest first (Forge's {@code EventPriority} order). */
public enum EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST
}
