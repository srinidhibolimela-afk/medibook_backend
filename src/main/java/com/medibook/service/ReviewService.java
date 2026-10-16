package com.medibook.service;

import com.medibook.dto.Dtos.*;
import com.medibook.entity.*;
import com.medibook.exception.ApiException;
import com.medibook.repository.AppointmentRepository;
import com.medibook.repository.ReviewRepository;
import com.medibook.repository.UserRepository;
import com.medibook.security.CurrentUser;
import com.medibook.util.Mapper;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class ReviewService {
    private final ReviewRepository reviews;
    private final AppointmentRepository appointments;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Mapper mapper;

    public ReviewService(ReviewRepository reviews, AppointmentRepository appointments, UserRepository users,
                         NotificationService notifications, AuditService audit, Mapper mapper) {
        this.reviews = reviews;
        this.appointments = appointments;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
    }

    public ReviewDto create(CurrentUser me, ReviewRequest r) {
        Appointment a = appointments.findById(r.appointmentId()).orElseThrow(() -> ApiException.notFound("Appointment not found"));
        // Only the patient who attended can review - and only completed visits.
        if (!a.getPatient().getId().equals(me.id())) throw ApiException.forbidden("You can only review your own appointments.");
        if (a.getStatus() != AppointmentStatus.COMPLETED) {
            throw ApiException.badRequest("You can review a doctor only after the appointment is completed.");
        }
        if (reviews.existsByAppointmentId(a.getId())) throw ApiException.conflict("You have already reviewed this appointment.");

        Review rv = new Review();
        rv.setAppointment(a);
        rv.setPatient(a.getPatient());
        rv.setDoctor(a.getDoctor());
        rv.setRating(r.rating());
        rv.setComment(r.comment() == null || r.comment().isBlank() ? null : r.comment().trim());
        reviews.saveAndFlush(rv);
        notifications.notify(a.getDoctor().getUser(), "REVIEW_RECEIVED", "New review",
                "You received a " + r.rating() + "-star review from " + Mapper.shortName(a.getPatient().getFullName()) + ".", a.getId());
        return mapper.review(rv, false);
    }

    @Transactional(readOnly = true)
    public List<ReviewDto> mine(CurrentUser me) {
        Specification<Review> spec = (root, q, cb) -> cb.equal(root.get("patient").get("id"), me.id());
        return reviews.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream().map(r -> mapper.review(r, false)).toList();
    }

    // ---------------------------------------------------------------- admin moderation
    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> adminList(Boolean hidden, Integer maxRating, String q, int page, int size) {
        Specification<Review> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (hidden != null) p.add(cb.equal(root.get("hidden"), hidden));
            if (maxRating != null) p.add(cb.lessThanOrEqualTo(root.get("rating"), maxRating));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("comment")), like),
                        cb.like(cb.lower(root.get("doctor").get("user").get("fullName")), like)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return PageResponse.of(reviews.findAll(spec, PageRequest.of(Math.max(page, 0), AuditService.clamp(size),
                Sort.by(Sort.Direction.DESC, "createdAt"))).map(r -> mapper.review(r, true)));
    }

    public ReviewDto moderate(CurrentUser admin, Long id, HideReviewRequest r) {
        Review rv = reviews.findById(id).orElseThrow(() -> ApiException.notFound("Review not found"));
        rv.setHidden(r.hidden());
        rv.setHiddenReason(r.hidden() ? (r.reason() == null || r.reason().isBlank() ? "Removed by moderator" : r.reason().trim()) : null);
        audit.log(admin, r.hidden() ? "REVIEW_HIDDEN" : "REVIEW_RESTORED", "Review", id,
                "Review for " + rv.getDoctor().getUser().getFullName() + (r.hidden() ? ": " + rv.getHiddenReason() : ""));
        return mapper.review(rv, true);
    }
}
