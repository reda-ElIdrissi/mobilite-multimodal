package com.transport.reservation;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.QuoteContracts.Price;
import com.transport.mobility.contracts.v1.QuoteContracts.Quote;
import com.transport.reservation.ReservationModel.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LOT0_POSTGRES_URL", matches=".+")
class SagaPostgresTest {
    @Test
    void survivesRestartAndCancelsConfirmedResources() throws Exception {
        database((store,db)-> {
            var partner = new Partner();
            var item = create(store,partner);
            var worker = new SagaWorker(store,partner,Duration.ofSeconds(30));
            tick(store,worker,item.id());
            assertEquals(Status.HOLDING,store.find(item.id(),false).status());
            var first = store.lines(item.id()).get(0);
            var prepared = store.start(item.id(),first.id(),Action.HOLD,"",null);
            partner.journal.put(prepared.id(),Result.success("HOLD-0",Instant.now().plusSeconds(600)));
            var restarted = new SagaWorker(store,partner,Duration.ofSeconds(30));
            drive(store,restarted,item.id(),Status.CONFIRMED);
            assertEquals(List.of("HOLD-1","CONFIRM-0","CONFIRM-1"),partner.sent);
            partner.loseCancelReceipt = true;
            store.transaction(()->{ store.markCancel(store.find(item.id(),true)); return null; });
            drive(store,restarted,item.id(),Status.CANCELLED);
            assertEquals(List.of("BOOKING:BOOK-1","BOOKING:BOOK-0"),partner.cancelled);
            assertEquals("CONSUMED",ReservationStore.quoteOutcome(store.find(item.id(),false)));
        });
    }

    @Test
    void compensatesEarlierHoldAfterDefiniteSecondHoldRejection() throws Exception {
        database((store,db)-> {
            var partner = new Partner();
            partner.rejectHoldAt = 1;
            var item = create(store,partner);
            var worker = new SagaWorker(store,partner,Duration.ofSeconds(30));
            drive(store,worker,item.id(),Status.FAILED_COMPENSATED);
            assertEquals(List.of("HOLD-0","HOLD-1","CANCEL-0"),partner.sent);
            assertEquals(List.of("HOLD:HOLD-0"),partner.cancelled);
            assertEquals("VOID",ReservationStore.quoteOutcome(store.find(item.id(),false)));
        });
    }

    @Test
    void compensatesTrainAndFirstCarWhenSecondParallelCarIsRejected() throws Exception {
        database((store, db) -> {
            var partner = new Partner();
            partner.rejectHoldAt = 2;
            var train = price().itinerary().segments().get(0);
            var arrival = train.plannedArrival();
            var rabat = train.destination();
            var fes = new Place("FES", "Fes", ZoneOffset.UTC);
            var departure = arrival.plusMinutes(30);
            var day = departure.toLocalDate();
            var cars = List.of(
                    carAllocation("car-1", "p1", departure, day),
                    carAllocation("car-2", "p2", departure, day));
            var rail = train.allocations().get(0);
            rail = new Allocation(rail.allocationId(), rail.operator(), rail.offerId(), rail.mode(), 2, 2, rail.unitType(),
                    rail.departureAt(), rail.arrivalAt(), null, null, List.of("p1", "p2"), null, rail.unitPrice(), null);
            train = new Segment(0, train.origin(), rabat, train.plannedDeparture(), arrival, List.of(rail));
            var itinerary = new Itinerary(2, List.of("p1", "p2"), List.of(train,
                    new Segment(1, rabat, fes, departure, departure.plusHours(1), cars)), List.of());
            var priced = new Price(new Money(76000, Money.Currency.MAD), null, "test", itinerary, Map.of(), List.of(
                    quoteLine(rail, 0, 16000),
                    quoteLine(cars.get(0), 1, 30000),
                    quoteLine(cars.get(1), 1, 30000)));
            var item = create(store, partner, priced);
            drive(store, new SagaWorker(store, partner, Duration.ofSeconds(30)), item.id(), Status.FAILED_COMPENSATED);
            assertEquals(List.of("HOLD-0", "HOLD-1", "HOLD-2", "CANCEL-1", "CANCEL-0"), partner.sent);
            assertEquals(List.of("HOLD:HOLD-1", "HOLD:HOLD-0"), partner.cancelled);
            assertEquals("VOID", ReservationStore.quoteOutcome(store.find(item.id(), false)));
        });
    }

