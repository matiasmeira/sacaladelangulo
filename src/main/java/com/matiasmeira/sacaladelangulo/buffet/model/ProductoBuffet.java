package com.matiasmeira.sacaladelangulo.buffet.model;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Producto del buffet de un establecimiento (bebidas, snacks, etc.). Inventario
 * simple: sin lotes ni fechas de vencimiento, solo producto y stock actual.
 */
@Entity
@Table(name = "productos_buffet")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductoBuffet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nombre;

    private String descripcion;

    @Column(nullable = false)
    private BigDecimal precio;

    @Column(nullable = false)
    private Integer stock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;
}
