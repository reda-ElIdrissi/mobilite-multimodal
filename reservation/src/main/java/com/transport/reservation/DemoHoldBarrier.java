package com.transport.reservation;

import com.transport.reservation.ReservationModel.Case;
import com.transport.reservation.ReservationModel.Line;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo & demo-contention")
class DemoHoldBarrier {
    private static final String FLIGHT = "AIR-CMN-MAD-1800";
    private final Path directory;

    DemoHoldBarrier(@Value("${mobility.demo.barrier-dir:/demo}") Path directory) {
        this.directory = directory;
    }

    boolean beforeHold(Case reservation, Line line) {
        if (line.position() != 1 || !FLIGHT.equals(line.allocation().offerId())) {
            return true;
        }
        String id = reservation.id().toString();
        var release = directory.resolve("t10.release");
        try {
            Files.writeString(directory.resolve("t10.ready"), id);
            long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < end) {
                if (Files.isRegularFile(release) && id.equals(Files.readString(release).trim())) {
                    return true;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException unavailable) {
            return false;
        }
        return false;
    }
}
