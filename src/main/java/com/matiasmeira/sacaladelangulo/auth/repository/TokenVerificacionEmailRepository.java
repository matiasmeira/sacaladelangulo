package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.TokenVerificacionEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio JPA para la entidad TokenVerificacionEmail.
 */
@Repository
public interface TokenVerificacionEmailRepository extends JpaRepository<TokenVerificacionEmail, Long> {

    Optional<TokenVerificacionEmail> findByTokenHash(String tokenHash);

    Optional<TokenVerificacionEmail> findByEmail(String email);

    void deleteByEmail(String email);
}
