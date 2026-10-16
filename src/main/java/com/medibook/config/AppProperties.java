package com.medibook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/** Typed view of every "medibook.*" property in application.properties. */
@ConfigurationProperties(prefix = "medibook")
public record AppProperties(Jwt jwt, Cors cors, Rules rules, Seed seed, String timezone) {

    public record Jwt(String secret, long expirationMinutes) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Rules(int cancellationMinHours, int rescheduleMinHours, int maxReschedules,
                        int bookingWindowDays, int minLeadMinutes, boolean allowEarlyCompletion) {}

    public record Seed(String adminEmail, String adminPassword, String adminName,
                       boolean demoData, String demoPassword) {}
}
