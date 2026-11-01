package com.transport.autopartage.vehiculeRoute.dto;

import com.transport.autopartage.vehiculeRoute.Status;
import java.time.LocalDate;

public record LocationHistoryItem(long id, String depart, String destination, LocalDate debut,
                                  LocalDate fin, Status status, Integer ratingOwner, Integer ratingRenter) {}
