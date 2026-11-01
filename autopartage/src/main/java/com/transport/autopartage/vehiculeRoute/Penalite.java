package com.transport.autopartage.vehiculeRoute;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Builder
@AllArgsConstructor
@Table(name = "penalites")
@Getter
@Setter
@NoArgsConstructor
class Penalite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private double frais;

    @Column(nullable = false)
    private String type;
}
