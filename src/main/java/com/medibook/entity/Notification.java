package com.medibook.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notif_user_read", columnList = "user_id, readFlag, createdAt"))
@Getter @Setter
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(nullable = false, length = 40) private String type;
    @Column(nullable = false, length = 150) private String title;
    @Column(nullable = false, length = 500) private String message;
    private Long appointmentId;
    @Column(nullable = false) private boolean readFlag = false;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
