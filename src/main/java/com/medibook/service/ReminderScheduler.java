package com.medibook.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends in-app reminders for confirmed appointments starting within 24 hours.
 * Limitations (documented in README): runs only while the backend is running, uses one in-process timer
 * (so run a single backend instance), and delivers in-app notifications only - no email/SMS.
 */
@Component
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private final AppointmentService appointmentService;

    public ReminderScheduler(AppointmentService appointmentService) { this.appointmentService = appointmentService; }

    @Scheduled(initialDelay = 60_000, fixedRate = 15 * 60_000)
    public void run() {
        try {
            int n = appointmentService.sendDueReminders();
            if (n > 0) log.info("Sent reminders for {} appointment(s)", n);
        } catch (Exception e) {
            log.error("Reminder job failed", e);
        }
    }
}
