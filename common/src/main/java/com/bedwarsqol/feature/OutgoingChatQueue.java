package com.bedwarsqol.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Priority queue for managed outgoing chat: a bounded FIFO of held MANUAL (typed) lines, one INC,
 * and one AUTO line. Higher-priority arrivals cancel/postpone optional automation rather than
 * queue behind it. Typed lines are never replaced by later typed lines — the player wrote every
 * one of them, so they go out in typing order; only the cap ({@link #MAX_HELD_MANUAL}) can refuse
 * one, and that refusal is reported, never silent.
 *
 * <p>Pure state machine — no Minecraft types — so unit tests can drive enqueue/select/cancel.
 */
public final class OutgoingChatQueue {

    /**
     * Most typed lines held at once. The queue drains one line per pacing gap (2.5 s), so a human
     * cannot type past this in practice; it only bounds a pathological burst.
     */
    public static final int MAX_HELD_MANUAL = 16;

    /** What an enqueue displaced or refused, if anything. Never silently discard these — send or notice. */
    public static final class Displacement {
        public final OutgoingChatRequest optional;
        public final OutgoingChatRequest priorSameSlot;
        /** The request itself, when the queue was full and could not take it; null otherwise. */
        public final OutgoingChatRequest rejected;

        public Displacement(OutgoingChatRequest optional, OutgoingChatRequest priorSameSlot) {
            this(optional, priorSameSlot, null);
        }

        public Displacement(OutgoingChatRequest optional, OutgoingChatRequest priorSameSlot,
                            OutgoingChatRequest rejected) {
            this.optional = optional;
            this.priorSameSlot = priorSameSlot;
            this.rejected = rejected;
        }

        public boolean isEmpty() {
            return optional == null && priorSameSlot == null && rejected == null;
        }
    }

    private final ArrayDeque<OutgoingChatRequest> manuals = new ArrayDeque<OutgoingChatRequest>();
    private OutgoingChatRequest inc;
    private OutgoingChatRequest auto;

    /** The oldest held typed line, or null. */
    public OutgoingChatRequest peekManual() { return manuals.peekFirst(); }
    public OutgoingChatRequest peekInc() { return inc; }
    public OutgoingChatRequest peekAuto() { return auto; }

    public int heldManualCount() { return manuals.size(); }

    public boolean isEmpty() {
        return manuals.isEmpty() && inc == null && auto == null;
    }

    /**
     * Enqueue {@code request}. MANUAL/INC interrupt and clear the AUTO slot. A MANUAL line joins the
     * back of the held FIFO (or is returned in {@link Displacement#rejected} at the cap); INC and
     * AUTO same-slot replace return the previous occupant in {@link Displacement#priorSameSlot}.
     */
    public Displacement enqueue(OutgoingChatRequest request) {
        if (request == null || request.kind == null || request.text == null || request.text.isEmpty()) {
            return new Displacement(null, null);
        }
        switch (request.kind) {
            case MANUAL: {
                OutgoingChatRequest displacedAuto = auto;
                auto = null;
                if (manuals.size() >= MAX_HELD_MANUAL) {
                    return new Displacement(displacedAuto, null, request);
                }
                manuals.addLast(request);
                return new Displacement(displacedAuto, null);
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

    /** Highest-priority pending request (the oldest held typed line first), or null. */
    public OutgoingChatRequest peekNext() {
        if (!manuals.isEmpty()) return manuals.peekFirst();
        if (inc != null) return inc;
        return auto;
    }

    /** Remove and return the highest-priority pending request, or null. */
    public OutgoingChatRequest pollNext() {
        if (!manuals.isEmpty()) return manuals.pollFirst();
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

    /** Drop one request of {@code kind}: for MANUAL, only the oldest held line. */
    public OutgoingChatRequest cancelKind(OutgoingChatKind kind) {
        if (kind == null) return null;
        switch (kind) {
            case MANUAL:
                return manuals.pollFirst();
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

    /** Remove and return every held typed line, oldest first. */
    public List<OutgoingChatRequest> drainManuals() {
        List<OutgoingChatRequest> out = new ArrayList<OutgoingChatRequest>(manuals);
        manuals.clear();
        return out;
    }

    public void clear() {
        manuals.clear();
        inc = null;
        auto = null;
    }
}
