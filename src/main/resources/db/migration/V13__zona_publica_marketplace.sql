-- =============================================================================
-- V13 — Zona pública del marketplace: slug, servicios y fotos de establecimiento
--
-- Prepara el modelo para el namespace público /api/v1/publico/**: cada complejo
-- necesita una URL estable (slug) y algo de contenido para mostrar sin depender
-- de campos internos (dueño, etc.). fotos/servicios se cargan a mano por ahora
-- (sin integración con ImageKit todavía).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- SLUG
-- Se agrega nullable primero para poder backfillear los complejos ya existentes
-- antes de exigir NOT NULL + UNIQUE: si se agregaran ambas restricciones de
-- entrada, el ALTER TABLE fallaría contra cualquier base con datos (todas las
-- filas existentes tendrían slug NULL).
-- -----------------------------------------------------------------------------
ALTER TABLE establecimientos ADD COLUMN slug VARCHAR(255);

-- Backfill: un slug base por nombre (minúsculas, sin acentos, separado por
-- guiones) y, si colisiona con uno ya asignado en esta misma corrida, un
-- sufijo numérico incremental (-2, -3, ...) hasta encontrar uno libre. Recorre
-- los establecimientos en orden de id para que el resultado sea determinístico.
DO $$
DECLARE
    fila RECORD;
    base VARCHAR(255);
    candidato VARCHAR(255);
    sufijo INT;
BEGIN
    FOR fila IN SELECT id, nombre FROM establecimientos ORDER BY id LOOP
        base := lower(translate(fila.nombre,
            'ÁÉÍÓÚÑÜáéíóúñü',
            'AEIOUNUaeiounu'));
        base := regexp_replace(base, '[^a-z0-9]+', '-', 'g');
        base := regexp_replace(base, '^-+|-+$', '', 'g');
        IF base IS NULL OR base = '' THEN
            base := 'complejo';
        END IF;

        candidato := base;
        sufijo := 1;
        WHILE EXISTS (SELECT 1 FROM establecimientos WHERE slug = candidato) LOOP
            sufijo := sufijo + 1;
            candidato := base || '-' || sufijo;
        END LOOP;

        UPDATE establecimientos SET slug = candidato WHERE id = fila.id;
    END LOOP;
END $$;

ALTER TABLE establecimientos ALTER COLUMN slug SET NOT NULL;
ALTER TABLE establecimientos ADD CONSTRAINT uk_establecimientos_slug UNIQUE (slug);

-- -----------------------------------------------------------------------------
-- SERVICIOS (@ElementCollection de Establecimiento.servicios)
-- Mismo patrón que cancha_deportes (V1): tabla de colección simple, sin PK
-- propia, con índice sobre la FK porque Postgres no la indexa sola (ver V9/V11).
-- -----------------------------------------------------------------------------
CREATE TABLE establecimiento_servicios (
    establecimiento_id  BIGINT NOT NULL,
    servicio             VARCHAR(255) NOT NULL,
    CONSTRAINT fk_establecimiento_servicios_establecimiento
        FOREIGN KEY (establecimiento_id) REFERENCES establecimientos (id)
);

CREATE INDEX idx_establecimiento_servicios_establecimiento
    ON establecimiento_servicios (establecimiento_id);

-- -----------------------------------------------------------------------------
-- FOTOS (@ElementCollection ordenada de Establecimiento.fotos)
-- La columna "orden" persiste el índice de la lista (@OrderColumn): sin ella,
-- Hibernate no puede garantizar cuál es la "fotoPrincipal" (la primera) al
-- releer la colección. PK compuesta (establecimiento_id, orden): ya identifica
-- cada fila sin necesitar una columna id propia.
-- -----------------------------------------------------------------------------
CREATE TABLE establecimiento_fotos (
    establecimiento_id  BIGINT NOT NULL,
    orden                INTEGER NOT NULL,
    foto_url             VARCHAR(1000) NOT NULL,
    PRIMARY KEY (establecimiento_id, orden),
    CONSTRAINT fk_establecimiento_fotos_establecimiento
        FOREIGN KEY (establecimiento_id) REFERENCES establecimientos (id)
);
