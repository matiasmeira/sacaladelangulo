package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad Cancha.
 */
@Repository
public interface CanchaRepository extends JpaRepository<Cancha, Long> {

    List<Cancha> findByEstablecimientoIdAndIsActiveTrue(Long establecimientoId);
}
