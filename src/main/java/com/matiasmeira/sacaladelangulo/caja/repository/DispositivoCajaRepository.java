package com.matiasmeira.sacaladelangulo.caja.repository;

import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DispositivoCajaRepository extends JpaRepository<DispositivoCaja, Long> {

    Optional<DispositivoCaja> findByTokenHash(String tokenHash);

    List<DispositivoCaja> findByEstablecimientoIdAndActivoTrue(Long establecimientoId);

    Optional<DispositivoCaja> findByIdAndEstablecimientoId(Long id, Long establecimientoId);
}
