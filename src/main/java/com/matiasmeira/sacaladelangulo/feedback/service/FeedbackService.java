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

        boolean esElJugador = reserva.getJugador() != null && reserva.getJugador().getId().equals(usuarioAutenticado.getId());
        if (!esElJugador) {
            log.warn("Acceso denegado a calificar reserva. Usuario: {}, Reserva: {}", usuarioAutenticado.getId(), reservaId);
            throw new AccessDeniedException("No está autorizado para calificar esta reserva");
        }

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

        return feedbackMapper.mapToResponse(feedbackGuardado);
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

        return feedbackMapper.mapToResponse(feedbackActualizado);
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
