-- =============================================================================
-- V21 — Índices faltantes detectados en auditoría de performance
--
-- CONTEXTO: V9 (idx_reservas_*) y V11 (idx_gastos_*, idx_ventas_buffet_*, etc.)
-- ya cubrieron los caminos calientes de `reservas` y de los módulos de buffet/
-- caja/gastos, con el criterio explícito "Postgres no indexa FK solo, hay que
-- crearlo a mano". Esta migración aplica el MISMO criterio a columnas que
-- quedaron afuera de esas dos pasadas: algunas porque pertenecen a queries que
-- corren dentro de la MISMA transacción de creación de reserva que V9 ya
-- optimizó parcialmente (cancha_deportes, cancha_composicion, bloqueos_cancha),
-- y otras porque el módulo se agregó después de V11 (auditoría de empleados,
-- días no laborables, búsqueda pública de marketplace).
--
-- Cada bloque referencia el/los métodos de repositorio que respalda, para que
-- quede trazable qué query motivó el índice.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- P0.1 — bloqueos_cancha: mismo patrón de solapamiento que `reservas`, sin
-- ningún índice más allá de la PK.
--
-- Respalda:
--   - BloqueoCanchaRepository.findOverlappingBloqueos   (ReservaService, se
--                                                        llama en TODA
--                                                        creación/edición de
--                                                        reserva para chequear
--                                                        que el horario no esté
--                                                        bloqueado por mantenimiento)
--   - findByCanchaIdOrderByFechaInicioAsc                (listado del panel)
--
-- Mismo orden de columnas que idx_reservas_cancha_rango (V9) y mismo
-- razonamiento: igualdad (cancha_id) antes que rango (fecha_inicio/fecha_fin).
-- -----------------------------------------------------------------------------
CREATE INDEX idx_bloqueos_cancha_cancha_rango
    ON bloqueos_cancha (cancha_id, fecha_inicio, fecha_fin);

-- -----------------------------------------------------------------------------
-- P0.2 — cancha_deportes: @ElementCollection de Cancha.deportes, sin índice
-- sobre su FK (cancha_id).
--
-- Se joinea en TRES queries de CanchaRepository vía @EntityGraph(deportes):
--   - findByEstablecimientoIdAndIsActiveTrue      (se llama en TODA creación de
--                                                  reserva para armar el pool de
--                                                  canchas, y en cada grilla de
--                                                  disponibilidad — ver comentario
--                                                  de idx_canchas_establecimiento
--                                                  en V9)
--   - findByEstablecimientoIdInAndIsActiveTrue    (búsqueda pública en lote)
--   - findActivasConDeportesYTarifasByEstablecimientoIdIn (marketplace público)
--
-- Es decir: comparte camino caliente con idx_canchas_establecimiento (V9), pero
-- el JOIN hacia cancha_deportes dentro de esa misma consulta quedó sin indexar.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_cancha_deportes_cancha
    ON cancha_deportes (cancha_id);

-- -----------------------------------------------------------------------------
-- P0.3 — cancha_composicion: @ManyToMany Cancha.canchasFisicas, sin índice.
--
-- Se joinea (dirección cancha_logica_id -> cancha_fisica_id) en la MISMA
-- consulta findByEstablecimientoIdAndIsActiveTrue de arriba, vía
-- @EntityGraph(canchasFisicas): PoolCanchaCalculator.canchasRelacionadas /
-- hayDisponibilidad son cálculo puro en memoria sobre la lista ya cargada, así
-- que el único costo de DB es este JOIN, una vez por creación de reserva.
--
-- Solo se indexa cancha_logica_id (dirección real de la FK en el JOIN): no hay
-- ningún query en el código que navegue cancha_fisica_id -> cancha_logica_id
-- (esa dirección se resuelve recorriendo en memoria "todasLasCanchasDelEstablecimiento",
-- no con una consulta nueva).
-- -----------------------------------------------------------------------------
CREATE INDEX idx_cancha_composicion_logica
    ON cancha_composicion (cancha_logica_id);

-- -----------------------------------------------------------------------------
-- P1.1 — usuarios.establecimiento_id: FK sin índice.
--
-- Respalda 4 queries de UsuarioRepository usadas en el panel de empleados:
--   - findByEstablecimientoIdAndNombreIgnoreCaseAndRol
--   - existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue
--   - findByEstablecimientoIdAndRol
--   - findByEstablecimientoIdAndRolAndIsActiveTrue
-- -----------------------------------------------------------------------------
CREATE INDEX idx_usuarios_establecimiento
    ON usuarios (establecimiento_id);

-- -----------------------------------------------------------------------------
-- P1.2 — establecimientos.dueno_id: FK sin índice.
--
-- Respalda EstablecimientoRepository.findByDuenoIdAndIsActiveTrue /
-- countByDuenoIdAndIsActiveTrue ("mis establecimientos" del dueño, se llama en
-- cada carga del panel).
-- -----------------------------------------------------------------------------
CREATE INDEX idx_establecimientos_dueno
    ON establecimientos (dueno_id);

