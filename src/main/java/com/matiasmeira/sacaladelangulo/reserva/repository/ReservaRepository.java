package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repositorio para la entidad Reserva.
 * Proporciona operaciones CRUD y consultas especializadas para validación de solapamientos.
 */
@Repository
public interface ReservaRepository extends JpaRepository<Reserva, Long> {

    /**
     * Obtiene todas las reservas solapadas de un predio (establecimiento).
     * Excluye reservas canceladas (CANCELADA y CANCELADA_PRERESERVA) y trae las
     * canchas asociadas. Una PENDIENTE_SENA cuya ventana de 10 minutos ya venció
     * (expiraEn < ahora) tampoco cuenta como solapamiento: así el turno queda libre
     * apenas vence, sin esperar a que corra ReservaExpiracionService.
     *
     * @param estId ID del establecimiento
     * @param inicio Fecha y hora de inicio del período
     * @param fin Fecha y hora de fin del período
     * @param ahora Momento actual, usado para descartar PENDIENTE_SENA ya vencidas
     * @return Lista de reservas solapadas en el predio
     */
    @Query("SELECT r FROM Reserva r JOIN FETCH r.cancha c " +
           "WHERE c.establecimiento.id = :estId " +
           "AND r.estado NOT IN ('CANCELADA', 'CANCELADA_PRERESERVA') " +
           "AND (r.estado != 'PENDIENTE_SENA' OR r.expiraEn IS NULL OR r.expiraEn > :ahora) " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findSuperpuestas(
            @Param("estId") Long estId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("ahora") LocalDateTime ahora
    );

    /**
     * Trae jugador, cancha y turnoFijo en la misma consulta (@EntityGraph): ReservaMapper.mapToResponse
     * dereferencia las tres asociaciones (getJugador().getNombre(), getCancha().getNombre(),
     * getTurnoFijo().getId()) y las tres son LAZY, así que sin esto cada página del listado dispara
     * una consulta por fila. Se usa @EntityGraph y no JOIN FETCH porque la consulta es paginada: las
     * tres son @ManyToOne (to-one), que Hibernate resuelve con un LEFT JOIN sin romper la paginación
     * (con una colección @OneToMany sí la rompería, paginando en memoria - HHH000104).
     */
    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    @org.springframework.data.jpa.repository.Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId AND r.fechaHoraInicio < :finDia AND r.fechaHoraFin > :inicioDia AND r.estado NOT IN :estadosExcluidos")
    org.springframework.data.domain.Page<Reserva> findReservasEnRangoDiario(
            @org.springframework.data.repository.query.Param("canchaId") Long canchaId,
            @org.springframework.data.repository.query.Param("inicioDia") java.time.LocalDateTime inicioDia,
            @org.springframework.data.repository.query.Param("finDia") java.time.LocalDateTime finDia,
            @org.springframework.data.repository.query.Param("estadosExcluidos") List<EstadoReserva> estadosExcluidos,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Misma consulta que findReservasEnRangoDiario pero sin excluir ningún estado, para
     * cuando el dueño/admin pide explícitamente auditar cancelaciones históricas (ver B7
     * en la auditoría). Mismo @EntityGraph, por el mismo motivo.
     */
    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    @org.springframework.data.jpa.repository.Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId AND r.fechaHoraInicio < :finDia AND r.fechaHoraFin > :inicioDia")
    org.springframework.data.domain.Page<Reserva> findReservasEnRangoDiarioIncluyendoCanceladas(
            @org.springframework.data.repository.query.Param("canchaId") Long canchaId,
            @org.springframework.data.repository.query.Param("inicioDia") java.time.LocalDateTime inicioDia,
            @org.springframework.data.repository.query.Param("finDia") java.time.LocalDateTime finDia,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId " +
           "AND r.estado NOT IN ('CANCELADA', 'CANCELADA_PRERESERVA') " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findOverlappingByCanchaId(
            @Param("canchaId") Long canchaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    /**
     * @EntityGraph por el mismo motivo que findReservasEnRangoDiario. Este es el peor caso
     * del listado: al ser de todo el establecimiento, la página trae reservas de varias
     * canchas Y de jugadores distintos, así que el caché de primer nivel casi no ayuda.
     */
    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    @Query("SELECT r FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin " +
           "AND r.estado NOT IN :estadosExcluidos")
    org.springframework.data.domain.Page<Reserva> findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn(
            @Param("estId") Long estId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("estadosExcluidos") List<EstadoReserva> estadosExcluidos,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Misma consulta que findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn
     * pero sin excluir ningún estado (ver B7 en la auditoría). Mismo @EntityGraph.
     */
    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    org.springframework.data.domain.Page<Reserva> findByCancha_Establecimiento_IdAndFechaHoraInicioBetween(
            Long estId,
            LocalDateTime inicio,
            LocalDateTime fin,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * "Mis reservas" del jugador. El @EntityGraph acá evita sobre todo las consultas por
     * `cancha` (una por cada cancha distinta de la página); `jugador` es siempre el mismo
     * para todas las filas, pero se incluye igual para que la garantía sea explícita y no
     * dependa del caché de primer nivel.
     */
    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    org.springframework.data.domain.Page<Reserva> findByJugadorId(Long jugadorId, org.springframework.data.domain.Pageable pageable);

    /**
     * Reservas futuras (fechaHoraInicio posterior a "ahora") de un jugador en los estados
     * dados, sin paginar: usado por UsuarioEliminacionService para cancelar solo lo que
     * todavía no se jugó al eliminar la cuenta. Restringido explícitamente a futuras y no a
     * "todo CONFIRMADA/PENDIENTE_SENA": este dominio no tiene un job que auto-finalice
     * reservas pasadas (finalizarReserva es una acción manual del dueño/empleado), así que
     * sin este filtro una cuenta con historial viejo sin finalizar mass-cancelaría reservas
     * ya jugadas, corrompiendo el historial y disparando notificaciones de liberación de
     * cancha para turnos que ya pasaron.
     */
    List<Reserva> findByJugadorIdAndEstadoInAndFechaHoraInicioAfter(Long jugadorId, List<EstadoReserva> estados, LocalDateTime ahora);

    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    org.springframework.data.domain.Page<Reserva> findByJugadorIdAndEstado(
            Long jugadorId,
            com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva estado,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Igual que findSuperpuestas pero para varios establecimientos a la vez: usado por
     * ComplejoPublicoService.filtrarPorDisponibilidad para resolver disponibilidad en lote
     * sin una consulta por establecimiento candidato. A diferencia de la vieja
     * findCanchaIdsConSolapamiento (que devolvía solo IDs), acá hace falta la Reserva
     * completa -- cancha y canchasNecesarias -- para poder correr PoolCanchaCalculator y
     * evaluar pools de canchas físicas/lógicas igual que DisponibilidadService.estaLibre.
     */
    @Query("SELECT r FROM Reserva r JOIN FETCH r.cancha c " +
           "WHERE c.establecimiento.id IN :estIds " +
           "AND r.estado NOT IN ('CANCELADA', 'CANCELADA_PRERESERVA') " +
           "AND (r.estado != 'PENDIENTE_SENA' OR r.expiraEn IS NULL OR r.expiraEn > :ahora) " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findSuperpuestasEnEstablecimientos(
            @Param("estIds") List<Long> estIds,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("ahora") LocalDateTime ahora
    );

    /**
     * Libera (pasa a CANCELADA_PRERESERVA) todas las reservas PENDIENTE_SENA cuya
     * ventana de 10 minutos ya venció. Usado por ReservaExpiracionService; mismo
     * patrón de bulk-update que SolicitudIdempotenteRepository.borrarExpiradas.
     *
     * @param ahora Momento actual, contra el que se compara expiraEn
     * @return Cantidad de reservas liberadas
     */
    @Modifying
    @Query("UPDATE Reserva r SET r.estado = 'CANCELADA_PRERESERVA' " +
           "WHERE r.estado = 'PENDIENTE_SENA' AND r.expiraEn < :ahora")
    int liberarReservasVencidas(@Param("ahora") LocalDateTime ahora);

    /**
     * Trae la reserva junto con cancha -> establecimiento -> dueño y jugador en una sola
     * consulta, evitando 3 round-trips adicionales por lazy loading encadenado al
     * confirmar/cancelar (ver ReservaService). Se usa LEFT JOIN FETCH sobre el jugador
     * porque las reservas de mostrador (manuales) no tienen un jugador asociado.
     */
    @Query("SELECT r FROM Reserva r " +
           "LEFT JOIN FETCH r.jugador " +
           "JOIN FETCH r.cancha c " +
           "JOIN FETCH c.establecimiento e " +
           "JOIN FETCH e.dueno " +
           "WHERE r.id = :id")
    java.util.Optional<Reserva> findByIdConEstablecimientoYDueno(@Param("id") Long id);

    /**
     * Variante en lote de {@link #findByIdConEstablecimientoYDueno}, para el aviso único de
     * un turno fijo semanal (ver TurnoFijoCreadoEvent): trae las N ocurrencias con el mismo
     * grafo de asociaciones en UNA consulta, en vez de una por ocurrencia.
     *
     * <p>ORDER BY fechaHoraInicio y no por id: el email lista las fechas y tienen que salir
     * en orden cronológico. Hoy id y fecha coinciden en orden porque saveAll persiste las
     * ocurrencias ya ordenadas, pero eso es un detalle de implementación de
     * TurnoFijoService.crear, no algo de lo que el email deba depender.
     */
    @Query("SELECT r FROM Reserva r " +
           "LEFT JOIN FETCH r.jugador " +
           "JOIN FETCH r.cancha c " +
           "JOIN FETCH c.establecimiento e " +
           "JOIN FETCH e.dueno " +
           "WHERE r.id IN :ids " +
           "ORDER BY r.fechaHoraInicio ASC")
    List<Reserva> findAllByIdInConEstablecimientoYDueno(@Param("ids") List<Long> ids);

    /**
     * Cuenta las reservas futuras que todavía pueden verse afectadas por un cambio de
     * política de cancelación (CONFIRMADA o PENDIENTE_SENA, con fechaHoraInicio posterior
     * a "ahora"). Usado por PoliticaCancelacionService para informarle al dueño el impacto
     * de un cambio antes de persistirlo. No incluye CANCELADA/CANCELADA_PRERESERVA/
     * FINALIZADA/AUSENTE ni reservas ya jugadas.
     */
    @Query("SELECT COUNT(r) FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.estado IN ('CONFIRMADA', 'PENDIENTE_SENA') AND r.fechaHoraInicio > :ahora")
    long countReservasFuturasActivas(@Param("estId") Long estId, @Param("ahora") LocalDateTime ahora);

    // ===== Reportes agregados (panel del dueño) =====
    // Solo cuentan reservas FINALIZADA: es el único estado que representa dinero/turno
    // efectivamente cerrado (decisión de negocio explícita, ver spec de reportes).

    /**
     * Desglose de facturación por método de pago real (no hay distinción online/mostrador
     * confiable en el modelo: MetodoPago se elige recién al finalizar y puede cobrarse por
     * cualquier canal, incluso Mercado Pago en el mostrador).
     */
    @Query("SELECT r.metodoPago, SUM(r.precioTotal), COUNT(r) FROM Reserva r " +
           "WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin GROUP BY r.metodoPago")
    List<Object[]> sumFacturacionPorMetodoPago(@Param("estId") Long estId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    /**
     * Proyección liviana (fecha + precio, no la entidad completa) para armar la serie
     * temporal diaria de facturación: se evita agrupar por fecha en JPQL (CAST/FUNCTION de
     * truncado de fecha tiene comportamiento de tipo de retorno poco predecible entre
     * versiones de Hibernate) y se agrupa por día en memoria, sobre un dataset acotado.
     */
    @Query("SELECT r.fechaHoraInicio, r.precioTotal FROM Reserva r " +
           "WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin")
    List<Object[]> findFechaYPrecioParaSerieTemporal(@Param("estId") Long estId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    /**
     * Proyección liviana (solo fechaHoraInicio, no la entidad completa) para el ranking de
     * horarios más pedidos: agrupar por día de semana + hora vía EXTRACT(DOW ...) no es
     * portable en JPQL (el nombre de campo "DOW" no es un extract field estándar reconocido
     * por el parser de Hibernate, aunque sí es válido en Postgres nativo) — se agrupa en
     * memoria con java.time.DayOfWeek/getHour(), sobre un dataset acotado al rango pedido.
     */
    @Query("SELECT r.fechaHoraInicio FROM Reserva r WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin")
    List<LocalDateTime> findFechasParaHorariosPedidos(@Param("estId") Long estId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    /**
     * Primera reserva FINALIZADA de cada jugador en este establecimiento, considerando TODO
     * el historial (no solo el rango pedido): es la única forma de saber si la primera
     * reserva de un jugador cae dentro del rango o es anterior (cliente "nuevo" vs. viejo).
     */
    @Query("SELECT r.jugador.id, MIN(r.fechaHoraInicio) FROM Reserva r " +
           "WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' AND r.jugador IS NOT NULL " +
           "GROUP BY r.jugador.id")
    List<Object[]> primeraReservaPorJugador(@Param("estId") Long estId);

    @Query("SELECT r.jugador.id, r.jugador.nombre, COUNT(r) FROM Reserva r " +
           "WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' AND r.jugador IS NOT NULL " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin GROUP BY r.jugador.id, r.jugador.nombre ORDER BY COUNT(r) DESC")
    List<Object[]> topClientesPorReservas(@Param("estId") Long estId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin, Pageable pageable);

    /**
     * Proyección liviana para el reporte de ocupación (ver ReservaOcupacionProjection):
     * el prorrateo por franja horaria y horario de atención se calcula en memoria en
     * OcupacionCalculator, no acá.
     */
    @Query("SELECT r.cancha.id AS canchaId, r.fechaHoraInicio AS fechaHoraInicio, r.fechaHoraFin AS fechaHoraFin " +
           "FROM Reserva r WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin")
    List<ReservaOcupacionProjection> findProyeccionOcupacion(@Param("estId") Long estId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    /**
     * Cantidad de reservas marcadas AUSENTE (no-show) de un establecimiento en el rango,
     * para el reporte de clientes (ver ReporteClientesService).
     */
    @Query("SELECT COUNT(r) FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.estado = 'AUSENTE' AND r.fechaHoraInicio BETWEEN :inicio AND :fin")
    long countAusenciasEnRango(@Param("estId") Long estId, @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    /**
     * Cantidad de reservas AUSENTE por jugador, restringido a un conjunto de jugadores
     * (el top de clientes ya calculado), para el reporte de clientes (ver ReporteClientesService).
     */
    @Query("SELECT r.jugador.id, COUNT(r) FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.estado = 'AUSENTE' AND r.jugador.id IN :jugadorIds AND r.fechaHoraInicio BETWEEN :inicio AND :fin " +
           "GROUP BY r.jugador.id")
    List<Object[]> countAusenciasPorJugadoresEnRango(@Param("estId") Long estId, @Param("jugadorIds") List<Long> jugadorIds,
                                                       @Param("inicio") LocalDateTime inicio, @Param("fin") LocalDateTime fin);

    // ===== Padrón de clientes (ClienteService) =====
    // A diferencia de los reportes de arriba, quién integra el padrón NO se restringe a
    // FINALIZADA: cualquier jugador con una reserva (de cualquier estado) en el
    // establecimiento es un cliente conocido (incluye a quien solo tiene una reserva
    // CONFIRMADA futura). reservasTotales/totalGastado/ultimaReserva siguen contando solo
    // lo FINALIZADA y ausencias solo lo AUSENTE, mismo criterio que los reportes.

    @Query("SELECT DISTINCT r.jugador.id FROM Reserva r WHERE r.cancha.establecimiento.id = :estId AND r.jugador IS NOT NULL")
    List<Long> jugadorIdsDelEstablecimiento(@Param("estId") Long estId);

    @Query("SELECT r.jugador.id, COUNT(r), SUM(r.precioTotal), MAX(r.fechaHoraInicio) FROM Reserva r " +
           "WHERE r.cancha.establecimiento.id = :estId AND r.estado = 'FINALIZADA' AND r.jugador IS NOT NULL " +
           "GROUP BY r.jugador.id")
    List<Object[]> historicoAgregadoPorJugador(@Param("estId") Long estId);

    @Query("SELECT r.jugador.id, COUNT(r) FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.estado = 'AUSENTE' AND r.jugador IS NOT NULL GROUP BY r.jugador.id")
    List<Object[]> countAusenciasPorJugador(@Param("estId") Long estId);

    /**
     * Variantes escalares (un solo jugador) de historicoAgregadoPorJugador/countAusenciasPorJugador,
     * para la ficha individual del cliente: evita traer el agregado de todo el establecimiento
     * para mostrar un solo registro. Devuelve List<Object[]> (siempre una sola fila, incluso sin
     * matches) y no Object[] a secas: Spring Data envuelve mal un retorno Object[] crudo desde una
     * consulta multi-columna (el caller termina con un array anidado en vez de la fila).
     */
    @Query("SELECT COUNT(r), SUM(r.precioTotal), MAX(r.fechaHoraInicio) FROM Reserva r " +
           "WHERE r.cancha.establecimiento.id = :estId AND r.jugador.id = :jugadorId AND r.estado = 'FINALIZADA'")
    List<Object[]> historicoAgregadoDeJugador(@Param("estId") Long estId, @Param("jugadorId") Long jugadorId);

    @Query("SELECT COUNT(r) FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.jugador.id = :jugadorId AND r.estado = 'AUSENTE'")
    long countAusenciasDeJugador(@Param("estId") Long estId, @Param("jugadorId") Long jugadorId);

    boolean existsByJugador_IdAndCancha_Establecimiento_Id(Long jugadorId, Long establecimientoId);

    // ===== Turnos fijos (TurnoFijoService) =====

    /**
     * Cantidad de ocurrencias futuras vivas y fecha de la próxima, por serie, para toda una
     * página del listado de turnos fijos EN UNA SOLA consulta. Sin esto el listado hace dos
     * consultas por fila, que es el N+1 clásico de un listado con agregados.
     */
    @Query("SELECT r.turnoFijo.id, COUNT(r), MIN(r.fechaHoraInicio) FROM Reserva r " +
           "WHERE r.turnoFijo.id IN :turnoFijoIds " +
           "AND r.estado IN ('CONFIRMADA', 'PENDIENTE_SENA') " +
           "AND r.fechaHoraInicio > :ahora " +
           "GROUP BY r.turnoFijo.id")
    List<Object[]> agregadosPorTurnoFijo(@Param("turnoFijoIds") List<Long> turnoFijoIds,
                                         @Param("ahora") LocalDateTime ahora);

    /**
     * Ocurrencias de una serie de turno fijo, para el detalle. No necesita @EntityGraph: la
     * cancha (y el jugador, si lo hay) son siempre los mismos en toda la serie, así que el
     * caché de primer nivel de Hibernate ya evita repetir esas consultas fila a fila.
     */
    List<Reserva> findByTurnoFijoIdOrderByFechaHoraInicioAsc(Long turnoFijoId);

    /**
     * Historial completo (todos los estados) de reservas de un jugador en un establecimiento,
     * para la ficha del cliente. Mismo @EntityGraph que el resto de los listados paginados de
     * Reserva, por el mismo motivo (evitar N+1 sobre jugador/cancha/turnoFijo, las tres LAZY).
     */
    @EntityGraph(attributePaths = {"jugador", "cancha", "turnoFijo"})
    org.springframework.data.domain.Page<Reserva> findByJugador_IdAndCancha_Establecimiento_Id(Long jugadorId, Long establecimientoId, Pageable pageable);
 }