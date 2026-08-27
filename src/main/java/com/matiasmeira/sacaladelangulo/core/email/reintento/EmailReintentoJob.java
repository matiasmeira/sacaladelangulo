package com.matiasmeira.sacaladelangulo.core.email.reintento;

import com.matiasmeira.sacaladelangulo.core.email.EmailTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reintenta periódicamente los emails que quedaron encolados por
 * EmailServiceConReintentos. De instancia única, igual que ReservaExpiracionService y el
 * rate limiter: si en el futuro se escala horizontalmente, esto necesita un lock
 * compartido para que dos instancias no manden el mismo email dos veces.
 *
 * <p>Usa EmailTransport y NO EmailService: pasar por el decorador volvería a encolar lo
 * que ya está encolado, y el contador de intentos nunca avanzaría.
 *
 * <p>No corre dentro de una transacción: manda de a un email por vez y confirma cada
 * resultado en su propia transacción corta (ver EmailPendienteRegistro). Una tanda que
 * abarcara todo en una sola transacción mantendría una conexión del pool tomada durante
 * N llamadas de red, y un fallo al final desharía los aciertos del principio.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailReintentoJob {

    private final EmailTransport emailTransport;
    private final EmailPendienteRepository emailPendienteRepository;
    private final EmailPendienteRegistro emailPendienteRegistro;

    @Value("${app.mail.reintento-intentos-maximos:3}")
    private int intentosMaximos;

    @Value("${app.mail.reintento-tamanio-lote:50}")
    private int tamanioLote;

    @Scheduled(
            initialDelayString = "${app.mail.reintento-delay-inicial-millis:60000}",
            fixedDelayString = "${app.mail.reintento-intervalo-millis:300000}")
    public void reintentarPendientes() {
        List<EmailPendiente> lote = emailPendienteRepository.findLoteAReintentar(PageRequest.of(0, tamanioLote));
        if (lote.isEmpty()) {
            return;
        }

        log.info("Reintentando {} email(s) encolado(s)", lote.size());
        int exitosos = 0;

        for (EmailPendiente pendiente : lote) {
            try {
                emailTransport.enviar(pendiente.getDestinatario(), pendiente.getAsunto(), pendiente.getCuerpoHtml());
                emailPendienteRegistro.borrar(pendiente.getId());
                exitosos++;
            } catch (RuntimeException ex) {
                emailPendienteRegistro.registrarFallo(pendiente.getId(), ex.getMessage(), intentosMaximos);
            }
        }

        log.info("Reintento de emails finalizado. Enviados: {}, fallidos: {}", exitosos, lote.size() - exitosos);
    }
}
