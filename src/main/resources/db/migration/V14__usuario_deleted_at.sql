-- =============================================================================
-- V14 — Columna deleted_at en usuarios (baja de cuenta)
--
-- Discriminador real de una cuenta eliminada. isActive=false por sí solo no
-- alcanza: hoy ya se usa para "onboarding no completado" (PLAYER) y para
-- desactivación de EMPLOYEE (ver EmpleadoService.desactivarEmpleado), así que no
-- puede reutilizarse como flag de "eliminado". Ver spec de eliminación de cuenta
-- (docs/superpowers/specs/2026-08-15-eliminacion-cuenta-design.md).
-- =============================================================================

ALTER TABLE usuarios ADD COLUMN deleted_at TIMESTAMP NULL;
