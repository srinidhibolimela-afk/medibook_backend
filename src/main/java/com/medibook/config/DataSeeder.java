package com.medibook.config;

import com.medibook.dto.Dtos;
import com.medibook.entity.*;
import com.medibook.repository.*;
import com.medibook.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs at startup:
 *  1. Specializations (only if the table is empty).
 *  2. The ADMIN account - only if MEDIBOOK_ADMIN_EMAIL and MEDIBOOK_ADMIN_PASSWORD are set. No default password exists.
 *  3. Demo mode: clearly labelled SAMPLE doctors and one sample patient. No fake reviews or appointments are created.
 */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final Pattern PASSWORD = Pattern.compile(Dtos.PASSWORD_REGEX);

    private final AppProperties props;
    private final SpecializationRepository specs;
    private final UserRepository users;
    private final PatientProfileRepository patients;
    private final DoctorAvailabilityRepository availability;
    private final PasswordEncoder encoder;
    private final AuthService authService;

    public DataSeeder(AppProperties props, SpecializationRepository specs, UserRepository users,
                      PatientProfileRepository patients, DoctorAvailabilityRepository availability,
                      PasswordEncoder encoder, AuthService authService) {
        this.props = props;
        this.specs = specs;
        this.users = users;
        this.patients = patients;
        this.availability = availability;
        this.encoder = encoder;
        this.authService = authService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedSpecializations();
        seedAdmin();
        if (props.seed().demoData()) seedDemo();
    }

    private void seedSpecializations() {
        if (specs.count() > 0) return;
        String[][] data = {
                {"General Physician", "Fever, infections, general health checks"},
                {"Cardiology", "Heart and blood-pressure care"},
                {"Dermatology", "Skin, hair and nail conditions"},
                {"Pediatrics", "Health care for infants and children"},
                {"Orthopedics", "Bones, joints and sports injuries"},
                {"Gynecology", "Women's health and pregnancy care"},
                {"Neurology", "Brain, spine and nerve disorders"},
                {"ENT", "Ear, nose and throat"},
                {"Ophthalmology", "Eye care and vision"},
                {"Psychiatry", "Mental health and counselling"},
                {"Dentistry", "Teeth and oral health"},
                {"Endocrinology", "Diabetes, thyroid and hormones"}};
        for (String[] row : data) {
            Specialization s = new Specialization();
            s.setName(row[0]);
            s.setDescription(row[1]);
            specs.save(s);
        }
        log.info("Seeded {} specializations", data.length);
    }

    private void seedAdmin() {
        String email = props.seed().adminEmail();
        String password = props.seed().adminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            if (!users.existsByRole(Role.ADMIN)) {
                log.warn("No admin account exists. Set MEDIBOOK_ADMIN_EMAIL and MEDIBOOK_ADMIN_PASSWORD and restart to create one.");
            }
            return;
        }
        if (users.existsByEmailIgnoreCase(email.trim())) return;
        if (!PASSWORD.matcher(password).matches()) {
            log.error("MEDIBOOK_ADMIN_PASSWORD is too weak (need 8-64 chars with a letter and a number). Admin NOT created.");
            return;
        }
        UserAccount a = new UserAccount();
        a.setEmail(email.trim().toLowerCase());
        a.setPasswordHash(encoder.encode(password));
        a.setFullName(props.seed().adminName());
        a.setRole(Role.ADMIN);
        users.save(a);
        log.info("Admin account created for {}", a.getEmail());
    }

    private void seedDemo() {
        if (users.existsByDemoTrue()) return;
        String password = props.seed().demoPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) password = randomPassword();

        record D(String name, String email, String spec, String quals, int exp, int fee, String clinic, String city,
                 String bio, int slot, boolean pending) {}
        List<D> doctors = List.of(
                new D("Dr. Meera Rao", "meera.rao", "Cardiology", "MBBS, MD (Cardiology)", 14, 900, "Sample Heart Clinic", "Hyderabad",
                        "Sample profile. Interventional cardiologist focused on preventive heart care.", 30, false),
                new D("Dr. Arjun Nair", "arjun.nair", "General Physician", "MBBS, MD (Internal Medicine)", 9, 500, "Sample Family Care Centre", "Hyderabad",
                        "Sample profile. Family physician for everyday illnesses and health checks.", 20, false),
                new D("Dr. Sana Khan", "sana.khan", "Dermatology", "MBBS, DVD", 7, 700, "Sample Skin Studio", "Secunderabad",
                        "Sample profile. Treats acne, eczema and hair-loss concerns.", 30, false),
                new D("Dr. Rohit Verma", "rohit.verma", "Pediatrics", "MBBS, DCH", 11, 600, "Sample Children's Hospital", "Bengaluru",
                        "Sample profile. Child health, vaccinations and growth monitoring.", 20, false),
                new D("Dr. Lakshmi Iyer", "lakshmi.iyer", "Gynecology", "MBBS, MS (OBG)", 18, 1000, "Sample Women's Clinic", "Chennai",
                        "Sample profile. Pregnancy care and women's wellness.", 30, false),
                new D("Dr. Imran Qureshi", "imran.qureshi", "Orthopedics", "MBBS, MS (Ortho)", 12, 800, "Sample Bone & Joint Centre", "Hyderabad",
                        "Sample profile. Sports injuries and joint-replacement follow-up.", 30, false),
                new D("Dr. Pooja Desai", "pooja.desai", "Psychiatry", "MBBS, MD (Psychiatry)", 8, 1200, "Sample Mind Wellness", "Mumbai",
                        "Sample profile. Anxiety, depression and stress counselling.", 45, false),
                new D("Dr. Karthik Menon", "karthik.menon", "ENT", "MBBS, MS (ENT)", 5, 550, "Sample ENT Clinic", "Hyderabad",
                        "Sample profile - verification pending so admins can try the approval workflow.", 15, true));

        for (D d : doctors) {
            Specialization spec = specs.findByNameIgnoreCase(d.spec()).orElseThrow();
            var req = new Dtos.RegisterDoctorRequest(d.name(), d.email() + "@demo.medibook.local", password, null, spec.getId(),
                    d.quals(), d.exp(), BigDecimal.valueOf(d.fee()), d.clinic(), d.city(), d.bio());
            DoctorProfile dp = authService.createDoctor(req, d.pending() ? VerificationStatus.PENDING : VerificationStatus.VERIFIED, true);
            for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)) {
                DoctorAvailability av = new DoctorAvailability();
                av.setDoctor(dp);
                av.setDayOfWeek(day);
                av.setStartTime(LocalTime.of(day == DayOfWeek.SATURDAY ? 10 : 9, 0));
                av.setEndTime(LocalTime.of(day == DayOfWeek.SATURDAY ? 13 : 17, 0));
                av.setSlotMinutes(d.slot());
                availability.save(av);
            }
        }

        UserAccount p = new UserAccount();
        p.setEmail("demo.patient@demo.medibook.local");
        p.setPasswordHash(encoder.encode(password));
        p.setFullName("Aarav Sample-Patient");
        p.setRole(Role.PATIENT);
        p.setDemo(true);
        users.save(p);
        PatientProfile pp = new PatientProfile();
        pp.setUser(p);
        pp.setGender("Male");
        patients.save(pp);

        log.info("Demo data loaded (SAMPLE doctors + 1 sample patient: demo.patient@demo.medibook.local).");
        if (generated) log.warn("Generated DEMO password (shown once, all demo accounts): {}", password);
        else log.info("Demo accounts use the password from MEDIBOOK_DEMO_PASSWORD.");
    }

    private static String randomPassword() {
        String letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
        String all = letters + "23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(all.charAt(r.nextInt(all.length())));
        return sb.append(letters.charAt(r.nextInt(letters.length()))).append(r.nextInt(10)).toString();
    }
}
