package com.transport.autopartage.vehiculeRoute.dto;
import java.time.LocalDate;

public record Trajet(
    long id,

    String depart,

    String arrive,

    String categorie,

    LocalDate debut
) {}
