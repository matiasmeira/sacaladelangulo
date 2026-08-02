package com.matiasmeira.sacaladelangulo.caja.repository;

import com.matiasmeira.sacaladelangulo.caja.model.CodigoEmparejamientoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CodigoEmparejamientoCajaRepository extends JpaRepository<CodigoEmparejamientoCaja, Long> {

    Optional<CodigoEmparejamientoCaja> findByCodigoHash(String codigoHash);
}
