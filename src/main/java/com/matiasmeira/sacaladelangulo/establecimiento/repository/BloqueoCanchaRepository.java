package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BloqueoCanchaRepository extends JpaRepository<BloqueoCancha, Long> {

    @Query("SELECT b FROM BloqueoCancha b WHERE b.cancha.id = :canchaId AND " +
           "(b.fechaInicio < :fin AND b.fechaFin > :inicio)")
    List<BloqueoCancha> findOverlappingBloqueos(@Param("canchaId") Long canchaId,
                                                @Param("inicio") LocalDateTime inicio,
                                                @Param("fin") LocalDateTime fin);

    List<BloqueoCancha> findByCanchaIdOrderByFechaInicioAsc(Long canchaId);

    @Query("SELECT b FROM BloqueoCancha b WHERE b.cancha.establecimiento.id = :establecimientoId AND " +
           "(b.fechaInicio < :fin AND b.fechaFin > :inicio)")
    List<BloqueoCancha> findByEstablecimientoAndRango(@Param("establecimientoId") Long establecimientoId,
                                                       @Param("inicio") LocalDateTime inicio,
                                                       @Param("fin") LocalDateTime fin);
}
