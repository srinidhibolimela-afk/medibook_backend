package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created", columnList = "createdAt"),
        @Index(name = "idx_audit_action", columnList = "action")})
@Getter @Setter
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150) private String actorEmail;
    @Column(nullable = false, length = 20) private String actorRole;
    @Column(nullable = false, length = 60) private String action;
    @Column(length = 40) private String targetType;
    private Long targetId;
    @Column(length = 500) private String details;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
