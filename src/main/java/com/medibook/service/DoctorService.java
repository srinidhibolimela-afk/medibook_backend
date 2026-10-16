package com.medibook.service;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.exception.ApiException;
import com.medibook.repository.*;
import com.medibook.security.CurrentUser;
import com.medibook.util.Mapper;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class DoctorService {
    private final DoctorProfileRepository doctors;
    private final DoctorAvailabilityRepository availability;
    private final SpecializationRepository specializations;
    private final ReviewRepository reviews;
    private final UserRepository users;
    private final AvailabilityService availabilityService;
    private final Mapper mapper;
    private final Clock clock;

    public DoctorService(DoctorProfileRepository doctors, DoctorAvailabilityRepository availability,
                         SpecializationRepository specializations, ReviewRepository reviews, UserRepository users,
                         AvailabilityService availabilityService, Mapper mapper, Clock clock) {
        this.doctors = doctors;
        this.availability = availability;
        this.specializations = specializations;
        this.reviews = reviews;
        this.users = users;
        this.availabilityService = availabilityService;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Filters accepted by the public directory. Every field is optional. */
    public record Search(String q, Long specializationId, String location, BigDecimal minFee, BigDecimal maxFee,
                         Integer minExperience, Double minRating, Integer availableWithinDays, String sort) {}

    // ---------------------------------------------------------------- public directory
    @Transactional(readOnly = true)
    public List<SpecializationDto> specializations() {
        return specializations.findAll().stream()
                .sorted(Comparator.comparing(Specialization::getName))
                .map(s -> new SpecializationDto(s.getId(), s.getName(), s.getDescription(), doctors.countBySpecializationId(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<DoctorCard> search(Search f, int page, int size) {
        Specification<DoctorProfile> spec = (root, query, cb) -> {
            Join<DoctorProfile, UserAccount> user = root.join("user");
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("verificationStatus"), VerificationStatus.VERIFIED));
            p.add(cb.isTrue(user.get("active")));
            if (f.q() != null && !f.q().isBlank()) p.add(cb.like(cb.lower(user.get("fullName")), "%" + f.q().trim().toLowerCase() + "%"));
            if (f.specializationId() != null) p.add(cb.equal(root.get("specialization").get("id"), f.specializationId()));
            if (f.location() != null && !f.location().isBlank()) p.add(cb.like(cb.lower(root.get("location")), "%" + f.location().trim().toLowerCase() + "%"));
            if (f.minFee() != null) p.add(cb.greaterThanOrEqualTo(root.get("consultationFee"), f.minFee()));
            if (f.maxFee() != null) p.add(cb.lessThanOrEqualTo(root.get("consultationFee"), f.maxFee()));
            if (f.minExperience() != null) p.add(cb.greaterThanOrEqualTo(root.get("experienceYears"), f.minExperience()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        List<DoctorCard> cards = cards(doctors.findAll(spec));

        // Rating and availability are computed values, so those filters run after the database filter.
        LocalDate today = LocalDate.now(clock);
        if (f.minRating() != null) {
            cards = cards.stream().filter(c -> c.averageRating() != null && c.averageRating() >= f.minRating()).toList();
        }
        if (f.availableWithinDays() != null) {
            LocalDate limit = today.plusDays(f.availableWithinDays());
            cards = cards.stream().filter(c -> c.nextAvailable() != null && !c.nextAvailable().toLocalDate().isAfter(limit)).toList();
        }
        cards = new ArrayList<>(cards);
        cards.sort(comparator(f.sort()));
        return PageResponse.ofList(cards, Math.max(page, 0), AuditService.clamp(size));
    }

    private Comparator<DoctorCard> comparator(String sort) {
        String s = sort == null ? "rating" : sort;
        Comparator<DoctorCard> byName = Comparator.comparing(DoctorCard::name, String.CASE_INSENSITIVE_ORDER);
        return switch (s) {
            case "experience" -> Comparator.comparingInt(DoctorCard::experienceYears).reversed().thenComparing(byName);
            case "fee_asc" -> Comparator.comparing(DoctorCard::consultationFee).thenComparing(byName);
            case "fee_desc" -> Comparator.comparing(DoctorCard::consultationFee).reversed().thenComparing(byName);
            case "next" -> Comparator.comparing(DoctorCard::nextAvailable, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(byName);
            case "name" -> byName;
            default -> Comparator.comparing(DoctorCard::averageRating, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparingLong(DoctorCard::reviewCount).reversed()).thenComparing(byName);
        };
    }

    /** Builds directory cards with real review averages and the real next free slot. */
    @Transactional(readOnly = true)
    public List<DoctorCard> cards(List<DoctorProfile> list) {
        Map<Long, double[]> stats = new HashMap<>();
        for (Object[] row : reviews.ratingStats()) {
            stats.put((Long) row[0], new double[]{((Number) row[1]).doubleValue(), ((Number) row[2]).doubleValue()});
        }
        Map<Long, List<DoctorAvailability>> schedules = availability.findByActiveTrue().stream()
                .collect(Collectors.groupingBy(a -> a.getDoctor().getId()));
        List<DoctorCard> out = new ArrayList<>();
        for (DoctorProfile d : list) {
            double[] st = stats.get(d.getId());
            LocalDateTime next = availabilityService.nextAvailable(d.getId(), schedules.get(d.getId())).orElse(null);
            out.add(mapper.card(d, st == null ? null : st[0], st == null ? 0 : (long) st[1], next));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public DoctorDetail detail(Long id) {
        DoctorProfile d = publicDoctor(id);
        DoctorCard card = cards(List.of(d)).get(0);
        Map<Integer, Long> hist = new TreeMap<>();
        for (int i = 1; i <= 5; i++) hist.put(i, 0L);
        for (Object[] row : reviews.ratingHistogram(id)) hist.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        return new DoctorDetail(card, d.getBio(), hist, availabilityService.schedule(id));
    }

    public DoctorProfile publicDoctor(Long id) {
        DoctorProfile d = doctors.findById(id).orElseThrow(() -> ApiException.notFound("Doctor not found"));
        if (d.getVerificationStatus() != VerificationStatus.VERIFIED || !d.getUser().isActive()) {
            throw ApiException.notFound("Doctor not found");
        }
        return d;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> publicReviews(Long doctorId, int page, int size) {
        publicDoctor(doctorId);
        return PageResponse.of(reviews.findByDoctorIdAndHiddenFalseOrderByCreatedAtDesc(doctorId,
                PageRequest.of(Math.max(page, 0), AuditService.clamp(size))).map(r -> mapper.review(r, false)));
    }

    // ---------------------------------------------------------------- doctor self-service
    public DoctorProfile requireOwn(CurrentUser me) {
        return doctors.findByUserId(me.id()).orElseThrow(() -> ApiException.notFound("Doctor profile not found"));
    }

    @Transactional(readOnly = true)
    public DoctorSelfView self(CurrentUser me) { return mapper.selfView(requireOwn(me)); }

    public DoctorSelfView updateSelf(CurrentUser me, UpdateDoctorProfileRequest r) {
        DoctorProfile d = requireOwn(me);
        Specialization spec = specializations.findById(r.specializationId())
                .orElseThrow(() -> ApiException.badRequest("Selected specialization does not exist"));
        UserAccount u = d.getUser();
        u.setFullName(r.fullName().trim());
        u.setPhone(r.phone() == null || r.phone().isBlank() ? null : r.phone().trim());
        d.setSpecialization(spec);
        d.setQualifications(r.qualifications());
        d.setExperienceYears(r.experienceYears());
        d.setConsultationFee(r.consultationFee());
        d.setClinicName(r.clinicName());
        d.setLocation(r.location());
        d.setBio(r.bio());
        d.setPhotoUrl(r.photoUrl() == null || r.photoUrl().isBlank() ? null : r.photoUrl());
        return mapper.selfView(d);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> ownReviews(CurrentUser me, int page, int size) {
        DoctorProfile d = requireOwn(me);
        return PageResponse.of(reviews.findByDoctorIdAndHiddenFalseOrderByCreatedAtDesc(d.getId(),
                PageRequest.of(Math.max(page, 0), AuditService.clamp(size))).map(r -> mapper.review(r, false)));
    }
}
