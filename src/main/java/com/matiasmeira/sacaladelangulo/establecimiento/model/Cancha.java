package com.matiasmeira.sacaladelangulo.establecimiento.model;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad que representa una cancha deportiva.
 */
@Getter
@Setter
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

    @Column(name = "canchas_necesarias")
    private Integer canchasNecesarias;

    @Column(name = "precio_base", nullable = false)
    private BigDecimal precioBase;

    @Column(name = "monto_sena", nullable = false)
    private BigDecimal montoSena;

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

    @OneToMany(mappedBy = "cancha", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Tarifa> tarifas = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "cancha_duraciones", joinColumns = @JoinColumn(name = "cancha_id"))
    @Column(name = "duracion_minutos")
    @Builder.Default
    private List<Integer> duracionesPermitidas = new ArrayList<>();

    @Column(name = "permite_inicio_media_hora")
    @Builder.Default
    private Boolean permiteInicioMediaHora = true;
}
