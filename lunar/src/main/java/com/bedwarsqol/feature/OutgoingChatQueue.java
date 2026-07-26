package com.bedwarsqol.feature;

/**
 * Priority queue for managed outgoing chat. At most one held MANUAL, one INC, and one AUTO line.
 * Higher-priority arrivals cancel/postpone optional automation rather than queue behind it.
 *
 * <p>Pure state machine — no Minecraft types — so unit tests can drive enqueue/select/cancel.
 */
public final class OutgoingChatQueue {

    /** What an enqueue displaced, if anything. Never silently discard these — send or notice. */
    public static final class Displacement {
        public final OutgoingChatRequest optional;
        public final OutgoingChatRequest priorSameSlot;

        public Displacement(OutgoingChatRequest optional, OutgoingChatRequest priorSameSlot) {
            this.optional = optional;
            this.priorSameSlot = priorSameSlot;
        }

        public boolean isEmpty() {
            return optional == null && priorSameSlot == null;
        }
    }

    private OutgoingChatRequest manual;
    private OutgoingChatRequest inc;
    private OutgoingChatRequest auto;

    public OutgoingChatRequest peekManual() { return manual; }
    public OutgoingChatRequest peekInc() { return inc; }
    public OutgoingChatRequest peekAuto() { return auto; }

    public boolean isEmpty() {
        return manual == null && inc == null && auto == null;
    }

    /**
     * Enqueue {@code request}. MANUAL/INC interrupt and clear the AUTO slot. Same-slot replace
     * returns the previous occupant in {@link Displacement#priorSameSlot}.
     */
    public Displacement enqueue(OutgoingChatRequest request) {
        if (request == null || request.kind == null || request.text == null || request.text.isEmpty()) {
            return new Displacement(null, null);
        }
        switch (request.kind) {
            case MANUAL: {
                OutgoingChatRequest displacedAuto = auto;
                auto = null;
                OutgoingChatRequest prev = manual;
                manual = request;
                return new Displacement(displacedAuto, prev);
            }
            case INC: {
                OutgoingChatRequest displacedAuto = auto;
                auto = null;
                OutgoingChatRequest prev = inc;
                inc = request;
                return new Displacement(displacedAuto, prev);
            }
            case SWEAT:
            case AUTOGG: {
                OutgoingChatRequest prev = auto;
                auto = request;
                return new Displacement(null, prev);
            }
            default:
                return new Displacement(null, null);
        }
    }

    /** Highest-priority pending request, or null. */
    public OutgoingChatRequest peekNext() {
        if (manual != null) return manual;
        if (inc != null) return inc;
        return auto;
    }

    /** Remove and return the highest-priority pending request, or null. */
    public OutgoingChatRequest pollNext() {
        if (manual != null) {
            OutgoingChatRequest r = manual;
            manual = null;
            return r;
        }
        if (inc != null) {
            OutgoingChatRequest r = inc;
            inc = null;
            return r;
        }
        if (auto != null) {
            OutgoingChatRequest r = auto;
            auto = null;
            return r;
        }
        return null;
    }

    /** Drop optional automation only (manual/INC preserved). */
    public OutgoingChatRequest cancelOptional() {
        OutgoingChatRequest r = auto;
        auto = null;
        return r;
    }

    public OutgoingChatRequest cancelKind(OutgoingChatKind kind) {
        if (kind == null) return null;
        switch (kind) {
            case MANUAL: {
                OutgoingChatRequest m = manual;
                manual = null;
                return m;
            }
            case INC: {
                OutgoingChatRequest i = inc;
                inc = null;
                return i;
            }
            case SWEAT:
            case AUTOGG:
                if (auto != null && auto.kind == kind) {
                    OutgoingChatRequest a = auto;
                    auto = null;
                    return a;
                }
                return null;
            default:
                return null;
        }
    }

    public void clear() {
        manual = null;
        inc = null;
        auto = null;
    }
}
