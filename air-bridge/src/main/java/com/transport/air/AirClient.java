package com.transport.air;

import com.transport.air.ws.Flight;
import com.transport.mobility.observability.Telemetry;
import com.transport.air.ws.SearchFlightsRequest;
import com.transport.air.ws.SearchFlightsResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

final class AirClient {
    private final WebServiceTemplate client;
    private final String endpoint;

    AirClient(String endpoint) throws Exception {
        this.endpoint = endpoint;
        var binding = new Jaxb2Marshaller();
        binding.setContextPath(SearchFlightsRequest.class.getPackageName());
        binding.afterPropertiesSet();

        var sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(Duration.ofMillis(1500));
        sender.setReadTimeout(Duration.ofSeconds(4));
        client = new WebServiceTemplate(binding);
        client.setMessageSender(sender);
    }

    List<Flight> search(String origin, String destination, LocalDate date) {
        var request = new SearchFlightsRequest();
        request.setOrigin(origin);
        request.setDestination(destination);
        request.setDate(date.toString());
        return exchange(request, SearchFlightsResponse.class).getFlight();
    }

    <T> T exchange(Object request, Class<T> type) {
        var operation = request.getClass().getSimpleName().replace("Request", "");
        var response = Telemetry.legacy("AIR", "soap", operation, () -> client.marshalSendAndReceive(endpoint, request));
        if (response == null) {
            throw new IllegalStateException("Empty SOAP response");
        }
        return type.cast(response);
    }
}
