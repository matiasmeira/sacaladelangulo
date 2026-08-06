-- =============================================================================
-- V11 — Índices secundarios: buffet, gastos y movimientos de caja
--
-- Mismo problema de base que V9: Postgres no indexa las columnas FK solo.
-- Estas tablas crecen con el uso diario (una fila por venta / gasto /
-- movimiento), así que el Seq Scan se degrada de forma sostenida.
--
-- Prioridad menor que V9: el volumen por establecimiento es mucho más chico que
-- el de `reservas` y ninguna de estas consultas corre dentro de un lock.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- GASTOS
-- Respalda GastoRepository.buscar (listado paginado del panel, ordenado por
-- fecha DESC) y las agregaciones de reporte sumGastoPorCategoria /
-- findFechaYMontoParaSerieTemporal, todas filtradas por establecimiento + rango
-- de fecha.
--
-- Índice PARCIAL sobre is_active: los gastos anulados (baja lógica, ver V7)
-- quedan en la tabla pero se excluyen de TODAS las consultas
-- (`AND g.isActive = true`). No tiene sentido indexarlos.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_gastos_establecimiento_fecha
    ON gastos (establecimiento_id, fecha DESC)
    WHERE is_active = true;

-- -----------------------------------------------------------------------------
-- VENTAS DE BUFFET
-- Listados y reportes de facturación por establecimiento y período.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_ventas_buffet_establecimiento_fecha
    ON ventas_buffet (establecimiento_id, fecha_hora DESC);

-- Ventas imputadas a una reserva (consumo cargado al turno). `reserva_id` es
-- NULLable — las ventas de mostrador sueltas no entran al índice.
CREATE INDEX idx_ventas_buffet_reserva
    ON ventas_buffet (reserva_id);

-- Detalle de cada venta: se recorre siempre por venta_id. Sin índice, abrir el
-- detalle de UNA venta escanea todos los detalles de todas las ventas.
CREATE INDEX idx_detalles_venta_venta
    ON detalles_venta (venta_id);

-- -----------------------------------------------------------------------------
-- MOVIMIENTOS DE CAJA
-- `idx_movimiento_caja_turno` YA EXISTE (creado en V6) y cubre
-- findByTurnoCajaIdOrderByFechaHoraAsc. No se toca.
--
-- Lo que NO estaba cubierto es
-- findTopByOrigenAndReferenciaIdOrderByFechaHoraDesc, que busca el último
-- movimiento de una venta/gasto/reserva puntual para decidir si todavía vive en
-- el turno abierto antes de compensarlo (TurnoCajaService). Se llama en cada
-- anulación/edición que impacta la caja.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_movimiento_caja_origen_referencia
    ON movimiento_caja (origen, referencia_id, fecha_hora DESC);

-- -----------------------------------------------------------------------------
-- PRODUCTOS DE BUFFET
-- El lock pesimista de stock los busca por id (ya cubierto por la PK). Este
-- índice es para el listado por establecimiento (grilla de venta).
-- -----------------------------------------------------------------------------
CREATE INDEX idx_productos_buffet_establecimiento
    ON productos_buffet (establecimiento_id);

-- =============================================================================
-- NO se agregan índices para: usuarios (uk_usuarios_email ya cubre el login),
-- turno_caja (V6 ya creó idx_turno_caja_establecimiento y el parcial
-- uk_turno_caja_abierto_por_establecimiento), tokens de verificación/
-- recuperación (el UNIQUE sobre el token ya es el índice de búsqueda), ni
-- solicitudes_idempotentes (V1 ya indexó fecha_creacion para la purga).
-- Todos esos caminos ya estaban respaldados.
-- =============================================================================
