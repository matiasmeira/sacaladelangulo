package com.matiasmeira.sacaladelangulo.core.idempotencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SolicitudIdempotenteRepository extends JpaRepository<SolicitudIdempotente, Long> {

    Optional<SolicitudIdempotente> findByClaveAndUsuarioEmail(String clave, String usuarioEmail);

    long deleteByFechaCreacionBefore(LocalDateTime limite);
}
