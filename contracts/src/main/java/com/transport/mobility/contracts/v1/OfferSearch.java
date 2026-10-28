package com.transport.mobility.contracts.v1;

import java.time.LocalDate;

public record OfferSearch(String origin, String destination, LocalDate date, LocalDate dateFin,
                          Integer capacity, String category) {}
