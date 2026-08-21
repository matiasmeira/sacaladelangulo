-- V18 — fileId de ImageKit en las fotos de establecimiento
--
-- establecimiento_fotos pasa de guardar solo la URL a guardar también el fileId que
-- devuelve ImageKit al subir: es la clave con la que después se borra el archivo y con la
-- que los endpoints de gestión identifican cada foto.
--
-- NULLABLE a propósito: las filas cargadas a mano antes de esta integración no tienen
-- fileId. Se siguen mostrando en el marketplace, pero no se pueden borrar ni reordenar
-- por API.
ALTER TABLE establecimiento_fotos ADD COLUMN file_id VARCHAR(255);

-- Único PARCIAL: el fileId es la clave de borrado y de reordenamiento, así que dos fotos
-- con el mismo fileId en un establecimiento serían ambiguas. Parcial (WHERE file_id IS
-- NOT NULL) porque las fotos legacy tienen NULL y varias NULL tienen que poder convivir
-- en el mismo establecimiento — un UNIQUE común lo permitiría en Postgres, pero dejarlo
-- explícito documenta la intención y evita depender de esa semántica.
CREATE UNIQUE INDEX uq_establecimiento_fotos_file_id
    ON establecimiento_fotos (establecimiento_id, file_id)
    WHERE file_id IS NOT NULL;