-- -----------------------------------------------------------------------------
-- P1.3 — dias_no_laborables.establecimiento_id: FK sin índice.
--
-- Respalda 5 queries de DiaNoLaborableRepository, incluyendo las dos variantes
-- en lote (findByEstablecimientoIdAndFechaBetween para la grilla de
-- disponibilidad, findByEstablecimientoIdInAndFecha para la búsqueda pública).
-- Se agrega fecha como segunda columna: 4 de las 5 queries filtran por fecha o
-- por un rango de fecha además del establecimiento.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_dias_no_laborables_establecimiento_fecha
    ON dias_no_laborables (establecimiento_id, fecha);

-- -----------------------------------------------------------------------------
-- P1.4 — registro_auditoria_empleados.establecimiento_id: FK sin índice.
--
-- Tabla de alto crecimiento (una fila por cada acción de cada empleado, ver V1).
-- Respalda RegistroAuditoriaRepository.findByEstablecimientoIdOrderByFechaHoraDesc
-- (listado paginado del panel de auditoría). DESC para que coincida con el
-- ORDER BY del método.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_registro_auditoria_establecimiento_fecha
    ON registro_auditoria_empleados (establecimiento_id, fecha_hora DESC);

-- -----------------------------------------------------------------------------
-- P1.5 — horarios_atencion.establecimiento_id: FK sin índice.
--
-- Respalda el fetch en lote de EstablecimientoRepository.precargarHorarios
-- (@EntityGraph horariosAtencion), usado en el filtro de disponibilidad de la
-- búsqueda pública (ComplejoPublicoService.filtrarPorDisponibilidad) para TODOS
-- los establecimientos candidatos de una búsqueda.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_horarios_atencion_establecimiento
    ON horarios_atencion (establecimiento_id);

-- -----------------------------------------------------------------------------
-- P1.6 — establecimientos activos: sin índice sobre is_active, usado en TODA
-- consulta del marketplace público (endpoint sin autenticación, el de mayor
-- exposición a tráfico/abuso del sistema):
--   - findCercanosYPorDeporte  (pre-filtro por bounding box de lat/lon, ver
--                               comentario ya existente en el repositorio sobre
--                               por qué se filtra por rango antes del Haversine
--                               exacto — pero ese rango hoy no tiene ningún
--                               índice que lo respalde)
--   - findActivosPorDeporte
--
-- Índice parcial (WHERE is_active = true) sobre (latitud, longitud): cubre el
-- filtro de activos con costo mínimo (se excluyen los inactivos del índice) y
-- deja que Postgres arranque el bounding box con un range scan sobre latitud
-- en vez de un Seq Scan completo de la tabla.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_establecimientos_activos_geo
    ON establecimientos (latitud, longitud)
    WHERE is_active = true;

-- =============================================================================
-- NO se agregan índices en esta migración para (revisados y descartados,
-- volumen bajo o beneficio marginal frente al costo de escritura):
--   - bloqueos_cancha(cancha_id) simple, aparte del compuesto de arriba: no
--     aporta nada. Por la regla de prefijo izquierdo de un B-tree, el índice
--     compuesto (cancha_id, fecha_inicio, fecha_fin) ya sirve tal cual a
--     cualquier query que filtre solo por cancha_id (findByCanchaIdOrderBy...
--     incluida); un índice adicional de una sola columna sería puro costo de
--     escritura sin beneficio de lectura.
--   - usuario_permisos, cancha_duraciones, cancha_precios_duracion, tarifas,
--     tarifa_precios_duracion: FKs sin índice, pero colecciones chicas (pocas
--     filas por cancha/usuario) navegadas siempre desde un id ya conocido.
--   - tokens_verificacion_email.email / tokens_recuperacion_password.email:
--     tablas transitorias (TTL de minutos), findByEmail ya corre sobre un
--     volumen acotado.
--   - dispositivo_caja.establecimiento_id: tabla chica (pocos dispositivos por
--     establecimiento).
--   - canchas(establecimiento_id, is_active): idx_canchas_establecimiento (V9)
--     ya cubre el filtro principal; is_active como segunda columna es una
--     mejora marginal dado que la cantidad de canchas por establecimiento es
--     chica (decenas, no miles).
--   - turno_caja: idx_turno_caja_establecimiento (V6) ya cubre el filtro
--     principal; el ORDER BY fecha_apertura DESC de
--     findByEstablecimientoIdOrderByFechaAperturaDesc no está cubierto, pero el
--     volumen es de ~1-2 filas por día por establecimiento.
--   - reservas: índice compuesto (cancha_id, estado, fecha_hora_inicio) para
--     las ~12 queries de reportes que siempre filtran estado = 'FINALIZADA'.
--     Quedan cubiertas indirectamente por idx_reservas_cancha_rango (V9), vía
--     canchas.establecimiento_id -> reservas.cancha_id -> filtro de rango,
--     con recheck de estado contra el heap. Se evalúa aparte porque compite en
--     tamaño/costo de escritura con el índice ya calibrado para el camino
--     crítico (creación de reservas bajo lock pesimista); ver reporte de
--     auditoría, sección P2, ítem "reservas.reportes".
-- =============================================================================
