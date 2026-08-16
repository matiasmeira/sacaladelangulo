package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Envía los emails de notificación cuando una Reserva queda CONFIRMADA (ver
 * ReservaConfirmadaEvent y los 3 puntos de publicación en ReservaService). AFTER_COMMIT +
 * @Async por el mismo motivo que RecuperacionPasswordEmailListener: el cambio de estado ya
 * quedó persistido antes de intentar el envío, y no se retiene la conexión de base de datos
 * durante la latencia de una llamada de red externa. El evento solo lleva el ID de la
 * reserva porque @Async corre en un hilo/persistence-context distinto al de la transacción
 * original: la entidad recibida ahí estaría detached y sus asociaciones LAZY no serían
 * navegables, así que se vuelve a cargar acá con findByIdConEstablecimientoYDueno (que ya
 * trae cancha, establecimiento, dueño y jugador con JOIN FETCH).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservaNotificacionListener {

    private static final String ASUNTO_JUGADOR = "Tu reserva fue confirmada";
    private static final String ASUNTO_DUENO = "Nueva reserva confirmada en tu establecimiento";
    private static final String ASUNTO_CANCELACION_JUGADOR = "Cancelaste tu reserva";
    private static final String ASUNTO_LIBERACION_DUENO = "Se liberó una cancha";
    private static final String ASUNTO_CANCELACION_POR_ESTABLECIMIENTO = "Tu reserva fue cancelada";
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ReservaRepository reservaRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarNotificacionesConfirmacion(ReservaConfirmadaEvent evento) {
        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(evento.reservaId()).orElse(null);
        if (reserva == null) {
            log.warn("No se encontró la reserva {} al intentar enviar las notificaciones de confirmación", evento.reservaId());
            return;
        }

        Map<String, Object> modelo = construirModeloBase(reserva);

        Usuario jugador = reserva.getJugador();
        if (puedeNotificar(jugador)) {
            String htmlJugador = emailRenderer.render("reserva-confirmada", modelo);
            emailService.enviar(jugador.getEmail(), ASUNTO_JUGADOR, htmlJugador);
        }

        Map<String, Object> modeloDueno = new HashMap<>(modelo);
        modeloDueno.put("nombreCliente", jugador != null ? jugador.getNombre() : reserva.getNombreClienteManual());
        String htmlDueno = emailRenderer.render("reserva-nueva-dueno", modeloDueno);
        String emailDueno = reserva.getCancha().getEstablecimiento().getDueno().getEmail();
        emailService.enviar(emailDueno, ASUNTO_DUENO, htmlDueno);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarNotificacionesCancelacion(ReservaCanceladaEvent evento) {
        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(evento.reservaId()).orElse(null);
        if (reserva == null) {
            log.warn("No se encontró la reserva {} al intentar enviar las notificaciones de cancelación", evento.reservaId());
            return;
        }

        boolean esElJugador = reserva.getJugador() != null && reserva.getJugador().getId().equals(evento.actorId());

        Map<String, Object> modelo = construirModeloBase(reserva);

        if (esElJugador) {
            Usuario jugador = reserva.getJugador();
            if (puedeNotificar(jugador)) {
                String htmlJugador = emailRenderer.render("reserva-cancelada-jugador", modelo);
                emailService.enviar(jugador.getEmail(), ASUNTO_CANCELACION_JUGADOR, htmlJugador);
            }

            String htmlDueno = emailRenderer.render("reserva-liberada-dueno", modelo);
            String emailDueno = reserva.getCancha().getEstablecimiento().getDueno().getEmail();
            emailService.enviar(emailDueno, ASUNTO_LIBERACION_DUENO, htmlDueno);
        } else {
            Usuario jugador = reserva.getJugador();
            if (puedeNotificar(jugador)) {
                boolean huboSeña = reserva.getSenaPagada() != null && reserva.getSenaPagada().compareTo(BigDecimal.ZERO) > 0;
                Map<String, Object> modeloJugador = new HashMap<>(modelo);
                modeloJugador.put("huboSeña", huboSeña);
                String htmlJugador = emailRenderer.render("reserva-cancelada", modeloJugador);
                emailService.enviar(jugador.getEmail(), ASUNTO_CANCELACION_POR_ESTABLECIMIENTO, htmlJugador);
            }
        }
    }

    /**
     * Corta el envío si el jugador ya fue anonimizado (ver UsuarioEliminacionService): su
     * email pasa a ser un placeholder @saque.deleted que no existe, y un bounce contra un
     * dominio inexistente pega directo contra la reputación de envío del dominio real en
     * Resend.
     */
    private boolean puedeNotificar(Usuario usuario) {
        return usuario != null && usuario.getDeletedAt() == null && StringUtils.hasText(usuario.getEmail());
    }

    private Map<String, Object> construirModeloBase(Reserva reserva) {
        Map<String, Object> modelo = new HashMap<>();
        modelo.put("establecimientoNombre", reserva.getCancha().getEstablecimiento().getNombre());
        modelo.put("canchaNombre", reserva.getCancha().getNombre());
        modelo.put("deporte", reserva.getDeporteSeleccionado());
        modelo.put("fecha", reserva.getFechaHoraInicio().format(FORMATO_FECHA));
        modelo.put("horaInicio", reserva.getFechaHoraInicio().format(FORMATO_HORA));
        modelo.put("horaFin", reserva.getFechaHoraFin().format(FORMATO_HORA));
        modelo.put("precioTotal", reserva.getPrecioTotal());
        modelo.put("senaPagada", reserva.getSenaPagada());
        return modelo;
    }
}
