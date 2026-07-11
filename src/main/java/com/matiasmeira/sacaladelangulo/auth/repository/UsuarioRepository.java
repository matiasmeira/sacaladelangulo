package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para la entidad Usuario.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<Usuario> findByEstablecimientoIdAndNombreAndRol(Long establecimientoId, String nombre, Role rol);

    boolean existsByEstablecimientoIdAndNombreAndRol(Long establecimientoId, String nombre, Role rol);

    List<Usuario> findByEstablecimientoIdAndRol(Long establecimientoId, Role rol);

    List<Usuario> findByEstablecimientoIdAndRolAndIsActiveTrue(Long establecimientoId, Role rol);
}
