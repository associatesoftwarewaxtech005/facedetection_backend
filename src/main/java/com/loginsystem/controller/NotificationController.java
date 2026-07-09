package com.loginsystem.controller;

import com.loginsystem.model.SystemNotification;
import com.loginsystem.repository.SystemNotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private SystemNotificationRepository systemNotificationRepository;

    @GetMapping
    public List<SystemNotification> getNotifications() {
        return systemNotificationRepository.findByIsReadFalseOrderByTimestampDesc();
    }

    @PostMapping("/read")
    public ResponseEntity<?> markAllAsRead() {
        List<SystemNotification> unread = systemNotificationRepository.findByIsReadFalseOrderByTimestampDesc();
        for (SystemNotification notif : unread) {
            notif.setIsRead(true);
        }
        systemNotificationRepository.saveAll(unread);
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read."));
    }

    @PostMapping("/trigger")
    public ResponseEntity<?> triggerNotification(@RequestBody Map<String, String> payload) {
        String message = payload.get("message");
        String type = payload.get("type"); // WARN, INFO, UNKNOWN_FACE
        
        SystemNotification notification = new SystemNotification(message, type);
        systemNotificationRepository.save(notification);
        
        return ResponseEntity.ok(notification);
    }
}
