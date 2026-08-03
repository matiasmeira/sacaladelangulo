package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Envía el email de aviso de fin de prueba gratuita (ver AvisoFinPruebaService).
 * AFTER_COMMIT + @Async por el mismo motivo que RegistroVerificacionEmailListener: el flag
 * de aviso enviado ya quedó persistido antes de intentar el envío, y no se retiene la
 * conexión de base de datos durante la latencia de una llamada de red externa. El evento
 * solo lleva el ID del usuario porque @Async corre en un hilo/persistence-context distinto
 * al de la transacción original, así que la entidad se vuelve a cargar acá.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvisoFinPruebaEmailListener {

    private final UsuarioRepository usuarioRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarAvisoFinPrueba(AvisoFinPruebaEvent evento) {
        Usuario usuario = usuarioRepository.findById(evento.usuarioId()).orElse(null);
        if (usuario == null) {
            log.warn("No se encontró el usuario {} al intentar enviar el aviso de fin de prueba", evento.usuarioId());
            return;
        }

        String html = emailRenderer.render("fin-prueba", Map.of(
                "nombre", usuario.getNombre(),
                "diasRestantes", evento.diasRestantes()
        ));

        String dias = evento.diasRestantes() == 1 ? "día" : "días";
        String asunto = "Tu prueba gratuita termina en " + evento.diasRestantes() + " " + dias;
        emailService.enviar(usuario.getEmail(), asunto, html);
    }
}
