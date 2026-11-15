package com.transport.notification;

import com.transport.mobility.events.*;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
class NotificationEndpoints {
    private final NotificationService notifications;
    private final JsonMapper json;

    NotificationEndpoints(NotificationService notifications, JsonMapper json) {
        this.notifications = notifications;
        this.json = json;
    }

    @RabbitListener(queues = EventTopology.NOTIFICATION)
    public void receive(Message message) {
        var attributes = message.getMessageProperties();
        Delivery.process(EventTopology.NOTIFICATION, attributes.getMessageId(),
                () -> notifications.receive(ReservationEvent.read(json, message.getBody(), attributes.getReceivedRoutingKey())));
    }

    @GetMapping("/api/v1/notifications")
    List<NotificationService.NotificationView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(defaultValue="false") boolean unread) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        return notifications.list(subject(jwt),page,size,unread);
    }

    @PatchMapping("/api/v1/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        if (!notifications.markRead(subject(jwt),id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND");
        }
    }

    private static String subject(Jwt jwt) {
        String id = jwt.getSubject();
        if (id == null || id.isBlank() || id.length()>255) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INVALID_SUBJECT");
        }
        return id;
    }
}
