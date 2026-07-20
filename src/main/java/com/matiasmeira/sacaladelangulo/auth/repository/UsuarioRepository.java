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

    /**
     * IgnoreCase para que "Juan" y "juan" (o con espacios extra, ya trimeados por el
     * llamador) se traten como el mismo empleado tanto al loguear como al validar
     * unicidad de nombre (ver B4 en la auditoría).
     */
    Optional<Usuario> findByEstablecimientoIdAndNombreIgnoreCaseAndRol(Long establecimientoId, String nombre, Role rol);

    /**
     * AndIsActiveTrue para que el nombre de un empleado desactivado quede libre y se
     * pueda reutilizar al dar de alta a otro empleado (ver B18 en la auditoría): sin
     * este filtro, un nombre queda bloqueado para siempre apenas se desactiva a quien
     * lo tenía.
     */
    boolean existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(Long establecimientoId, String nombre, Role rol);

    List<Usuario> findByEstablecimientoIdAndRol(Long establecimientoId, Role rol);

    List<Usuario> findByEstablecimientoIdAndRolAndIsActiveTrue(Long establecimientoId, Role rol);
}
