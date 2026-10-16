package com.medibook;

import com.medibook.config.AppProperties;
import com.medibook.entity.AppointmentStatus;
import com.medibook.entity.DoctorAvailability;
import com.medibook.entity.Role;
import com.medibook.entity.UserAccount;
import com.medibook.security.JwtService;
import com.medibook.service.AvailabilityService;
import com.medibook.util.Csv;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fast tests of pure business rules - no Spring context or database needed. */
class UnitTests {

    @Test
    void statusTransitionsFollowTheLifecycle() {
        assertTrue(AppointmentStatus.PENDING.canMoveTo(AppointmentStatus.CONFIRMED));
        assertTrue(AppointmentStatus.PENDING.canMoveTo(AppointmentStatus.REJECTED));
        assertTrue(AppointmentStatus.CONFIRMED.canMoveTo(AppointmentStatus.COMPLETED));
        assertFalse(AppointmentStatus.PENDING.canMoveTo(AppointmentStatus.COMPLETED));
        assertFalse(AppointmentStatus.COMPLETED.canMoveTo(AppointmentStatus.CANCELLED));
        assertFalse(AppointmentStatus.CANCELLED.canMoveTo(AppointmentStatus.CONFIRMED));
        assertFalse(AppointmentStatus.REJECTED.canMoveTo(AppointmentStatus.PENDING));
    }

    @Test
    void slotsAreGeneratedInsideWorkingHours() {
        DoctorAvailability a = new DoctorAvailability();
        a.setStartTime(LocalTime.of(9, 0));
        a.setEndTime(LocalTime.of(10, 30));
        a.setSlotMinutes(30);
        List<LocalTime> starts = AvailabilityService.starts(a);
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)), starts);

        a.setSlotMinutes(45); // 9:00-9:45, 9:45-10:30
        assertEquals(2, AvailabilityService.starts(a).size());
    }

    @Test
    void csvQuotesValuesAndNeutralisesFormulas() {
        String out = new String(Csv.build(List.of("Name"), List.of(List.of("=HYPERLINK(\"x\")"), List.of("a,\"b\""))), StandardCharsets.UTF_8);
        assertTrue(out.contains("\"'=HYPERLINK(\"\"x\"\")\""));
        assertTrue(out.contains("\"a,\"\"b\"\"\""));
    }

    @Test
    void jwtRoundTripAndTamperDetection() {
        JwtService jwt = new JwtService(new AppProperties(new AppProperties.Jwt("unit-test-secret-unit-test-secret-1234", 5), null, null, null, "UTC"));
        UserAccount u = new UserAccount();
        u.setId(7L);
        u.setEmail("a@b.c");
        u.setRole(Role.PATIENT);
        String token = jwt.issue(u);
        assertEquals("7", jwt.parse(token).getSubject());
        assertThrows(JwtException.class, () -> jwt.parse(token.substring(0, token.length() - 3) + "abc"));
    }

    @Test
    void jwtSecretMustBeLongEnough() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(new AppProperties(new AppProperties.Jwt("short", 5), null, null, null, "UTC")));
    }
}
