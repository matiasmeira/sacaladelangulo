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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Deportes para los que está habilitada esta cancha. Una misma cancha puede
     * soportar más de uno (ej. fútbol y hockey) y aparece en la búsqueda de ambos.
     */
    @ElementCollection
    @CollectionTable(name = "cancha_deportes", joinColumns = @JoinColumn(name = "cancha_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "deporte", nullable = false)
    @Builder.Default
    private Set<Deporte> deportes = new HashSet<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
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

    /**
     * Set, no List: el finder que la trae (CanchaRepository.findByEstablecimientoIdAndIsActiveTrue)
     * usa un @EntityGraph que fetch-joinea esta colección junto con "deportes" en la misma
     * consulta. Hibernate 6 arma un único JOIN de ambas colecciones, así que cada fila del
     * resultado es un par (cancha física, deporte): con un bag (List) esas filas quedaban
     * todas en la lista tal cual, duplicando cada física una vez por cada deporte de la
     * cancha lógica (3 físicas × 2 deportes = 6, con cada física repetida). Un Set se
     * reconstruye deduplicando esas filas, igual que ya hacía "deportes" en esa misma query.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "cancha_composicion",
            joinColumns = @JoinColumn(name = "cancha_logica_id"),
            inverseJoinColumns = @JoinColumn(name = "cancha_fisica_id")
    )
    @Builder.Default
    private Set<Cancha> canchasFisicas = new LinkedHashSet<>();

    @OneToMany(mappedBy = "cancha", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Tarifa> tarifas = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "cancha_duraciones", joinColumns = @JoinColumn(name = "cancha_id"))
    @Column(name = "duracion_minutos")
    @Builder.Default
    private List<Integer> duracionesPermitidas = new ArrayList<>();

    /**
     * Precio exacto (no proporcional) para una duración puntual, en minutos, ej. {120: 30000}.
     * Si una duración reservada no tiene entrada acá, se sigue calculando de forma
     * proporcional sobre precioBase (ver PrecioReservaCalculator) — permite migrar cancha
     * por cancha, y duración por duración, sin romper la configuración existente.
     */
    @ElementCollection
    @CollectionTable(name = "cancha_precios_duracion", joinColumns = @JoinColumn(name = "cancha_id"))
    @MapKeyColumn(name = "duracion_minutos")
    @Column(name = "precio")
    @Builder.Default
    private Map<Integer, BigDecimal> preciosPorDuracion = new HashMap<>();

    @Column(name = "permite_inicio_media_hora")
    @Builder.Default
    private Boolean permiteInicioMediaHora = true;
}
