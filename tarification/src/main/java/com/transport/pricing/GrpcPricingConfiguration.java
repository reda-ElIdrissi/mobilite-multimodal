package com.transport.pricing;

import com.transport.mobility.pricing.grpc.PreviewCodec;
import com.transport.mobility.pricing.grpc.PreviewPricingGrpc;
import com.transport.mobility.pricing.grpc.PricingPreview.PreviewRequest;
import com.transport.mobility.pricing.grpc.PricingPreview.PreviewResponse;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.DateTimeException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Configuration(proxyBeanMethods = false)
@Profile("grpc-preview")
class GrpcPricingConfiguration {
    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(GrpcPricingConfiguration.class);

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    Server pricingPreviewServer(PricingService pricing, JwtDecoder decoder,
            @Value("${mobility.pricing.grpc-address:127.0.0.1}") String address,
            @Value("${mobility.pricing.grpc-port:9090}") int port) {
        return NettyServerBuilder.forAddress(new InetSocketAddress(address, port))
                .maxInboundMessageSize(512 * 1024)
                .intercept(new PreviewAuthorization(decoder))
                .addService(new PreviewPricingGrpc.PreviewPricingImplBase() {
                    @Override
                    public void preview(PreviewRequest request, StreamObserver<PreviewResponse> reply) {
                        PreviewResponse result;
                        try {
                            result = PreviewCodec.encodePrices(pricing.preview(PreviewCodec.decode(request)));
                        } catch (IllegalArgumentException | DateTimeException | ArithmeticException invalid) {
                            reply.onError(Status.INVALID_ARGUMENT.withDescription("Invalid pricing basket").asRuntimeException());
                            return;
                        } catch (RuntimeException unavailable) {
                            LOG.warn("gRPC pricing preview failed ({})", unavailable.getClass().getSimpleName());
                            reply.onError(Status.INTERNAL.withDescription("Pricing preview failed").asRuntimeException());
                            return;
                        }
                        reply.onNext(result);
                        reply.onCompleted();
                    }
                }).build();
    }

    private record PreviewAuthorization(JwtDecoder decoder) implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                ServerCallHandler<Q, R> next) {
            String bearer = headers.get(AUTHORIZATION);
            Status denial = Status.UNAUTHENTICATED;
            if (bearer != null && bearer.regionMatches(true, 0, "Bearer ", 0, 7) && bearer.length() > 7) {
                try {
                    var jwt = decoder.decode(bearer.substring(7));
                    if (new JwtGrantedAuthoritiesConverter().convert(jwt).stream()
                            .anyMatch(authority -> authority.getAuthority().equals("SCOPE_pricing:issue"))) {
                        return next.startCall(call, headers);
                    }
                    denial = Status.PERMISSION_DENIED;
                } catch (JwtException rejected) {
                    // Use the same issuer, audience and signatures as the REST resource server.
                }
            }
            call.close(denial, new Metadata());
            return new ServerCall.Listener<>() {};
        }
    }
}
