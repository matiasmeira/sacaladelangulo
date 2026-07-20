package com.matiasmeira.sacaladelangulo.feedback.controller;

import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackRequest;
import com.matiasmeira.sacaladelangulo.feedback.dto.FeedbackResponse;
import com.matiasmeira.sacaladelangulo.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para calificaciones y comentarios de reservas.
 */
@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    /**
     * Crea la calificación de una reserva finalizada.
     * Protegido: solo el jugador que jugó la reserva (el service exige esa
     * pertenencia explícitamente; ADMIN no está habilitado porque nunca es el
     * jugador de una reserva ajena, ver M20 en la auditoría).
     */
    @PostMapping("/api/v1/reservas/{reservaId}/feedback")
    @PreAuthorize("hasRole('PLAYER')")
    public ResponseEntity<FeedbackResponse> crearFeedback(
            @PathVariable Long reservaId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid FeedbackRequest request) {
        FeedbackResponse feedback = feedbackService.crearFeedback(reservaId, request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(feedback);
    }

    /**
     * Edita la puntuación/comentario de un feedback propio.
     * Protegido: solo el jugador dueño del feedback (mismo criterio de ownership que
     * crearFeedback, ver B10 en la auditoría).
     */
    @PutMapping("/api/v1/feedback/{feedbackId}")
    @PreAuthorize("hasRole('PLAYER')")
    public ResponseEntity<FeedbackResponse> editarFeedback(
            @PathVariable Long feedbackId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid FeedbackRequest request) {
        return ResponseEntity.ok(feedbackService.editarFeedback(feedbackId, request, userDetails.getUsername()));
    }

    /**
     * Elimina un feedback propio.
     * Protegido: solo el jugador dueño del feedback (mismo criterio de ownership que
     * crearFeedback, ver B10 en la auditoría).
     */
    @DeleteMapping("/api/v1/feedback/{feedbackId}")
    @PreAuthorize("hasRole('PLAYER')")
    public ResponseEntity<Void> eliminarFeedback(
            @PathVariable Long feedbackId,
            @AuthenticationPrincipal UserDetails userDetails) {
        feedbackService.eliminarFeedback(feedbackId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * Lista los comentarios de un establecimiento.
     * Protegido: dueño real del establecimiento, administradores, y empleados con el
     * permiso FIJAR_COMENTARIO_DESTACADO.
     */
    @GetMapping("/api/v1/establecimientos/{estId}/feedback")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<Page<FeedbackResponse>> obtenerFeedbacksDeEstablecimiento(
            @PathVariable Long estId,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(feedbackService.obtenerFeedbacksDeEstablecimiento(estId, pageable, userDetails.getUsername()));
    }

    /**
     * Fija un comentario como destacado para que aparezca en el perfil público del
     * establecimiento. Desfija automáticamente cualquier otro comentario que ya
     * estuviera destacado en ese establecimiento.
     * Protegido: dueño real del establecimiento, administradores, y empleados con el
     * permiso FIJAR_COMENTARIO_DESTACADO.
     */
    @PutMapping("/api/v1/feedback/{feedbackId}/destacar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<FeedbackResponse> fijarComentario(
            @PathVariable Long feedbackId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(feedbackService.fijarComentario(feedbackId, userDetails.getUsername()));
    }

    /**
     * Quita el estado de destacado de un comentario.
     * Protegido: dueño real del establecimiento, administradores, y empleados con el
     * permiso FIJAR_COMENTARIO_DESTACADO.
     */
    @PutMapping("/api/v1/feedback/{feedbackId}/quitar-destacado")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<FeedbackResponse> quitarComentarioDestacado(
            @PathVariable Long feedbackId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(feedbackService.quitarComentarioDestacado(feedbackId, userDetails.getUsername()));
    }
}
