package com.medibook.entity;

import java.util.EnumSet;
import java.util.Set;

public enum AppointmentStatus {
    PENDING, CONFIRMED, REJECTED, CANCELLED, COMPLETED;

    /** Statuses that occupy a slot on the doctor's calendar. */
    public static final Set<AppointmentStatus> SLOT_HOLDING = EnumSet.of(PENDING, CONFIRMED, COMPLETED);
    /** Statuses that are still "live" (can be cancelled / rescheduled). */
    public static final Set<AppointmentStatus> OPEN = EnumSet.of(PENDING, CONFIRMED);

    /** The status-transition table. Anything not listed here is rejected by the service. */
    public boolean canMoveTo(AppointmentStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == REJECTED || next == CANCELLED;
            case CONFIRMED -> next == COMPLETED || next == CANCELLED || next == PENDING; // PENDING = patient rescheduled
            default -> false; // REJECTED, CANCELLED, COMPLETED are final
        };
    }
}
