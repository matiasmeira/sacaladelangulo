-- =============================================================================
-- V10 — Backstop de doble-booking a nivel base de datos (EXCLUDE USING gist)
--
-- CONTEXTO: el solapamiento ya se previene en la aplicación con un lock
-- pesimista (SELECT ... FOR UPDATE sobre las canchas del pool, ordenado por id
-- para evitar deadlocks; ver ReservaService.bloquearCanchasRelacionadas +
-- CanchaRepository.lockPorIds). Ese mecanismo está bien implementado y los 4
-- caminos de escritura lo usan. Esto NO lo reemplaza: es la red por si un camino
-- futuro se olvida de tomar el lock, o por si alguien inserta a mano contra la
-- base. Defensa en profundidad.
-- =============================================================================

-- Necesario para combinar `=` sobre un escalar (cancha_id) con `&&` sobre un
-- rango dentro del mismo índice GiST.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- -----------------------------------------------------------------------------
-- DECISIÓN 1 — PENDIENTE_SENA queda FUERA del constraint. Es obligatorio.
--
-- La app considera libre una pre-reserva PENDIENTE_SENA cuya ventana ya venció
-- (`expira_en < now()`), sin esperar a ReservaExpiracionService. Ver la cláusula
-- `(r.estado != 'PENDIENTE_SENA' OR r.expiraEn IS NULL OR r.expiraEn > :ahora)`
-- en findSuperpuestas.
--
-- Un constraint NO puede expresar "vencida": `now()` no es inmutable y Postgres
-- no lo acepta en el predicado de un índice. Si incluyéramos PENDIENTE_SENA, una
-- pre-reserva abandonada bloquearía el horario a nivel base hasta que el job la
-- pase a CANCELADA_PRERESERVA — y el rebooking legítimo que la app SÍ permite
-- fallaría con un error de constraint. Exactamente el caso a evitar.
--
-- Consecuencia asumida: las pre-reservas siguen protegidas solo por el lock
-- pesimista. Es aceptable: son transitorias (10 min) y el lock ya las cubre.
--
-- DECISIÓN 2 — CANCELADA y CANCELADA_PRERESERVA quedan fuera (liberan el slot),
-- igual que en findSuperpuestas.
--
-- DECISIÓN 3 — AUSENTE queda DENTRO (sigue ocupando).
-- Podría asumirse que un no-show libera el slot; en este código NO lo hace:
-- findSuperpuestas solo descarta CANCELADA y CANCELADA_PRERESERVA. Un no-show se
-- marca sobre un turno que ya pasó, así que no hay rebooking que habilitar. El
-- constraint replica la definición real de la app.
--
-- Regla general que guió las tres: el constraint nunca debe ser MÁS restrictivo
-- que la app, o rechaza escrituras que la app considera válidas.
-- -----------------------------------------------------------------------------

ALTER TABLE reservas
    ADD CONSTRAINT excl_reservas_solapadas
    EXCLUDE USING gist (
        cancha_id WITH =,
        tsrange(fecha_hora_inicio, fecha_hora_fin, '[)') WITH &&
    )
    WHERE (estado IN ('CONFIRMADA', 'FINALIZADA', 'AUSENTE'));

-- '[)' = inicio incluido, fin excluido: una reserva 14:00-15:00 y otra
-- 15:00-16:00 NO se solapan. Coincide con la semántica de la app
-- (`inicio < :fin AND fin > :inicio`).

-- -----------------------------------------------------------------------------
-- LIMITACIÓN CONOCIDA — este constraint es un backstop PARCIAL.
--
-- El modelo soporta canchas LÓGICAS compuestas por canchas FÍSICAS
-- (Cancha.canchasFisicas / canchasNecesarias; ver PoolCanchaCalculator).
-- Reservar la cancha lógica "Cancha Grande" consume las físicas "Cancha 1" y
-- "Cancha 2" — pero esas filas tienen `cancha_id` DISTINTO.
--
-- Un EXCLUDE sobre `cancha_id WITH =` solo detecta choques con el MISMO
-- cancha_id. NO detecta el choque lógica-vs-física, que es justamente el caso
-- de doble-booking más sutil del sistema. Ese caso sigue dependiendo
-- exclusivamente del lock pesimista de la aplicación.
--
-- Cerrarlo a nivel base requeriría desnormalizar (ej. una tabla
-- `ocupacion_cancha_fisica` con una fila por cancha física efectivamente
-- ocupada, y el EXCLUDE sobre esa tabla). Es un cambio de modelo real, no un
-- índice: deliberadamente fuera del alcance de esta migración.
--
-- MANEJO DEL ERROR: si el constraint dispara, Postgres devuelve SQLSTATE 23P01
-- (exclusion_violation), que Spring traduce a DataIntegrityViolationException.
-- GlobalExceptionHandler la mapea a un 409 con mensaje de negocio (mismo texto
-- que el conflicto de bloqueo optimista), para que el caso raro se vea como
-- "el turno se acaba de ocupar" y no como un 500.
-- =============================================================================
