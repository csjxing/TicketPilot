package cn.local.tickethelper.core;

/** System authorization and a fully initialized local connection are separate facts. */
public final class AccessibilityReadiness {
    public enum State { OFF, ENABLED_DISCONNECTED, READY, UNKNOWN }

    private AccessibilityReadiness() { }

    /** A failed system read must not be presented as an explicit user denial. */
    public static State evaluate(Boolean configured, boolean globallyEnabled, boolean connectedReady) {
        if (configured == null) return State.UNKNOWN;
        if (!configured) return State.OFF;
        return globallyEnabled && connectedReady ? State.READY : State.ENABLED_DISCONNECTED;
    }
}