    private static Allocation carAllocation(String offer, String alias, OffsetDateTime departure, LocalDate day) {
        return new Allocation(UUID.randomUUID(), Offer.Operator.CAR, offer, Offer.Mode.VOITURE, 1, 1,
                Offer.UnitType.DAY, departure, departure.plusHours(1), day, day, List.of(alias), alias,
                new Money(30000, Money.Currency.MAD), 1L);
    }

    private static QuoteContracts.Line quoteLine(Allocation allocation, int segment, long total) {
        return new QuoteContracts.Line(UUID.randomUUID(), allocation.allocationId(), segment, total, 0, 0, total, List.of());
    }

    @Test
    void unresolvedConfirmationDoesNotBecomeReleasedWithTimeButLateBookingIsCancelled() throws Exception {
        database((store,db)-> {
            var partner = new Partner();
            partner.unknownConfirm = true;
            var item = create(store,partner);
            var worker = new SagaWorker(store,partner,Duration.ofSeconds(30));
            drive(store,worker,item.id(),Status.NEEDS_RECONCILIATION);
            var first = store.lines(item.id()).get(0);
            var confirm = SagaWorker.step(store.steps(item.id()),first.id(),Action.CONFIRM);
            assertEquals(List.of("HOLD:HOLD-1"),partner.cancelled);
            db.update("UPDATE saga_step SET updated_at=now()-interval '30 minutes' WHERE operation_id=?",confirm.id());
            tick(store,worker,item.id());
            assertEquals(Status.NEEDS_RECONCILIATION,store.find(item.id(),false).status());
            partner.journal.put(confirm.id(),Result.success("BOOK-0",null));
            db.update("UPDATE saga_step SET updated_at=now()-interval '1 minute' WHERE operation_id=?",confirm.id());
            drive(store,worker,item.id(),Status.FAILED_COMPENSATED);
            assertEquals(List.of("HOLD:HOLD-1","BOOKING:BOOK-0"),partner.cancelled);
            assertEquals(1,partner.sent.stream().filter("CONFIRM-0"::equals).count());
        });
    }

    @Test
    void ambiguousHoldRequiresBothAuditedDeadlineAndSuccessfulPurge() throws Exception {
        database((store,db)-> {
            var partner = new Partner();
            partner.unknownSecondHold = true;
            var item = create(store,partner);
            var worker = new SagaWorker(store,partner,Duration.ofSeconds(30));
            drive(store,worker,item.id(),Status.NEEDS_RECONCILIATION);
            var second = store.lines(item.id()).get(1);
            var unknown = SagaWorker.step(store.steps(item.id()),second.id(),Action.HOLD);
            assertEquals(List.of("HOLD:HOLD-0"),partner.cancelled);
            db.update("UPDATE saga_step SET updated_at=now()-interval '15 minutes' WHERE operation_id=?",unknown.id());
            tick(store,worker,item.id());
            assertEquals(0,partner.purges);
            assertEquals(Status.NEEDS_RECONCILIATION,store.find(item.id(),false).status());

            Instant upper = Instant.now().minusSeconds(631);
            store.saveProof(unknown.id(),upper,upper.plusSeconds(630),"Signed operator evidence","support-admin");
            db.update("UPDATE saga_step SET updated_at=now()-interval '1 minute' WHERE operation_id=?",unknown.id());
            tick(store,worker,item.id());
            assertEquals(1,partner.purges);
            assertEquals(Status.NEEDS_RECONCILIATION,store.find(item.id(),false).status());
            partner.purgeAvailable = true;
            db.update("UPDATE saga_step SET updated_at=now()-interval '1 minute' WHERE operation_id=?",unknown.id());
            drive(store,worker,item.id(),Status.FAILED_COMPENSATED);
            assertNotNull(store.evidence(unknown.id()).resolvedAt());
            assertEquals(1,partner.sent.stream().filter("HOLD-1"::equals).count());
        });
    }

    private static Case create(ReservationStore store,Partner partner) {
        return create(store, partner, price());
    }

    private static Case create(ReservationStore store, Partner partner, Price price) {
        UUID id = UUID.randomUUID();
        var item = store.transaction(()->store.create("owner",id,Instant.now().plusSeconds(300),Instant.now().plusSeconds(1000)));
        partner.quote = new Quote(id,item.id(),QuoteContracts.Status.CLAIMED,Instant.now(),item.quoteExpires(),"hash",1,price);
        return item;
    }

