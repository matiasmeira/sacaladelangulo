package com.matiasmeira.sacaladelangulo.establecimiento.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Entidad que representa una cancha deportiva.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "canchas")
public class Cancha {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String deporte;

    @Column(nullable = false)
    private Integer capacidad;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "cancha_composicion",
            joinColumns = @JoinColumn(name = "cancha_logica_id"),
            inverseJoinColumns = @JoinColumn(name = "cancha_fisica_id")
    )
    @Builder.Default
    private List<Cancha> canchasFisicas = new ArrayList<>();
}
