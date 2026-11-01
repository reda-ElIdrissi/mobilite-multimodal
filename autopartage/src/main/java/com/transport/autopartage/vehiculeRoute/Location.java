package com.transport.autopartage.vehiculeRoute;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Builder
@AllArgsConstructor
@Table(name = "locations")
@Getter
@Setter
@NoArgsConstructor
class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "owner_subject", nullable = false)
    private String ownerId;

    @Column(name = "renter_subject", nullable = false)
    private String renterId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depart_id", nullable = false)
    private Ville depart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_id", nullable = false)
    private Ville destination;

    @Column(nullable = false)
    private LocalDate dateDebut;

    @Column(nullable = false)
    private LocalDate dateFin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    private Instant expiresAt;
    private UUID holdOperationId;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private Long quotedDailyRateMinor;
    private String driverAlias;
    private UUID reservationLineId;
    private Instant handoverAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handover_city_id")
    private Ville handoverCity;

    private Integer ratingOwner;

    private Integer ratingRenter;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDate joinedAt;

}
