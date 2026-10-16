package com.medibook.controller;

import com.medibook.dto.Dtos.*;
import com.medibook.security.CurrentUser;
import com.medibook.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public PageResponse<NotificationDto> list(@AuthenticationPrincipal CurrentUser me,
                                              @RequestParam(defaultValue = "false") boolean unreadOnly,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "15") int size) {
        return service.list(me, unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCount unread(@AuthenticationPrincipal CurrentUser me) { return new UnreadCount(service.unreadCount(me)); }

    @PatchMapping("/{id}/read")
    public MessageResponse read(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id) {
        service.markRead(me, id);
        return new MessageResponse("Marked as read");
    }

    @PatchMapping("/read-all")
    public MessageResponse readAll(@AuthenticationPrincipal CurrentUser me) {
        int n = service.markAllRead(me);
        return new MessageResponse(n + " notification(s) marked as read");
    }
}
