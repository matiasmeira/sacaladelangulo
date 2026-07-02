package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de negocio para reservas.
 * Implementa lógica de validación de solapamientos y disponibilidad de canchas lógicas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReservaService {

    private final ReservaRepository reservaRepository;
    private final CanchaRepository canchaRepository;
    private final UsuarioRepository usuarioRepository;

    /**
     * Crea una nueva reserva con validación de solapamientos y disponibilidad de pool.
     *
     * @param request DTO con datos de la reserva
     * @param email Email del usuario autenticado (jugador)
     * @return ReservaResponse con los datos de la reserva creada
     */
    public ReservaResponse crearReserva(ReservaRequest request, String email) {
        log.info("Iniciando creación de reserva. Email: {}, Cancha: {}, Inicio: {}, Fin: {}",
                email, request.canchaId(), request.fechaHoraInicio(), request.fechaHoraFin());

        // Buscar el jugador
        Usuario jugador = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        log.debug("Usuario encontrado: {}", jugador.getId());

        // Buscar la cancha
        Cancha cancha = canchaRepository.findById(request.canchaId())
                .orElseThrow(() -> new IllegalArgumentException("Cancha no encontrada"));
        log.debug("Cancha encontrada: {} - Establecimiento: {}", cancha.getId(), cancha.getEstablecimiento().getId());

        // Validar que inicio sea antes de fin
        if (!request.fechaHoraInicio().isBefore(request.fechaHoraFin())) {
            log.warn("Fechas inválidas. Inicio >= Fin");
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }

        // Validar que sean en el futuro
        if (request.fechaHoraInicio().isBefore(LocalDateTime.now())) {
            log.warn("Reserva solicitada en el pasado");
            throw new IllegalArgumentException("No se pueden crear reservas en el pasado");
        }

        int minutoInicio = request.fechaHoraInicio().getMinute();
        if (minutoInicio != 0 && minutoInicio != 30) {
            log.warn("Inicio de reserva inválido: {}", request.fechaHoraInicio());
            throw new IllegalArgumentException("Las reservas solo pueden iniciar en punto (:00) o y media (:30)");
        }
        if (Boolean.FALSE.equals(cancha.getPermiteInicioMediaHora()) && minutoInicio != 0) {
            log.warn("Cancha no permite inicio a media hora: {}", request.fechaHoraInicio());
            throw new IllegalArgumentException("Esta cancha solo permite iniciar reservas en horas en punto exactas (:00)");
        }

        long duracionMinutos = java.time.Duration.between(request.fechaHoraInicio(), request.fechaHoraFin()).toMinutes();
        if (!cancha.getDuracionesPermitidas().contains((int) duracionMinutos)) {
            log.warn("Duración no permitida: {} minutos", duracionMinutos);
            throw new IllegalArgumentException("Duración no permitida. Opciones válidas: " + cancha.getDuracionesPermitidas() + " minutos");
        }

        // Buscar todas las reservas solapadas del establecimiento
        List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                cancha.getEstablecimiento().getId(),
                request.fechaHoraInicio(),
                request.fechaHoraFin()
        );
        log.debug("Se encontraron {} reservas solapadas en el predio", solapadas.size());

        // Regla 1: Validar cancha exacta
        boolean canchaExactaReservada = solapadas.stream()
                .anyMatch(r -> r.getCancha().getId().equals(cancha.getId()));

        if (canchaExactaReservada) {
            log.warn("Cancha exacta ya está reservada. Cancha: {}", cancha.getId());
            throw new IllegalArgumentException("La cancha exacta ya está reservada en ese horario");
        }
        log.debug("Validación de cancha exacta: OK");

        // Regla 2: Validar pool de canchas lógicas
        validarPoolCanchas(cancha, solapadas);
        log.debug("Validación de pool de canchas: OK");

        BigDecimal duracionHoras = BigDecimal.valueOf(duracionMinutos)
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal precioPorHora = cancha.getPrecioBase();
        for (var tarifa : cancha.getTarifas()) {
            if (tarifa.getDiaSemana() == request.fechaHoraInicio().getDayOfWeek() &&
                    !request.fechaHoraInicio().toLocalTime().isBefore(tarifa.getHoraInicio()) &&
                    request.fechaHoraInicio().toLocalTime().isBefore(tarifa.getHoraFin())) {
                precioPorHora = tarifa.getPrecio();
                break;
            }
        }
        BigDecimal precioCalculado = precioPorHora.multiply(duracionHoras);

        // Crear la reserva
        Reserva reserva = Reserva.builder()
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(request.fechaHoraInicio())
                .fechaHoraFin(request.fechaHoraFin())
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(precioCalculado)
                .senaPagada(BigDecimal.ZERO)
                .build();

        Reserva reservaGuardada = reservaRepository.save(reserva);
        log.info("Nueva reserva creada con éxito. ID: {}, Cancha: {}, Jugador: {}",
                reservaGuardada.getId(), cancha.getNombre(), jugador.getNombre());

        return mapToResponse(reservaGuardada);
    }

    /**
     * Confirma una reserva y cambia su estado a CONFIRMADA.
     * Solo el dueño del establecimiento o un administrador puede confirmar.
     *
     * @param reservaId ID de la reserva a confirmar
     * @param email Email del usuario autenticado
     * @return ReservaResponse con los datos actualizados
     */
    public ReservaResponse confirmarReserva(Long reservaId, String email) {
        log.info("Iniciando confirmación de reserva. ID: {}, Email: {}", reservaId, email);

        // Buscar la reserva
        Reserva reserva = reservaRepository.findById(reservaId)
                .orElseThrow(() -> new IllegalArgumentException("Reserva no encontrada"));
        log.debug("Reserva encontrada. Estado actual: {}", reserva.getEstado());

        // Buscar el usuario autenticado
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        // Validar que sea dueño del establecimiento o administrador
        Long duenioEstablecimientoId = reserva.getCancha().getEstablecimiento().getDueno().getId();
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !usuarioAutenticado.getId().equals(duenioEstablecimientoId)) {
            log.warn("Acceso denegado. Usuario: {}, Dueño: {}", usuarioAutenticado.getId(), duenioEstablecimientoId);
            throw new AccessDeniedException("No está autorizado para confirmar esta reserva");
        }

        // Cambiar estado a CONFIRMADA
        reserva.setEstado(EstadoReserva.CONFIRMADA);
        Reserva reservaActualizada = reservaRepository.save(reserva);
        log.info("Reserva confirmada con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());

        return mapToResponse(reservaActualizada);
    }

    /**
     * Valida que haya disponibilidad en el pool de canchas lógicas.
     * Si la cancha solicitada es física y pertenece a un pool, valida el uso total del pool.
     *
     * @param cancha Cancha solicitada
     * @param solapadas Reservas solapadas en el establecimiento
     */
    private void validarPoolCanchas(Cancha cancha, List<Reserva> solapadas) {
        // Obtener todas las canchas del establecimiento
        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(
                cancha.getEstablecimiento().getId()
        );

        // Iterar sobre todas las canchas para encontrar pools
        for (Cancha canchaDelPool : todasLasCanchas) {
            // Si tiene canchas físicas, es una cancha lógica
            if (canchaDelPool.getCanchasFisicas() != null && !canchaDelPool.getCanchasFisicas().isEmpty()) {
                List<Long> poolIds = canchaDelPool.getCanchasFisicas().stream()
                        .map(Cancha::getId)
                        .collect(Collectors.toList());

                log.debug("Cancha lógica encontrada: {} con {} canchas físicas", canchaDelPool.getId(), poolIds.size());

                // Determinar si la cancha solicitada afecta este pool
                boolean afectaEstePool = canchaDelPool.getId().equals(cancha.getId()) ||
                        poolIds.contains(cancha.getId());

                if (afectaEstePool) {
                    log.debug("La cancha solicitada afecta el pool. Validando disponibilidad...");

                    // Calcular uso actual del pool
                    int usoActual = 0;
                    for (Reserva reservaSolapada : solapadas) {
                        Long canchaReservadaId = reservaSolapada.getCancha().getId();

                        // Si es una cancha física del pool
                        if (poolIds.contains(canchaReservadaId)) {
                            usoActual += 1;
                            log.debug("Uso actual +1 (cancha física): total = {}", usoActual);
                        }
                        // Si es la cancha lógica misma
                        else if (canchaReservadaId.equals(canchaDelPool.getId())) {
                            Integer canchasNecesarias = reservaSolapada.getCancha().getCanchasNecesarias();
                            if (canchasNecesarias != null && canchasNecesarias > 0) {
                                usoActual += canchasNecesarias;
                                log.debug("Uso actual +{} (cancha lógica): total = {}", canchasNecesarias, usoActual);
                            }
                        }
                    }

                    // Calcular uso nuevo
                    int usoNuevo = cancha.getId().equals(canchaDelPool.getId()) ?
                            (cancha.getCanchasNecesarias() != null ? cancha.getCanchasNecesarias() : 1) : 1;
                    log.debug("Uso nuevo: {}", usoNuevo);

                    // Validar disponibilidad
                    int capacidadPool = poolIds.size();
                    if (usoActual + usoNuevo > capacidadPool) {
                        log.warn("No hay disponibilidad en el pool. Uso actual: {}, Uso nuevo: {}, Capacidad: {}",
                                usoActual, usoNuevo, capacidadPool);
                        throw new IllegalArgumentException("No hay disponibilidad en el pool para armar esta cancha");
                    }
                    log.debug("Validación de pool: OK (Uso: {}/{} disponibles)", usoActual + usoNuevo, capacidadPool);
                }
            }
        }
    }

    /**
     * Mapea una entidad Reserva a su DTO de respuesta.
     *
     * @param reserva Entidad de reserva
     * @return ReservaResponse
     */
    public org.springframework.data.domain.Page<ReservaResponse> obtenerReservasPorCanchaYFecha(Long canchaId, java.time.LocalDate fecha, org.springframework.data.domain.Pageable pageable) {
        java.time.LocalDateTime inicioDia = fecha.atStartOfDay();
        java.time.LocalDateTime finDia = fecha.atTime(23, 59, 59);
        return reservaRepository.findByCanchaIdAndFechaHoraInicioBetweenAndEstadoNot(canchaId, inicioDia, finDia, EstadoReserva.CANCELADA, pageable)
                .map(this::mapToResponse);
    }

    public org.springframework.data.domain.Page<ReservaResponse> obtenerReservasPorEstablecimientoYFecha(Long estId, java.time.LocalDate fecha, org.springframework.data.domain.Pageable pageable) {
        java.time.LocalDateTime inicioDia = fecha.atStartOfDay();
        java.time.LocalDateTime finDia = fecha.atTime(23, 59, 59);
        return reservaRepository.findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNot(estId, inicioDia, finDia, EstadoReserva.CANCELADA, pageable)
                .map(this::mapToResponse);
    }

    private ReservaResponse mapToResponse(Reserva reserva) {
        return new ReservaResponse(
                reserva.getId(),
                reserva.getJugador().getId(),
                reserva.getJugador().getNombre(),
                reserva.getCancha().getId(),
                reserva.getCancha().getNombre(),
                reserva.getFechaHoraInicio(),
                reserva.getFechaHoraFin(),
                reserva.getEstado().name(),
                reserva.getPrecioTotal(),
                reserva.getSenaPagada()
        );
    }
}
