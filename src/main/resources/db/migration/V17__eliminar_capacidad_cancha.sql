-- =============================================================================
-- CANCHA — capacidad (Integer) deja de existir. La modalidad/tamaño que antes
-- se inferia de capacidad (ej. "cancha de 10" = fútbol 5) ahora la codifica
-- directamente el valor granular de Deporte (FUTBOL_5, FUTBOL_7, ...). Ver
-- Deporte.java: pasó de 6 valores genéricos a 29 específicos.
--
-- Entorno de desarrollo sin datos reales que preservar: no hay backfill, solo
-- limpieza de los dos valores del enum viejo que ya no existen.
-- =============================================================================

-- FUTBOL y BASQUET (genéricos) ya no son valores válidos de Deporte.
-- PADEL, TENIS, HOCKEY y VOLEY siguen existiendo tal cual y no se tocan.
DELETE FROM cancha_deportes WHERE deporte IN ('FUTBOL', 'BASQUET');

ALTER TABLE canchas DROP COLUMN capacidad;
