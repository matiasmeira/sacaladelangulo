package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaEliminacionUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad AuditoriaEliminacionUsuario. Solo persistencia, sin
 * queries especiales por ahora.
 */
@Repository
public interface AuditoriaEliminacionUsuarioRepository extends JpaRepository<AuditoriaEliminacionUsuario, Long> {
}
