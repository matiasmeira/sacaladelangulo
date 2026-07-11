package com.matiasmeira.sacaladelangulo.buffet.repository;

import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductoBuffetRepository extends JpaRepository<ProductoBuffet, Long> {

    List<ProductoBuffet> findByEstablecimientoId(Long establecimientoId);
}
