package com.medibook.service;

import com.medibook.dto.Dtos.NotificationDto;
import com.medibook.dto.Dtos.PageResponse;
import com.medibook.entity.Notification;
import com.medibook.entity.Role;
import com.medibook.entity.UserAccount;
import com.medibook.exception.ApiException;
import com.medibook.repository.NotificationRepository;
import com.medibook.repository.UserRepository;
import com.medibook.security.CurrentUser;
import com.medibook.util.Mapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notification centre. Any service can call notify(...). */
@Service
@Transactional
public class NotificationService {
    private final NotificationRepository repo;
    private final UserRepository users;
    private final Mapper mapper;

    public NotificationService(NotificationRepository repo, UserRepository users, Mapper mapper) {
        this.repo = repo;
        this.users = users;
        this.mapper = mapper;
    }

    public void notify(UserAccount user, String type, String title, String message, Long appointmentId) {
        Notification n = new Notification();
        n.setUser(user);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message.length() > 490 ? message.substring(0, 490) : message);
        n.setAppointmentId(appointmentId);
        repo.save(n);
    }

    public void notifyAdmins(String type, String title, String message) {
        for (UserAccount admin : users.findByRoleAndActiveTrue(Role.ADMIN)) {
            notify(admin, type, title, message, null);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(CurrentUser me, boolean unreadOnly, int page, int size) {
        var pageable = PageRequest.of(page, AuditService.clamp(size));
        var result = unreadOnly ? repo.findByUserIdAndReadFlagFalseOrderByCreatedAtDesc(me.id(), pageable)
                : repo.findByUserIdOrderByCreatedAtDesc(me.id(), pageable);
        return PageResponse.of(result.map(mapper::notification));
    }

    @Transactional(readOnly = true)
    public long unreadCount(CurrentUser me) { return repo.countByUserIdAndReadFlagFalse(me.id()); }

    public void markRead(CurrentUser me, Long id) {
        Notification n = repo.findById(id).orElseThrow(() -> ApiException.notFound("Notification not found"));
        // Ownership check: you can only touch your own notifications.
        if (!n.getUser().getId().equals(me.id())) throw ApiException.notFound("Notification not found");
        n.setReadFlag(true);
    }

    public int markAllRead(CurrentUser me) { return repo.markAllRead(me.id()); }
}