    private static void drive(ReservationStore store,SagaWorker w,UUID id,Status target) {
        for(int i=0;i<120;i++) {
            if(store.find(id,false).status()==target) return;
            tick(store,w,id);
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail(interrupted);
            }
        }
        fail("Expected " + target + " but was " + store.find(id,false).status() + " steps " + store.steps(id));
    }

    private static void tick(ReservationStore store,SagaWorker w,UUID id) {
        store.wake(id);
        w.scan();
    }

    private static Price price() {
        var time = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        var rabat = new Place("RAB","Rabat",ZoneOffset.UTC);
        var paris = new Place("PAR","Paris",ZoneOffset.UTC);
        var cas = new Place("CAS","Casablanca",ZoneOffset.UTC);
        var train = new Allocation(UUID.randomUUID(),Offer.Operator.RAIL,"train",Offer.Mode.TRAIN,1,1,Offer.UnitType.SEAT,
                time,time.plusHours(1),null,null,List.of("p1"),null,new Money(8000,Money.Currency.MAD),null);
        var air = new Allocation(UUID.randomUUID(),Offer.Operator.AIR,"air",Offer.Mode.AVION,1,1,Offer.UnitType.SEAT,
                time.plusHours(3),time.plusHours(5),null,null,List.of("p1"),null,new Money(180000,Money.Currency.MAD),null);
        var itinerary = new Itinerary(1,List.of("p1"),List.of(
                new Segment(0,cas,rabat,train.departureAt(),train.arrivalAt(),List.of(train)),
                new Segment(1,rabat,paris,air.departureAt(),air.arrivalAt(),List.of(air))),List.of());
        return new Price(new Money(188000,Money.Currency.MAD),null,"test",itinerary,Map.of(),List.of(
                new QuoteContracts.Line(UUID.randomUUID(),train.allocationId(),0,8000,0,0,8000,List.of()),
                new QuoteContracts.Line(UUID.randomUUID(),air.allocationId(),1,180000,0,0,180000,List.of())));
    }

    private static final class Partner extends RemoteServices {
        Quote quote;
        int rejectHoldAt = -1;
        boolean unknownConfirm;
        boolean unknownSecondHold;
        boolean purgeAvailable;
        boolean loseCancelReceipt;
        int purges;
        final List<String> sent = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final Map<UUID,Result> journal = new HashMap<>();
        Partner() { super("","","","","",""); }
        @Override Quote claim(Case item) { return quote; }
        @Override Result validate(Case item,ReservationModel.Line line) { return Result.success(null,null); }
        @Override Result lookup(ReservationModel.Line line,Step step) {
            return journal.getOrDefault(step.id(),Result.state(State.NOT_SENT,"JOURNAL_ABSENT"));
        }
        @Override boolean purge(Case item,ReservationModel.Line line) { purges++; return purgeAvailable; }
        @Override Result send(Case item,ReservationModel.Line line,Step step) {
            sent.add(step.action()+"-"+line.position());
            Result value = switch(step.action()) {
                case HOLD -> line.position()==rejectHoldAt ? Result.state(State.REJECTED,"NOT_AVAILABLE")
                        : line.position()==1 && unknownSecondHold ? Result.state(State.UNKNOWN,"LOST")
                        : Result.success("HOLD-"+line.position(),Instant.now().plusSeconds(600));
                case CONFIRM -> line.position()==0 && unknownConfirm ? Result.state(State.UNKNOWN,"LOST")
                        : Result.success("BOOK-"+line.position(),null);
                case CANCEL -> {
                    cancelled.add(step.reference());
                    yield Result.success(null,null);
                }
                default -> throw new AssertionError(step.action());
            };
            journal.put(step.id(),value);
            return step.action()==Action.CANCEL && loseCancelReceipt ? Result.state(State.UNKNOWN,"LOST_CANCEL_REPLY") : value;
        }
    }

    private static void database(DatabaseTest work) throws Exception {
        String url=System.getenv("LOT0_POSTGRES_URL"), user=System.getenv("LOT0_POSTGRES_USER"), password=System.getenv("LOT0_POSTGRES_PASSWORD");
        String schema="saga_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password); var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
                Flyway.configure().dataSource(source).load().migrate();
                var db=new JdbcTemplate(source);
                work.run(new ReservationStore(db,new DataSourceTransactionManager(source),JsonMapper.builder().build()),db);
            } finally {
                sql.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }

    @FunctionalInterface
    private interface DatabaseTest { void run(ReservationStore store,JdbcTemplate db); }
}
