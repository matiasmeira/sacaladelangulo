package com.matiasmeira.sacaladelangulo.feedback.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackMapper;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackRequest;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackResponse;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de negocio para calificaciones y comentarios de reservas finalizadas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final ReservaRepository reservaRepository;
    private final UsuarioRepository usuarioRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final FeedbackMapper feedbackMapper;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final ComplejoDetalleCache complejoDetalleCache;

    /**
     * Crea la calificación de una reserva. Solo puede calificarla el jugador que la
     * jugó, y solo una vez que la reserva está FINALIZADA.
     *
     * @param reservaId ID de la reserva a calificar
     * @param request DTO con puntuación y comentario
     * @param email Email del usuario autenticado (jugador)
     * @return FeedbackResponse con el feedback creado
     */
    public FeedbackResponse crearFeedback(Long reservaId, FeedbackRequest request, String email) {
        log.info("Iniciando creación de feedback. Reserva: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));

        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);
        validarEsElJugador(reserva, usuarioAutenticado);

        if (reserva.getEstado() != EstadoReserva.FINALIZADA) {
            throw new IllegalArgumentException("Solo se puede calificar una reserva finalizada");
        }

        if (feedbackRepository.existsByReservaId(reservaId)) {
            throw new IllegalArgumentException("Ya calificaste esta reserva");
        }

        Feedback feedback = Feedback.builder()
                .reserva(reserva)
                .puntuacion(request.puntuacion())
                .comentario(request.comentario())
                .destacado(false)
                .build();

        Feedback feedbackGuardado;
        try {
            feedbackGuardado = feedbackRepository.saveAndFlush(feedback);
        } catch (DataIntegrityViolationException ex) {
            // existsByReservaId + save no es atómico: dos requests casi simultáneas del
            // mismo jugador (doble click/retry) pueden pasar ambas la validación antes de
            // que cualquiera persista (ver M18 en la auditoría).
            throw new IllegalArgumentException("Ya calificaste esta reserva");
        }
        log.info("Feedback creado con éxito. ID: {}, Reserva: {}", feedbackGuardado.getId(), reservaId);

        // La ficha pública publica promedio, cantidad de calificaciones y comentario
        // destacado, así que cualquier alta/baja/edición de feedback la desactualiza.
        invalidarFichaDe(reserva);

        return feedbackMapper.mapToResponse(feedbackGuardado);
    }

    /**
     * Edita la calificación/comentario de un feedback propio. Misma validación de
     * ownership que crearFeedback: solo el jugador que dejó el feedback puede editarlo
     * (ver B10 en la auditoría).
     *
     * @param feedbackId ID del feedback a editar
     * @param request DTO con la nueva puntuación y comentario
     * @param email Email del usuario autenticado (jugador)
     * @return FeedbackResponse con el feedback ya actualizado
     */
    public FeedbackResponse editarFeedback(Long feedbackId, FeedbackRequest request, String email) {
        log.info("Iniciando edición de feedback. Feedback: {}, Email: {}", feedbackId, email);

        Feedback feedback = feedbackRepository.findByIdConReservaYJugador(feedbackId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback no encontrado"));
        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);
        validarEsElJugador(feedback.getReserva(), usuarioAutenticado);

        feedback.setPuntuacion(request.puntuacion());
        feedback.setComentario(request.comentario());
        Feedback feedbackActualizado = feedbackRepository.save(feedback);
        log.info("Feedback editado con éxito. ID: {}", feedbackId);

        invalidarFichaDe(feedback.getReserva());

        return feedbackMapper.mapToResponse(feedbackActualizado);
    }

    /**
     * Elimina un feedback propio. Misma validación de ownership que crearFeedback (ver
     * B10 en la auditoría).
     *
     * @param feedbackId ID del feedback a eliminar
     * @param email Email del usuario autenticado (jugador)
     */
    public void eliminarFeedback(Long feedbackId, String email) {
        log.info("Iniciando eliminación de feedback. Feedback: {}, Email: {}", feedbackId, email);

        Feedback feedback = feedbackRepository.findByIdConReservaYJugador(feedbackId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback no encontrado"));
        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);
        validarEsElJugador(feedback.getReserva(), usuarioAutenticado);

        feedbackRepository.delete(feedback);
        log.info("Feedback eliminado con éxito. ID: {}", feedbackId);

        invalidarFichaDe(feedback.getReserva());
    }

    /**
     * Lista los comentarios de un establecimiento, para que el dueño/administrador/
     * empleado autorizado los revise y decida cuál fijar como destacado.
     */
    @Transactional(readOnly = true)
    public Page<FeedbackResponse> obtenerFeedbacksDeEstablecimiento(Long estId, Pageable pageable, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(estId);
        autorizacionEmpleadoService.validarAccion(establecimiento, email, PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO);

        return feedbackRepository.findByEstablecimientoId(estId, pageable)
                .map(feedbackMapper::mapToResponse);
    }

    /**
     * Fija un comentario como destacado. Solo puede haber un comentario destacado por
     * establecimiento a la vez: si había otro, se desfija automáticamente.
     */
    public FeedbackResponse fijarComentario(Long feedbackId, String email) {
        log.info("Iniciando fijado de comentario destacado. Feedback: {}, Email: {}", feedbackId, email);

        Feedback feedback = buscarFeedbackPorId(feedbackId);
        Establecimiento establecimiento = feedback.getReserva().getCancha().getEstablecimiento();
        autorizacionEmpleadoService.validarAccion(establecimiento, email, PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO);

        feedbackRepository.findDestacadoByEstablecimientoId(establecimiento.getId())
                .filter(destacadoActual -> !destacadoActual.getId().equals(feedback.getId()))
                .ifPresent(destacadoActual -> {
                    destacadoActual.setDestacado(false);
                    feedbackRepository.save(destacadoActual);
                });

        feedback.setDestacado(true);
        Feedback feedbackActualizado = feedbackRepository.save(feedback);
        log.info("Comentario fijado con éxito. Feedback: {}, Establecimiento: {}", feedbackId, establecimiento.getId());

        complejoDetalleCache.invalidarPorEstablecimientoId(establecimiento.getId());

        return feedbackMapper.mapToResponse(feedbackActualizado);
    }

    /**
     * Quita el estado de destacado de un comentario.
     */
    public FeedbackResponse quitarComentarioDestacado(Long feedbackId, String email) {
        log.info("Iniciando remoción de comentario destacado. Feedback: {}, Email: {}", feedbackId, email);

        Feedback feedback = buscarFeedbackPorId(feedbackId);
        Establecimiento establecimiento = feedback.getReserva().getCancha().getEstablecimiento();
        autorizacionEmpleadoService.validarAccion(establecimiento, email, PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO);

        feedback.setDestacado(false);
        Feedback feedbackActualizado = feedbackRepository.save(feedback);
        log.info("Comentario destacado removido con éxito. Feedback: {}", feedbackId);

        complejoDetalleCache.invalidarPorEstablecimientoId(establecimiento.getId());

        return feedbackMapper.mapToResponse(feedbackActualizado);
    }

    /**
     * El establecimiento de un feedback se alcanza por reserva -> cancha -> establecimiento.
     * Ambas relaciones son LAZY, así que esto sólo es válido con la sesión abierta: se llama
     * siempre dentro del método transaccional, nunca desde el callback de after-commit (el
     * slug ya quedó resuelto para entonces).
     */
    private void invalidarFichaDe(Reserva reserva) {
        complejoDetalleCache.invalidarPorEstablecimientoId(
                reserva.getCancha().getEstablecimiento().getId());
    }

    private void validarEsElJugador(Reserva reserva, Usuario usuarioAutenticado) {
        boolean esElJugador = reserva.getJugador() != null && reserva.getJugador().getId().equals(usuarioAutenticado.getId());
        if (!esElJugador) {
            log.warn("Acceso denegado sobre feedback. Usuario: {}, Reserva: {}", usuarioAutenticado.getId(), reserva.getId());
            throw new AccessDeniedException("No está autorizado para operar sobre el feedback de esta reserva");
        }
    }

    private Usuario buscarUsuarioPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private Feedback buscarFeedbackPorId(Long id) {
        return feedbackRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Feedback no encontrado"));
    }
}
