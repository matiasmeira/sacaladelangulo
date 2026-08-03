package com.matiasmeira.sacaladelangulo.mails.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.mails.dto.EnviarOfertaRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Envía en lotes el email de oferta a todos los usuarios con opt-in de marketing.
 * Separado de OfertaMarketingService (que hace la autorización) para que el método
 * @Async se invoque a través del proxy de Spring: la auto-invocación dentro de la misma
 * clase no dispara la ejecución asíncrona (limitación conocida de @Async).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfertaMarketingBatchSender {

    private static final int TAMANIO_PAGINA = 50;

    private final UsuarioRepository usuarioRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    public void enviarEnLotes(EnviarOfertaRequest request) {
        int totalEnviados = 0;
        Pageable pageable = Pageable.ofSize(TAMANIO_PAGINA);
        Page<Usuario> pagina = usuarioRepository.findByAceptaMarketingTrue(pageable);

        while (!pagina.isEmpty()) {
            for (Usuario usuario : pagina.getContent()) {
                String unsubscribeLink = frontendUrl + "/baja-mails?token=" + usuario.getUnsubscribeToken();
                String html = emailRenderer.render("oferta", Map.of(
                        "cuerpoHtml", request.cuerpoHtml(),
                        "unsubscribeLink", unsubscribeLink
                ));
                emailService.enviar(usuario.getEmail(), request.asunto(), html);
                totalEnviados++;
            }

            pageable = pageable.next();
            pagina = usuarioRepository.findByAceptaMarketingTrue(pageable);
        }

        log.info("Broadcast de oferta de marketing finalizado. Total de emails enviados: {}", totalEnviados);
    }
}
