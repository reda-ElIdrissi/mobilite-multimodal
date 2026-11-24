package com.transport.bff;

import com.transport.mobility.contracts.v1.QuoteContracts.PreviewBatch;
import com.transport.mobility.contracts.v1.QuoteContracts.Price;
import com.transport.mobility.pricing.grpc.PreviewCodec;
import com.transport.mobility.pricing.grpc.PreviewPricingGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
@Profile("grpc-preview")
class GrpcPreviewClient {
    private final ManagedChannel channel;
    private final PreviewPricingGrpc.PreviewPricingBlockingStub stub;

    GrpcPreviewClient(@Value("${mobility.pricing.grpc-target:127.0.0.1:9090}") String target) {
        this.channel = NettyChannelBuilder.forTarget(target).usePlaintext().disableRetry().build();
        this.stub = PreviewPricingGrpc.newBlockingStub(channel);
    }

    List<Price> preview(PreviewBatch batch, String token) {
        var headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        try {
            var result = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(5, TimeUnit.SECONDS).preview(PreviewCodec.encode(batch));
            return PreviewCodec.decodePrices(batch, result);
        } catch (StatusRuntimeException | IllegalArgumentException unavailable) {
            throw new RestClientException("gRPC pricing preview unavailable", unavailable);
        }
    }

    @PreDestroy
    void shutdown() {
        channel.shutdownNow();
    }
}
