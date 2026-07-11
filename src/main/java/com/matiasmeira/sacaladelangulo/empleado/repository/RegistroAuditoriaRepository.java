package com.matiasmeira.sacaladelangulo.empleado.repository;

import com.matiasmeira.sacaladelangulo.empleado.model.RegistroAuditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistroAuditoriaRepository extends JpaRepository<RegistroAuditoria, Long> {

    Page<RegistroAuditoria> findByEstablecimientoIdOrderByFechaHoraDesc(Long establecimientoId, Pageable pageable);
}
