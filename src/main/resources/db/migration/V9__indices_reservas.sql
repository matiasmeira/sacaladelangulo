-- =============================================================================
-- V9 — Índices del camino caliente: reservas + canchas
--
-- Estado previo: la tabla `reservas` NO tenía ningún índice más allá de la PK.
-- Postgres NO crea índices automáticamente para las columnas FK, así que
-- `cancha_id`, `jugador_id` y las columnas de rango temporal estaban sin indexar.
-- Toda consulta de disponibilidad, agenda y reporte hacía Seq Scan sobre la tabla
-- que más crece del sistema.
--
-- Agravante que motivó la prioridad: findSuperpuestas() se ejecuta DENTRO del
-- lock pesimista sobre las canchas (SELECT ... FOR UPDATE en
-- ReservaService.bloquearCanchasRelacionadas). Cuanto más tarda ese Seq Scan,
-- más tiempo se retiene el lock y más se serializan entre sí las reservas
-- concurrentes del mismo establecimiento.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) El índice de mayor impacto del sistema.
--
-- Respalda:
--   - ReservaRepository.findSuperpuestas            (cada creación de reserva +
--                                                    cada render de la grilla de
--                                                    disponibilidad)
--   - findReservasEnRangoDiario / ...IncluyendoCanceladas  (agenda del panel)
--   - findOverlappingByCanchaId
--   - findCanchaIdsConSolapamiento
--   - el filtro por fecha de todos los reportes, una vez resuelto el join
--
-- Orden de columnas: `cancha_id` primero (igualdad / target del join), luego
-- `fecha_hora_inicio` (rango + orden). Es el orden correcto para un índice
-- compuesto B-tree: igualdad antes que rango.
--
-- Se incluye `fecha_hora_fin` al final para que las consultas de solapamiento
-- (`inicio < :fin AND fin > :inicio`) puedan resolver ambos extremos del rango
-- desde el índice sin ir al heap por cada fila candidata.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_reservas_cancha_rango
    ON reservas (cancha_id, fecha_hora_inicio, fecha_hora_fin);

-- -----------------------------------------------------------------------------
-- 2) Driver del join de findSuperpuestas y de todos los reportes por
--    establecimiento: se entra por `canchas.establecimiento_id` y desde ahí se
--    saltan las reservas. Sin este índice, cada consulta "del establecimiento"
--    escanea la tabla completa de canchas antes de tocar reservas.
--
--    También respalda CanchaRepository.findByEstablecimientoIdAndIsActiveTrue,
--    que se llama en TODA creación de reserva (para armar el pool) y en cada
--    grilla de disponibilidad.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_canchas_establecimiento
    ON canchas (establecimiento_id);

-- -----------------------------------------------------------------------------
-- 3) "Mis reservas" del jugador: ReservaRepository.findByJugadorId /
--    findByJugadorIdAndEstado. Crece con la cantidad de reservas del sistema,
--    no con las del jugador: sin índice, ver las propias 5 reservas obliga a
--    escanear las de todos.
--
--    `jugador_id` es NULLable (reservas de mostrador) — Postgres no las indexa
--    en un B-tree común, así que el índice queda naturalmente más chico.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_reservas_jugador
    ON reservas (jugador_id, fecha_hora_inicio DESC);

-- -----------------------------------------------------------------------------
-- 4) Barrido de pre-reservas vencidas (ReservaExpiracionService →
--    liberarReservasVencidas). Corre en loop programado: sin índice hacía un
--    Seq Scan completo de `reservas` en cada pasada, para siempre, aun cuando
--    no hubiera nada que liberar.
--
--    Índice PARCIAL: solo las filas PENDIENTE_SENA, que son una fracción mínima
--    y transitoria del total. El índice se mantiene chico permanentemente.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_reservas_pendientes_expiracion
    ON reservas (expira_en)
    WHERE estado = 'PENDIENTE_SENA';

-- -----------------------------------------------------------------------------
-- NOTA sobre CREATE INDEX CONCURRENTLY
--
-- Estos CREATE INDEX toman un lock SHARE sobre la tabla (bloquean escrituras
-- mientras se construyen). Con el volumen al momento de aplicar esta migración
-- (pre-launch) es instantáneo y no hace falta nada especial.
--
-- Si en el futuro se agregan índices con tráfico real encima, usar CONCURRENTLY.
-- Ojo: requiere que Flyway NO envuelva la migración en una transacción, lo que
-- se configura por script con `-- flyway:executeInTransaction=false` en la
-- primera línea del archivo.
-- =============================================================================
