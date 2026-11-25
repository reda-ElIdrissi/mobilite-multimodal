package com.transport.pricing;

import com.transport.mobility.contracts.v1.Itinerary;
import com.transport.mobility.contracts.v1.Place;
import com.transport.mobility.contracts.v1.QuoteContracts.Basket;
import com.transport.mobility.contracts.v1.QuoteContracts.PreviewBatch;
import com.transport.mobility.contracts.v1.Segment;
import com.transport.mobility.pricing.grpc.PreviewCodec;
import com.transport.mobility.pricing.grpc.PreviewPricingGrpc;
import com.transport.mobility.pricing.grpc.PricingPreview.PreviewRequest;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

final class GrpcPreviewChecks {
    private GrpcPreviewChecks() {}

    static void verify(WebServerApplicationContext context, Basket sample, Basket withoutPromotion) throws Exception {
        var morocco = ZoneId.of("Africa/Casablanca");
        var france = ZoneId.of("Europe/Paris");
        var cas = new Place("CAS", "Casablanca", morocco);
        var rab = new Place("RAB", "Rabat", morocco);
        var paris = new Place("PAR", "Paris", france);
        var versailles = new Place("VERSAILLES", "Versailles", france);
        var allocations = sample.itinerary().segments().get(0).allocations();
        var itinerary = new Itinerary(2, sample.itinerary().participants(), List.of(
                leg(0, cas, rab, "07:30", "08:25", allocations.get(0)),
                leg(1, rab, paris, "12:00", "15:10", allocations.get(1)),
                leg(2, paris, versailles, "17:00", "18:00", allocations.get(2))),
                List.of("Indicative car duration"));
        var batch = new PreviewBatch(List.of(
                new Basket(itinerary, sample.promoCode(), Map.of(allocations.get(2).allocationId(), "Citadine")),
                withoutPromotion));
        var mapper = context.getBean(JsonMapper.class);
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        int port = context.getWebServer().getPort();
        byte[] jsonRequest = mapper.writeValueAsBytes(batch);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/pricing/preview"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer preview-test")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonRequest)).build();

        var channel = NettyChannelBuilder.forAddress("127.0.0.1", context.getBean(Server.class).getPort()).usePlaintext().build();
        try {
            var stub = PreviewPricingGrpc.newBlockingStub(channel);
            var authorized = bearer(stub, "preview-test");
            var wire = PreviewCodec.encode(batch);
            assertEquals(batch, PreviewCodec.decode(wire));
            assertEquals(Status.Code.UNAUTHENTICATED, rejected(stub, wire));
            assertEquals(Status.Code.UNAUTHENTICATED, rejected(bearer(stub, "invalid-test"), wire));
            assertEquals(Status.Code.PERMISSION_DENIED, rejected(bearer(stub, "other-scope-test"), wire));
            assertEquals(Status.Code.INVALID_ARGUMENT, rejected(authorized, PreviewRequest.getDefaultInstance()));

            var rest = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, rest.statusCode());
            var grpc = authorized.withDeadlineAfter(5, TimeUnit.SECONDS).preview(wire);
            assertEquals(mapper.readTree(rest.body()), mapper.readTree(mapper.writeValueAsBytes(PreviewCodec.decodePrices(batch, grpc))));

            for (int i = 0; i < 10; i++) {
                http.send(request, HttpResponse.BodyHandlers.discarding());
                authorized.withDeadlineAfter(5, TimeUnit.SECONDS).preview(wire);
            }
            long[] httpTimes = new long[40];
            long[] grpcTimes = new long[40];
            for (int i = 0; i < httpTimes.length; i++) {
                long start = System.nanoTime();
                var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                httpTimes[i] = System.nanoTime() - start;
                assertEquals(200, response.statusCode());
                start = System.nanoTime();
                authorized.withDeadlineAfter(5, TimeUnit.SECONDS).preview(wire);
                grpcTimes[i] = System.nanoTime() - start;
            }
            System.out.printf(java.util.Locale.ROOT,
                    "LOT10 batch=%d n=%d REST request=%d response=%d p50=%.2fms p95=%.2fms "
                            + "gRPC request=%d response=%d p50=%.2fms p95=%.2fms%n",
                    batch.baskets().size(), httpTimes.length, jsonRequest.length, rest.body().length,
                    percentile(httpTimes, 0.50), percentile(httpTimes, 0.95), wire.getSerializedSize(),
                    grpc.getSerializedSize(), percentile(grpcTimes, 0.50), percentile(grpcTimes, 0.95));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Segment leg(int index, Place origin, Place destination, String departure, String arrival,
            com.transport.mobility.contracts.v1.Allocation allocation) {
        return new Segment(index, origin, destination, OffsetDateTime.parse("2026-11-15T" + departure + ":00+01:00"),
                OffsetDateTime.parse("2026-11-15T" + arrival + ":00+01:00"), List.of(allocation));
    }

    private static Status.Code rejected(PreviewPricingGrpc.PreviewPricingBlockingStub stub, PreviewRequest request) {
        return assertThrows(StatusRuntimeException.class, () -> stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .preview(request)).getStatus().getCode();
    }

    private static PreviewPricingGrpc.PreviewPricingBlockingStub bearer(
            PreviewPricingGrpc.PreviewPricingBlockingStub stub, String token) {
        var headers = new Metadata();
        headers.put(GrpcPricingConfiguration.AUTHORIZATION, "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static double percentile(long[] times, double fraction) {
        Arrays.sort(times);
        return times[(int) Math.ceil(times.length * fraction) - 1] / 1_000_000.0;
    }
}
