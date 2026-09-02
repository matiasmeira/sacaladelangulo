package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad AuditoriaDegradacionPlan. Solo persistencia, sin queries
 * especiales por ahora.
 */
@Repository
public interface AuditoriaDegradacionPlanRepository extends JpaRepository<AuditoriaDegradacionPlan, Long> {
}
