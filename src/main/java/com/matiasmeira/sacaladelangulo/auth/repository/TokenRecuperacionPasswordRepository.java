package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.TokenRecuperacionPassword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio JPA para la entidad TokenRecuperacionPassword.
 */
@Repository
public interface TokenRecuperacionPasswordRepository extends JpaRepository<TokenRecuperacionPassword, Long> {

    Optional<TokenRecuperacionPassword> findByTokenHash(String tokenHash);

    Optional<TokenRecuperacionPassword> findByEmail(String email);

    void deleteByEmail(String email);
}
