package com.transport.notification;

import com.transport.mobility.events.ReservationEvent;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LOT0_POSTGRES_URL",matches=".+")
class NotificationPostgresTest {
    @Test
    void ignoresDuplicateAndStaleEventsAndMarksOnlyOwnersNotification() throws Exception {
        String url=System.getenv("LOT0_POSTGRES_URL");
        String user=System.getenv("LOT0_POSTGRES_USER");
        String password=System.getenv("LOT0_POSTGRES_PASSWORD");
        String schema="notification_"+UUID.randomUUID().toString().replace("-","");
        try (var connection=DriverManager.getConnection(url,user,password); var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                String scoped=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
                try (var app=new SpringApplicationBuilder(NotificationApplication.class).run("--server.port=0",
                        "--spring.datasource.url="+scoped,"--spring.datasource.username="+user,
                        "--spring.datasource.password="+password,"--spring.rabbitmq.listener.simple.auto-startup=false")) {
                    var notifications=app.getBean(NotificationService.class);
                    var reservation=UUID.randomUUID();
                    var quote=UUID.randomUUID();
                    var cancellation=event(reservation,quote,"owner",4,true);
                    notifications.receive(cancellation);
                    notifications.receive(cancellation);
                    notifications.receive(event(reservation,quote,"owner",3,false));
                    assertThrows(IllegalArgumentException.class, () -> notifications.receive(event(reservation,quote,"other",5,false)));
                    var visible=notifications.list("owner",0,20,true);
                    assertEquals(1,visible.size());
                    assertEquals("CANCELLED",visible.get(0).type());
                    assertTrue(visible.get(0).current());
                    assertFalse(notifications.markRead("other",visible.get(0).id()));
                    assertTrue(notifications.markRead("owner",visible.get(0).id()));
                    var readAt=notifications.list("owner",0,20,false).get(0).readAt();
                    assertNotNull(readAt);
                    assertTrue(notifications.markRead("owner",visible.get(0).id()));
                    assertEquals(readAt,notifications.list("owner",0,20,false).get(0).readAt());
                    assertTrue(notifications.list("owner",0,20,true).isEmpty());
                    assertTrue(notifications.list("other",0,20,false).isEmpty());
                }
            } finally {
                sql.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }

    private static ReservationEvent event(UUID reservation,UUID quote,String user,long version,boolean cancelled) {
        return new ReservationEvent(UUID.randomUUID(),cancelled ? "reservation.cancelled.v1" : "reservation.confirmed.v1",
                1,Instant.now(),reservation,version,user,reservation,reservation,
                new ReservationEvent.Payload(quote,cancelled ? ReservationEvent.Status.CANCELLED : ReservationEvent.Status.CONFIRMED,
                        ReservationEvent.QuoteOutcome.CONSUMED,true,null));
    }
}
