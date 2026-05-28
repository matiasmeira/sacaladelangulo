package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.CodigoVerificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio JPA para la entidad CodigoVerificacion.
 */
@Repository
public interface CodigoVerificacionRepository extends JpaRepository<CodigoVerificacion, Long> {

    Optional<CodigoVerificacion> findByEmail(String email);

    void deleteByEmail(String email);
}
