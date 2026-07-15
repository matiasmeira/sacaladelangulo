package com.matiasmeira.sacaladelangulo.core.idempotencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SolicitudIdempotenteRepository extends JpaRepository<SolicitudIdempotente, Long> {

    Optional<SolicitudIdempotente> findByClaveAndUsuarioEmail(String clave, String usuarioEmail);

    /**
     * DELETE en lote directo en la base, a diferencia de un derived deleteBy... (que Spring
     * Data resuelve como un SELECT de todas las filas + un remove() entidad por entidad, para
     * poder disparar callbacks de ciclo de vida JPA que acá no hacen falta).
     */
    @Modifying
    @Query("delete from SolicitudIdempotente s where s.fechaCreacion < :limite")
    int borrarExpiradas(@Param("limite") LocalDateTime limite);
}
