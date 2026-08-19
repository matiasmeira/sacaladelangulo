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

    /**
     * Informativo: PUEDE quedar negativo. Una venta real ya cobrada en el
     * mostrador no se bloquea porque el inventario del sistema esté
     * desactualizado; el número en negativo es la señal de que hay que reponer
     * o corregir la carga (ver V16).
     */
    @Column(nullable = false)
    private Integer stock;

    /** Con stock igual o menor a este número, el producto se marca "stock bajo". */
    @Column(name = "umbral_alerta", nullable = false)
    @Builder.Default
    private Integer umbralAlerta = 5;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;
}
