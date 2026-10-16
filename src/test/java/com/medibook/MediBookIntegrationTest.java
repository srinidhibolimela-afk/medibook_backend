package com.medibook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.entity.Appointment;
import com.medibook.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the REST API against an in-memory H2 database (profile "test").
 * Seeded data used: admin@test.local / Admin@12345 and the SAMPLE doctors (password Demo@12345).
 * Each test creates its own patients with random e-mails and always asks the API for a currently free slot,
 * so tests do not depend on each other or on the order they run in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MediBookIntegrationTest {
    static final String DEMO_PASSWORD = "Demo@12345";
    static final String PATIENT_PASSWORD = "Patient@123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppointmentRepository appointmentRepo;
    @Autowired TransactionTemplate tx;

    // ------------------------------------------------------------------ helpers
    JsonNode call(MockHttpServletRequestBuilder req, String token, Object body, int expectedStatus) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        MvcResult r = mvc.perform(req).andExpect(status().is(expectedStatus)).andReturn();
        String text = r.getResponse().getContentAsString();
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }

    String login(String email, String password, String portal) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("email", email, "password", password, "portal", portal), 200)
                .get("token").asText();
    }

    String registerPatient() throws Exception {
        String email = "p" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        call(post("/api/auth/register/patient"), null,
                Map.of("fullName", "Test Patient", "email", email, "password", PATIENT_PASSWORD), 201);
        return login(email, PATIENT_PASSWORD, "PATIENT");
    }

    String doctorToken(String slug) throws Exception { return login(slug + "@demo.medibook.local", DEMO_PASSWORD, "DOCTOR"); }
    String adminToken() throws Exception { return login("admin@test.local", "Admin@12345", "ADMIN"); }

    long doctorId(String name) throws Exception {
        JsonNode page = call(get("/api/public/doctors?q=" + name.replace(" ", "%20")), null, null, 200);
        assertTrue(page.get("totalElements").asLong() >= 1, "doctor not found: " + name);
        return page.get("content").get(0).get("id").asLong();
    }

    /** First free slot on a date at least `minDaysAhead` days from now: {date, time}. */
    String[] freeSlot(long doctorId, int minDaysAhead) throws Exception {
        JsonNode dates = call(get("/api/public/doctors/" + doctorId + "/available-dates?days=45"), null, null, 200);
        for (JsonNode d : dates) {
            if (LocalDate.parse(d.asText()).isBefore(LocalDate.now().plusDays(minDaysAhead))) continue;
            JsonNode slots = call(get("/api/public/doctors/" + doctorId + "/slots?date=" + d.asText()), null, null, 200);
            for (JsonNode s : slots) if (s.get("available").asBoolean()) return new String[]{d.asText(), s.get("time").asText()};
        }
        fail("No free slot found");
        return null;
    }

    JsonNode book(String patientToken, long doctorId, String[] slot, int expected) throws Exception {
        return call(post("/api/appointments"), patientToken,
                Map.of("doctorId", doctorId, "date", slot[0], "time", slot[1], "reason", "Routine check-up"), expected);
    }

    @Test
    void healthEndpointWorksWithoutAuth() throws Exception {
        JsonNode h = call(get("/api/health"), null, null, 200);
        assertEquals("UP", h.path("status").asText());
        assertEquals("MediBook", h.path("application").asText());
        assertEquals("UP", h.path("database").asText());
    }

    /** Pretend the visit time has arrived by moving the appointment into the past (the only way to test "complete"). */
    void moveToYesterday(long appointmentId) {
        tx.executeWithoutResult(s -> {
            Appointment a = appointmentRepo.findById(appointmentId).orElseThrow();
            a.setAppointmentDate(LocalDate.now().minusDays(1));
            a.syncSlotKey();
            appointmentRepo.save(a);
        });
    }

    // ------------------------------------------------------------------ authentication
    @Test
    void patientCanRegisterAndLogin_andRoleCannotBeChosenAtRegistration() throws Exception {
        String email = "reg" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        // The request tries to smuggle in an ADMIN role. The field does not exist in the DTO and must be ignored.
        JsonNode res = call(post("/api/auth/register/patient"), null, Map.of("fullName", "Sneaky User", "email", email,
                "password", PATIENT_PASSWORD, "role", "ADMIN"), 201);
        assertEquals("PATIENT", res.get("user").get("role").asText());
        String token = login(email, PATIENT_PASSWORD, "PATIENT");
        assertEquals(email, call(get("/api/auth/me"), token, null, 200).get("email").asText());
    }

    @Test
    void registrationRejectsDuplicateEmailAndWeakPassword() throws Exception {
        String email = "dup" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        Map<String, Object> body = Map.of("fullName", "Dup User", "email", email, "password", PATIENT_PASSWORD);
        call(post("/api/auth/register/patient"), null, body, 201);
        call(post("/api/auth/register/patient"), null, body, 409);
        call(post("/api/auth/register/patient"), null, Map.of("fullName", "Weak", "email", "weak@test.local", "password", "abc"), 400);
    }

    @Test
    void doctorAndAdminCanLogin() throws Exception {
        assertNotNull(doctorToken("meera.rao"));
        assertNotNull(adminToken());
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        call(post("/api/auth/login"), null, Map.of("email", "admin@test.local", "password", "WrongPass1", "portal", "ADMIN"), 401);
        call(post("/api/auth/login"), null, Map.of("email", "nobody@test.local", "password", "Whatever1", "portal", "PATIENT"), 401);
    }

    @Test
    void loginIsRejectedForTheWrongPortal() throws Exception {
        // Correct password, but a doctor trying to use the admin portal.
        call(post("/api/auth/login"), null, Map.of("email", "meera.rao@demo.medibook.local", "password", DEMO_PASSWORD, "portal", "ADMIN"), 403);
    }

    @Test
    void logoutInvalidatesTheToken() throws Exception {
        String token = registerPatient();
        call(get("/api/auth/me"), token, null, 200);
        call(post("/api/auth/logout"), token, null, 200);
        call(get("/api/auth/me"), token, null, 401);
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = registerPatient();
        call(get("/api/auth/me"), token + "x", null, 401);
        call(get("/api/auth/me"), null, null, 401);
    }

    // ------------------------------------------------------------------ authorization
    @Test
    void roleBasedAuthorizationIsEnforced() throws Exception {
        String patient = registerPatient();
        String doctor = doctorToken("meera.rao");
        call(get("/api/admin/dashboard"), patient, null, 403);
        call(get("/api/admin/dashboard"), doctor, null, 403);
        call(get("/api/admin/dashboard"), null, null, 401);
        call(get("/api/doctor/profile"), patient, null, 403);
        call(get("/api/patient/profile"), doctor, null, 403);
        call(get("/api/admin/dashboard"), adminToken(), null, 200);
    }

    // ------------------------------------------------------------------ doctor search & availability
    @Test
    void doctorSearchFiltersUseRealData() throws Exception {
        JsonNode byName = call(get("/api/public/doctors?q=meera"), null, null, 200);
        assertEquals(1, byName.get("totalElements").asInt());
        assertEquals("Dr. Meera Rao", byName.get("content").get(0).get("name").asText());

        JsonNode specs = call(get("/api/public/specializations"), null, null, 200);
        long cardioId = 0;
        for (JsonNode s : specs) if ("Cardiology".equals(s.get("name").asText())) cardioId = s.get("id").asLong();
        JsonNode bySpec = call(get("/api/public/doctors?specializationId=" + cardioId), null, null, 200);
        assertTrue(bySpec.get("totalElements").asInt() >= 1);
        bySpec.get("content").forEach(d -> assertEquals("Cardiology", d.get("specialization").asText()));

        JsonNode cheap = call(get("/api/public/doctors?maxFee=550&sort=fee_asc"), null, null, 200);
        cheap.get("content").forEach(d -> assertTrue(d.get("consultationFee").asDouble() <= 550));
    }

    /** Registers a doctor application (status PENDING) with a unique name and returns that name. */
    String applyAsDoctor() throws Exception {
        String unique = "Zed" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long specId = call(get("/api/public/specializations"), null, null, 200).get(0).get("id").asLong();
        call(post("/api/auth/register/doctor"), null, Map.ofEntries(
                Map.entry("fullName", "Dr. " + unique), Map.entry("email", unique.toLowerCase() + "@test.local"),
                Map.entry("password", PATIENT_PASSWORD), Map.entry("specializationId", specId),
                Map.entry("qualifications", "MBBS"), Map.entry("experienceYears", 3), Map.entry("consultationFee", 400),
                Map.entry("clinicName", "Test Clinic"), Map.entry("location", "Hyderabad")), 201);
        return unique;
    }

    @Test
    void unverifiedDoctorIsNotListedAndNotBookable() throws Exception {
        String name = applyAsDoctor();
        assertEquals(0, call(get("/api/public/doctors?q=" + name), null, null, 200).get("totalElements").asInt());
    }

    @Test
    void slotsComeFromTheDoctorsScheduleAndExcludePastDates() throws Exception {
        long id = doctorId("meera");
        JsonNode slots = call(get("/api/public/doctors/" + id + "/slots?date=" + LocalDate.now().minusDays(1)), null, null, 200);
        assertEquals(0, slots.size(), "past dates must have no slots");
        String[] slot = freeSlot(id, 1);
        assertNotNull(slot[1]);
    }

    // ------------------------------------------------------------------ booking
    @Test
    void patientCanBookAndDoctorIsNotified() throws Exception {
        String patient = registerPatient();
        String doctor = doctorToken("arjun.nair");
        long id = doctorId("arjun");
        long before = call(get("/api/notifications/unread-count"), doctor, null, 200).get("count").asLong();

        JsonNode appt = book(patient, id, freeSlot(id, 1), 201);
        assertEquals("PENDING", appt.get("status").asText());
        assertTrue(appt.get("reference").asText().startsWith("MB-"));

        long after = call(get("/api/notifications/unread-count"), doctor, null, 200).get("count").asLong();
        assertEquals(before + 1, after);
    }

    @Test
    void doubleBookingTheSameSlotIsRejected() throws Exception {
        String p1 = registerPatient();
        String p2 = registerPatient();
        long id = doctorId("sana");
        String[] slot = freeSlot(id, 1);
        book(p1, id, slot, 201);
        book(p2, id, slot, 409);
    }

    @Test
    void pastDatesAndInvalidTimesAreRejectedByTheServer() throws Exception {
        String p = registerPatient();
        long id = doctorId("meera");
        book(p, id, new String[]{LocalDate.now().minusDays(1).toString(), "10:00"}, 400);
        String[] slot = freeSlot(id, 1);
        book(p, id, new String[]{slot[0], "10:07"}, 400); // not on the 30-minute grid
        book(p, id, new String[]{LocalDate.now().plusDays(400).toString(), "10:00"}, 400); // beyond booking window
    }

    // ------------------------------------------------------------------ confirm / reject / cancel / reschedule
    @Test
    void doctorConfirmsAndOnlyTheTreatingDoctorCan() throws Exception {
        String patient = registerPatient();
        long id = doctorId("rohit");
        long apptId = book(patient, id, freeSlot(id, 1), 201).get("id").asLong();

        call(patch("/api/appointments/" + apptId + "/confirm"), patient, null, 403);            // patient cannot confirm
        call(patch("/api/appointments/" + apptId + "/confirm"), doctorToken("meera.rao"), null, 403); // other doctor cannot
        JsonNode confirmed = call(patch("/api/appointments/" + apptId + "/confirm"), doctorToken("rohit.verma"), null, 200);
        assertEquals("CONFIRMED", confirmed.get("status").asText());
        // Status transition validation: cannot confirm twice.
        call(patch("/api/appointments/" + apptId + "/confirm"), doctorToken("rohit.verma"), null, 409);
    }

    @Test
    void rejectedAppointmentFreesTheSlot() throws Exception {
        String p1 = registerPatient();
        String p2 = registerPatient();
        long id = doctorId("lakshmi");
        String[] slot = freeSlot(id, 1);
        long a1 = book(p1, id, slot, 201).get("id").asLong();
        call(patch("/api/appointments/" + a1 + "/reject"), doctorToken("lakshmi.iyer"), Map.of("reason", "On leave"), 200);
        book(p2, id, slot, 201); // slot is free again
    }

    @Test
    void patientCanCancelAndSlotBecomesFree() throws Exception {
        String p1 = registerPatient();
        String p2 = registerPatient();
        long id = doctorId("imran");
        String[] slot = freeSlot(id, 2);
        long a1 = book(p1, id, slot, 201).get("id").asLong();
        assertEquals("CANCELLED", call(patch("/api/appointments/" + a1 + "/cancel"), p1, Map.of("reason", "Plans changed"), 200).get("status").asText());
        call(patch("/api/appointments/" + a1 + "/cancel"), p1, null, 409); // already cancelled
        book(p2, id, slot, 201);
    }

    @Test
    void rescheduleMovesTheAppointmentAndRequiresReapproval() throws Exception {
        String patient = registerPatient();
        long id = doctorId("pooja");
        String[] first = freeSlot(id, 2);
        long apptId = book(patient, id, first, 201).get("id").asLong();
        call(patch("/api/appointments/" + apptId + "/confirm"), doctorToken("pooja.desai"), null, 200);

        String[] second = freeSlot(id, 3);
        JsonNode moved = call(patch("/api/appointments/" + apptId + "/reschedule"), patient,
                Map.of("date", second[0], "time", second[1]), 200);
        assertEquals("PENDING", moved.get("status").asText());
        assertEquals(1, moved.get("rescheduleCount").asInt());

        // Rescheduling into the same slot is rejected; the old slot is free for others.
        call(patch("/api/appointments/" + apptId + "/reschedule"), patient, Map.of("date", second[0], "time", second[1]), 400);
        book(registerPatient(), id, first, 201);
    }

    @Test
    void rescheduleCountIsLimited() throws Exception {
        String patient = registerPatient();
        long id = doctorId("meera");
        long apptId = book(patient, id, freeSlot(id, 3), 201).get("id").asLong();
        for (int i = 0; i < 2; i++) {
            String[] s = freeSlot(id, 3);
            call(patch("/api/appointments/" + apptId + "/reschedule"), patient, Map.of("date", s[0], "time", s[1]), 200);
        }
        String[] s = freeSlot(id, 3);
        call(patch("/api/appointments/" + apptId + "/reschedule"), patient, Map.of("date", s[0], "time", s[1]), 400);
    }

    // ------------------------------------------------------------------ unauthorized record access
    @Test
    void anotherPatientCannotReadOrCancelMyAppointment() throws Exception {
        String owner = registerPatient();
        String stranger = registerPatient();
        long id = doctorId("meera");
        long apptId = book(owner, id, freeSlot(id, 1), 201).get("id").asLong();
        call(get("/api/appointments/" + apptId), stranger, null, 403);
        call(patch("/api/appointments/" + apptId + "/cancel"), stranger, null, 403);
        call(get("/api/appointments/" + apptId + "/receipt"), stranger, null, 403);
        call(get("/api/appointments/" + apptId), owner, null, 200);
    }

    @Test
    void visitNotesAreVisibleOnlyToTheTreatingDoctor() throws Exception {
        String patient = registerPatient();
        long id = doctorId("arjun");
        long apptId = book(patient, id, freeSlot(id, 1), 201).get("id").asLong();
        String doctor = doctorToken("arjun.nair");
        call(patch("/api/appointments/" + apptId + "/confirm"), doctor, null, 200);
        call(put("/api/appointments/" + apptId + "/notes"), doctor, Map.of("visitNotes", "Private clinical note"), 200);

        assertEquals("Private clinical note", call(get("/api/appointments/" + apptId), doctor, null, 200).get("visitNotes").asText());
        assertTrue(call(get("/api/appointments/" + apptId), patient, null, 200).get("visitNotes").isNull());
        assertTrue(call(get("/api/appointments/" + apptId), adminToken(), null, 200).get("visitNotes").isNull());
        // A different doctor can neither read nor edit them.
        call(get("/api/appointments/" + apptId), doctorToken("meera.rao"), null, 403);
        call(put("/api/appointments/" + apptId + "/notes"), doctorToken("meera.rao"), Map.of("visitNotes", "x"), 403);
    }

    // ------------------------------------------------------------------ reviews
    @Test
    void reviewsRequireCompletedVisitAndAreUniquePerAppointment() throws Exception {
        String patient = registerPatient();
        String stranger = registerPatient();
        long id = doctorId("sana");
        long apptId = book(patient, id, freeSlot(id, 1), 201).get("id").asLong();
        String doctor = doctorToken("sana.khan");
        Map<String, Object> review = Map.of("appointmentId", apptId, "rating", 5, "comment", "Very helpful");

        call(post("/api/reviews"), patient, review, 400);          // still PENDING
        call(patch("/api/appointments/" + apptId + "/confirm"), doctor, null, 200);
        call(post("/api/reviews"), patient, review, 400);          // CONFIRMED, not completed
        moveToYesterday(apptId);
        call(patch("/api/appointments/" + apptId + "/complete"), doctor, Map.of("visitNotes", "Prescribed cream"), 200);

        call(post("/api/reviews"), stranger, review, 403);         // not the patient of this visit
        call(post("/api/reviews"), patient, Map.of("appointmentId", apptId, "rating", 9), 400); // invalid rating
        call(post("/api/reviews"), patient, review, 201);
        call(post("/api/reviews"), patient, review, 409);          // duplicate

        JsonNode detail = call(get("/api/public/doctors/" + id), null, null, 200);
        assertEquals(1, detail.get("card").get("reviewCount").asInt());
        assertEquals(5.0, detail.get("card").get("averageRating").asDouble(), 0.01);
    }

    @Test
    void adminCanHideAReviewAndItNoLongerCountsTowardsTheRating() throws Exception {
        String patient = registerPatient();
        long id = doctorId("imran");
        long apptId = book(patient, id, freeSlot(id, 1), 201).get("id").asLong();
        String doctor = doctorToken("imran.qureshi");
        call(patch("/api/appointments/" + apptId + "/confirm"), doctor, null, 200);
        moveToYesterday(apptId);
        call(patch("/api/appointments/" + apptId + "/complete"), doctor, null, 200);
        long reviewId = call(post("/api/reviews"), patient, Map.of("appointmentId", apptId, "rating", 1, "comment", "rude text"), 201).get("id").asLong();

        String admin = adminToken();
        call(patch("/api/admin/reviews/" + reviewId + "/moderation"), admin, Map.of("hidden", true, "reason", "Inappropriate"), 200);
        JsonNode detail = call(get("/api/public/doctors/" + id), null, null, 200);
        assertEquals(0, detail.get("card").get("reviewCount").asInt());
        assertTrue(detail.get("card").get("averageRating").isNull());
    }

    // ------------------------------------------------------------------ availability, notifications, dashboards
    @Test
    void doctorChangingAvailabilityNotifiesAffectedPatients() throws Exception {
        String patient = registerPatient();
        long id = doctorId("lakshmi");
        String[] slot = freeSlot(id, 2);
        book(patient, id, slot, 201);
        String doctor = doctorToken("lakshmi.iyer");

        // Move the working hours to the afternoon only -> the booked morning slot no longer fits.
        var schedule = new java.util.ArrayList<Map<String, Object>>();
        for (String day : new String[]{"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"}) {
            schedule.add(Map.of("dayOfWeek", day, "startTime", "15:00", "endTime", "18:00", "slotMinutes", 30, "active", true));
        }
        long before = call(get("/api/notifications/unread-count"), patient, null, 200).get("count").asLong();
        JsonNode result = call(put("/api/doctor/availability"), doctor, schedule, 200);
        assertTrue(result.get("affectedAppointments").asInt() >= 1);
        assertEquals(before + 1, call(get("/api/notifications/unread-count"), patient, null, 200).get("count").asLong());

        // Invalid schedules are rejected.
        call(put("/api/doctor/availability"), doctor, java.util.List.of(
                Map.of("dayOfWeek", "MONDAY", "startTime", "12:00", "endTime", "10:00", "slotMinutes", 30, "active", true)), 400);
    }

    @Test
    void notificationsCanBeMarkedAsRead_andOnlyByTheirOwner() throws Exception {
        String patient = registerPatient();
        String doctor = doctorToken("arjun.nair");
        long id = doctorId("arjun");
        book(patient, id, freeSlot(id, 1), 201);

        JsonNode list = call(get("/api/notifications?size=5"), doctor, null, 200);
        long notifId = list.get("content").get(0).get("id").asLong();
        call(patch("/api/notifications/" + notifId + "/read"), patient, null, 404); // not mine
        call(patch("/api/notifications/" + notifId + "/read"), doctor, null, 200);
        call(patch("/api/notifications/read-all"), doctor, null, 200);
        assertEquals(0, call(get("/api/notifications/unread-count"), doctor, null, 200).get("count").asInt());
    }

    @Test
    void adminDashboardStatisticsMatchRealData() throws Exception {
        String admin = adminToken();
        long before = call(get("/api/admin/dashboard"), admin, null, 200).get("totals").get("appointments").asLong();
        String patient = registerPatient();
        long id = doctorId("meera");
        book(patient, id, freeSlot(id, 1), 201);
        JsonNode after = call(get("/api/admin/dashboard"), admin, null, 200);
        assertEquals(before + 1, after.get("totals").get("appointments").asLong());
        assertTrue(after.get("byStatus").get("PENDING").asLong() >= 1);
    }

    @Test
    void adminCanVerifyDoctorApplicationsAndDeactivateAccounts() throws Exception {
        String admin = adminToken();
        String name = applyAsDoctor();
        JsonNode pending = call(get("/api/admin/users?role=DOCTOR&verification=PENDING&q=" + name), admin, null, 200);
        assertEquals(1, pending.get("totalElements").asInt());
        long doctorProfileId = pending.get("content").get(0).get("doctorId").asLong();
        call(patch("/api/admin/doctors/" + doctorProfileId + "/verification"), admin, Map.of("status", "VERIFIED", "note", "Checked licence"), 200);
        assertEquals(1, call(get("/api/public/doctors?q=" + name), null, null, 200).get("totalElements").asInt());

        // Deactivated patients can no longer sign in.
        String email = "deact" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
        JsonNode reg = call(post("/api/auth/register/patient"), null, Map.of("fullName", "To Deactivate", "email", email, "password", PATIENT_PASSWORD), 201);
        call(patch("/api/admin/users/" + reg.get("user").get("id").asLong() + "/active"), admin, Map.of("active", false), 200);
        call(post("/api/auth/login"), null, Map.of("email", email, "password", PATIENT_PASSWORD, "portal", "PATIENT"), 403);
        // The action was audited.
        JsonNode logs = call(get("/api/admin/audit-logs?action=USER_DEACTIVATED"), admin, null, 200);
        assertTrue(logs.get("totalElements").asInt() >= 1);
    }

    @Test
    void csvExportIsAdminOnly() throws Exception {
        mvc.perform(get("/api/admin/reports/doctors.csv").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/reports/doctors.csv").header("Authorization", "Bearer " + registerPatient()))
                .andExpect(status().isForbidden());
    }
}
