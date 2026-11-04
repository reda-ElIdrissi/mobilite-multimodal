package com.transport.rail;

import com.transport.mobility.observability.Telemetry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Properties;
import mobility.rail.RailBookingHelper;
import mobility.rail.Trip;
import org.omg.CORBA.ORB;

final class RailClient implements AutoCloseable {
    private final Path iorPath;
    private final ORB orb;

    RailClient(Path iorPath) {
        this.iorPath = iorPath;
        Properties settings = new Properties();
        settings.setProperty("org.omg.CORBA.ORBClass", "org.jacorb.orb.ORB");
        settings.setProperty("org.omg.CORBA.ORBSingletonClass", "org.jacorb.orb.ORBSingleton");
        settings.setProperty("jacorb.connection.client.connect_timeout", "1500");
        settings.setProperty("jacorb.connection.client.pending_reply_timeout", "4000");
        orb = ORB.init(new String[0], settings);
    }

    Trip[] search(String origin, String destination, LocalDate date) throws IOException {
        // Reload on every call so an operator restart can publish a new object reference.
        return Telemetry.legacy("RAIL", "iiop", "search", () -> reference().search(origin, destination, date.toString()));
    }

    mobility.rail.RailBooking reference() throws IOException {
        String ior = Files.readString(iorPath).strip();
        if (!ior.startsWith("IOR:")) {
            throw new IOException("No valid rail IOR is available");
        }
        return RailBookingHelper.narrow(orb.string_to_object(ior));
    }

    @Override
    public void close() {
        orb.destroy();
    }
}
