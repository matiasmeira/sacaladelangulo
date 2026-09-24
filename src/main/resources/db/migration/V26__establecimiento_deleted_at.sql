-- =============================================================================
-- V26 — Columna deleted_at en establecimientos (baja lógica del complejo)
--
-- Mismo patrón que V14 (usuarios.deleted_at): columna simple, nullable, sin
-- default, sin índice. isActive=false no alcanza como discriminador de
-- "eliminado" -- ya se usa para "deshabilitado por el dueño" (PATCH /estado),
-- que es reversible y no libera el cupo del límite de 3 establecimientos.
-- deletedAt es el hecho irreversible: sin restauración, slug liberado.
-- =============================================================================

ALTER TABLE establecimientos ADD COLUMN deleted_at TIMESTAMP NULL;
