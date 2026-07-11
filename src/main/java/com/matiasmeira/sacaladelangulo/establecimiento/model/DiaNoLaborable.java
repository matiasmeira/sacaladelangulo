package com.matiasmeira.sacaladelangulo.establecimiento.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Excepción puntual al horario de atención semanal recurrente: una fecha específica en
 * la que el establecimiento no abre (feriado, cierre especial), independientemente de lo
 * que indique el HorarioAtencion del día de la semana que le corresponda.
 */
@Entity
@Table(name = "dias_no_laborables")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiaNoLaborable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(length = 255)
    private String motivo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establecimiento_id", nullable = false)
    private Establecimiento establecimiento;
}
