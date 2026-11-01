package com.transport.autopartage.vehiculeRoute;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "villes")
class Ville {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    long id() {
        return id;
    }

    @Column(nullable = false, unique = true)
    @Getter
    private String nom;
}
