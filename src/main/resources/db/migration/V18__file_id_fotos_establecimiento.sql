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

-- Unicidad de (establecimiento_id, file_id): el fileId es la clave de borrado y de
-- reordenamiento, así que dos fotos con el mismo fileId en un establecimiento serían
-- ambiguas.
--
-- DEFERRABLE INITIALLY DEFERRED, y no un CREATE UNIQUE INDEX: Postgres chequea un índice
-- único no diferible SENTENCIA POR SENTENCIA, y el reordenamiento pasa por un estado
-- intermedio con dos filas repetidas. Establecimiento.fotos es una @ElementCollection con
-- @OrderColumn: al reordenar, Hibernate no borra la lista entera y la reinserta, resuelve
-- la permutación con un UPDATE por índice
--   UPDATE establecimiento_fotos SET file_id=?, foto_url=? WHERE establecimiento_id=? AND orden=?
-- Dando vuelta [a, b] a [b, a], después del primer UPDATE las filas orden=0 y orden=1
-- tienen las dos file_id='b'; recién el segundo UPDATE deshace la repetición. Con chequeo
-- por sentencia eso es una violación inmediata y PUT /establecimientos/{id}/fotos/orden
-- falla siempre. Diferido, el chequeo corre en el COMMIT: el estado intermedio es legal y
-- un fileId repetido de verdad sigue abortando la transacción. Sólo un CONSTRAINT se puede
-- diferir; un índice creado con CREATE UNIQUE INDEX no.
--
-- Sin WHERE file_id IS NOT NULL (que un índice parcial sí permitiría, pero un constraint
-- no): un UNIQUE de Postgres ya considera distintos los NULL entre sí, así que las fotos
-- legacy sin fileId conviven varias en el mismo establecimiento igual que antes.
ALTER TABLE establecimiento_fotos
    ADD CONSTRAINT uq_establecimiento_fotos_file_id
    UNIQUE (establecimiento_id, file_id)
    DEFERRABLE INITIALLY DEFERRED;
