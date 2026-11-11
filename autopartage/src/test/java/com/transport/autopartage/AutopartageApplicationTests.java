package com.transport.autopartage;

import com.transport.autopartage.vehiculeRoute.RouteService;
import com.transport.autopartage.vehiculeRoute.RouteService.HoldConflictException;
import com.transport.autopartage.vehiculeRoute.dto.LocationRequestDTO;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "LOT0_POSTGRES_URL", matches = ".+")
class AutopartageApplicationTests {
    private static final LocalDate START = LocalDate.of(2026, 11, 15);
    private static final LocalDate END = START.plusDays(1);

    @Test
    void migratesFreshSchemaAndProtectsConcurrentHolds() throws Exception {
        withSchema((url, user, password) -> {
            try (var context = start(url, user, password, "demo");
                    var connection = DriverManager.getConnection(url, user, password);
                    var sql = connection.createStatement()) {
                assertEquals("6", flyway(url, user, password).info().current().getVersion().getVersion());
                long vehicle = queryLong(sql, "SELECT id FROM vehicules WHERE immatriculation = 'DEMO-LOT1'");
                long destination = queryLong(sql, "SELECT id FROM villes WHERE nom = 'Versailles'");
                String copy = """
                    INSERT INTO vehicules (owner_subject, marque, modele, immatriculation,
                        couleur, ville_id, categorie_id, added_at, is_available)
                    SELECT owner_subject, marque, modele, immatriculation, couleur, ville_id,
                        categorie_id, added_at, false FROM vehicules
                    """;
                assertEquals("23505", assertThrows(SQLException.class, () -> sql.executeUpdate(copy)).getSQLState());

                var routes = context.getBean(RouteService.class);
                assertEquals(1, routes.getRoutesFrom("Paris", START, "Citadine").getTotalElements());
                var request = new LocationRequestDTO("test-renter", destination);
                // A failed link insert must roll back the Location created in the same transaction.
                sql.execute("ALTER TABLE vehiculelocations ADD CONSTRAINT reject_link CHECK (false)");
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                        () -> routes.attemptHold(vehicle, START, END, request));
                assertEquals(0, queryLong(sql, "SELECT count(*) FROM locations"));
                sql.execute("ALTER TABLE vehiculelocations DROP CONSTRAINT reject_link");

                var ready = new CountDownLatch(2);
                var go = new CountDownLatch(1);
                var executor = Executors.newFixedThreadPool(2);
                try {
                    var first = executor.submit(() -> compete(routes, vehicle, START, END, request, ready, go));
                    var second = executor.submit(() -> compete(routes, vehicle, END, END, request, ready, go));
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    go.countDown();
                    assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }
                assertEquals(1, queryLong(sql, "SELECT count(*) FROM locations WHERE status = 'PENDING' AND rating_owner IS NULL"));
                assertEquals(1, queryLong(sql, "SELECT count(*) FROM vehiculelocations"));
                var page = org.springframework.data.domain.PageRequest.of(0, 20);
                assertEquals(1, routes.history("test-renter", false, page).getTotalElements());
                assertEquals(1, routes.history("74fe3186-57c1-43a6-8f75-95cc328f77b3", true, page).getTotalElements());
                assertTrue(routes.history("unrelated-user", true, page).isEmpty());
                assertTrue(routes.history("unrelated-user", false, page).isEmpty());
            }
        });
    }

    @Test
    void convertsHistoricalOrdinalsAndDisablesVehicleWithoutInventedPrice() throws Exception {
        withSchema((url, user, password) -> {
            Flyway.configure().dataSource(url, user, password).target("2").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                    var sql = connection.createStatement()) {
                sql.execute("INSERT INTO villes (nom) VALUES ('Paris')");
                sql.execute("INSERT INTO categories (nom) VALUES ('Citadine')");
                sql.execute("ALTER TABLE locations ADD CONSTRAINT legacy_status CHECK (status BETWEEN 0 AND 3)");
                sql.execute("""
                    INSERT INTO locations (owner_id, renter_id, depart_id, destination_id,
                                           date_debut, date_fin, joined_at, status)
                    SELECT 17, 23, id, id, CURRENT_DATE, CURRENT_DATE, CURRENT_DATE, n
                    FROM villes CROSS JOIN generate_series(0, 3) AS ordinals(n)
                    """);
                sql.execute("""
                    INSERT INTO vehicules (owner_id, marque, modele, immatriculation,
                                           couleur, ville_id, categorie_id, added_at, is_available)
                    SELECT 17, 'Test', 'Test', 'OLD', 'Bleu', v.id, c.id, CURRENT_DATE, true
                    FROM villes v CROSS JOIN categories c
                    """);
            }
            assertThrows(org.flywaydb.core.api.FlywayException.class,
                    () -> flyway(url, user, password).migrate());
            try (var connection = DriverManager.getConnection(url, user, password);
                    var sql = connection.createStatement()) {
                sql.execute("""
                    CREATE TABLE legacy_identity_mapping (
                        legacy_id bigint PRIMARY KEY CHECK (legacy_id > 0),
                        subject varchar(255) NOT NULL UNIQUE CHECK (btrim(subject) <> '')
                    )
                    """);
                sql.execute("INSERT INTO legacy_identity_mapping VALUES (17, 'test-owner'), (23, 'test-renter')");
            }
            try (var context = start(url, user, password, "test");
                    var connection = DriverManager.getConnection(url, user, password);
                    var sql = connection.createStatement()) {
                try (var result = sql.executeQuery("SELECT status, owner_id, renter_id, owner_subject, renter_subject FROM locations ORDER BY id")) {
                    for (String status : new String[]{"PENDING", "CONFIRMED", "CANCELLED", "FAILED"}) {
                        assertTrue(result.next());
                        assertEquals(status, result.getString("status"));
                        assertEquals(17, result.getLong("owner_id"));
                        assertEquals(23, result.getLong("renter_id"));
                        assertEquals("test-owner", result.getString("owner_subject"));
                        assertEquals("test-renter", result.getString("renter_subject"));
                    }
                    assertFalse(result.next());
                }
                assertEquals(1, queryLong(sql, """
                    SELECT count(*) FROM vehicules WHERE immatriculation = 'OLD'
                        AND NOT is_available AND daily_rate_minor IS NULL AND passenger_capacity IS NULL
                    """));
                assertEquals(0, queryLong(sql, "SELECT count(*) FROM vehicules WHERE immatriculation = 'DEMO-LOT1'"));
            }
        });
    }

    private static boolean compete(RouteService routes, long vehicle, LocalDate from, LocalDate to,
            LocationRequestDTO request, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent hold did not start");
        }
        try {
            routes.attemptHold(vehicle, from, to, request);
            return true;
        } catch (HoldConflictException e) {
            return false;
        }
    }

    private static long queryLong(java.sql.Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static Flyway flyway(String url, String user, String password) {
        return Flyway.configure().dataSource(url, user, password).load();
    }

    private static org.springframework.context.ConfigurableApplicationContext start(
            String url, String user, String password, String profile) {
        return new SpringApplicationBuilder(AutopartageApplication.class).profiles(profile).run(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + user, "--spring.datasource.password=" + password);
    }

    private static void withSchema(SchemaTest test) throws Exception {
        String url = System.getenv("LOT0_POSTGRES_URL");
        String user = System.getenv("LOT0_POSTGRES_USER");
        String password = System.getenv("LOT0_POSTGRES_PASSWORD");
        String schema = "lot1_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                test.run(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @FunctionalInterface
    private interface SchemaTest {
        void run(String url, String user, String password) throws Exception;
    }
}
