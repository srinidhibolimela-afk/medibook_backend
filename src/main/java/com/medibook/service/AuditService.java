package com.medibook.service;

import com.medibook.dto.Dtos.AuditLogDto;
import com.medibook.dto.Dtos.PageResponse;
import com.medibook.entity.AuditLog;
import com.medibook.repository.AuditLogRepository;
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

/** Writes the audit trail of sensitive actions and lets admins read it. */
@Service
@Transactional
public class AuditService {
    private final AuditLogRepository repo;
    private final Mapper mapper;

    public AuditService(AuditLogRepository repo, Mapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public void log(CurrentUser actor, String action, String targetType, Long targetId, String details) {
        log(actor.email(), actor.role().name(), action, targetType, targetId, details);
    }

    public void log(String actorEmail, String actorRole, String action, String targetType, Long targetId, String details) {
        AuditLog a = new AuditLog();
        a.setActorEmail(actorEmail);
        a.setActorRole(actorRole);
        a.setAction(action);
        a.setTargetType(targetType);
        a.setTargetId(targetId);
        a.setDetails(details == null ? null : (details.length() > 490 ? details.substring(0, 490) : details));
        repo.save(a);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> search(String q, String action, int page, int size) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (action != null && !action.isBlank()) p.add(cb.equal(root.get("action"), action));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("actorEmail")), like), cb.like(cb.lower(root.get("details")), like)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return PageResponse.of(repo.findAll(spec, PageRequest.of(page, clamp(size), Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(mapper::audit));
    }

    static int clamp(int size) { return Math.max(1, Math.min(size, 100)); }
}
