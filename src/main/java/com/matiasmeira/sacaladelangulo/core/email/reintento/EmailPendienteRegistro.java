package com.matiasmeira.sacaladelangulo.core.email.reintento;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escrituras sobre la cola de reintento, en transacciones CORTAS y propias.
 *
 * <p>Es un bean aparte (y no métodos privados del decorador) por dos motivos. El
 * primero: la llamada al proveedor de email es I/O de red y NO puede correr con una
 * transacción abierta — el pool de producción es de 5 conexiones y un proveedor lento las
 * agotaría, con reservas y login encolando detrás (mismo problema que documenta
 * FotoEstablecimientoService con ImageKit). El segundo: Spring no aplica @Transactional en
 * self-invocation, así que llamarlos como métodos privados del propio decorador
 * silenciosamente no abriría ninguna transacción.
 *
 * <p>REQUIRES_NEW en todos los métodos: se los invoca desde listeners @Async que corren
 * después del commit, pero también desde el job de reintento; en ninguno de los dos casos
 * el resultado de la cola debe quedar atado a una transacción de negocio ajena.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailPendienteRegistro {

    private final EmailPendienteRepository emailPendienteRepository;

    /**
     * Encola (o pisa) el email que acaba de fallar. Pisar es deliberado: para un mismo
     * destinatario y asunto, el cuerpo más nuevo es el único vigente — reintentar un email
     * de verificación viejo mandaría un link cuyo token ya fue reemplazado (ver V20).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void encolar(String destinatario, String asunto, String cuerpoHtml, String mensajeError) {
        String errorRecortado = recortar(mensajeError);
        try {
            EmailPendiente pendiente = emailPendienteRepository
                    .findByDestinatarioAndAsunto(destinatario, asunto)
                    .orElseGet(() -> EmailPendiente.builder()
                            .destinatario(destinatario)
                            .asunto(asunto)
                            .build());

            pendiente.setCuerpoHtml(cuerpoHtml);
            // Un envío nuevo que falla reinicia el contador y saca la fila de ERROR: es un
            // intento fresco del usuario (pidió el código de nuevo), no la continuación de
            // la tanda anterior.
            pendiente.setEstado(EstadoEmailPendiente.PENDIENTE);
            pendiente.setIntentos(0);
            pendiente.setUltimoError(errorRecortado);
            emailPendienteRepository.saveAndFlush(pendiente);
        } catch (DataIntegrityViolationException ex) {
            // find + save no es atómico: dos envíos casi simultáneos al mismo destinatario
            // y asunto pueden pasar los dos por el find antes de que cualquiera inserte. El
            // constraint único lo resuelve, y perder el reencolado de uno de los dos no
            // importa: el otro dejó una fila equivalente.
            log.debug("Carrera al encolar el email para {} (ya lo encoló otro hilo)", destinatario);
        }
    }

    /**
     * Un envío exitoso deja sin efecto cualquier fila encolada para el mismo destinatario
     * y asunto: lo que estaba en la cola quedó superado por este envío, y reintentarlo
     * mandaría contenido viejo (un link ya vencido, en el peor caso).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolver(String destinatario, String asunto) {
        emailPendienteRepository.deleteByDestinatarioAndAsunto(destinatario, asunto);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void borrar(Long id) {
        emailPendienteRepository.deleteById(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarFallo(Long id, String mensajeError, int intentosMaximos) {
        emailPendienteRepository.findById(id).ifPresent(pendiente -> {
            pendiente.registrarFallo(mensajeError, intentosMaximos);
            emailPendienteRepository.save(pendiente);
            if (pendiente.getEstado() == EstadoEmailPendiente.ERROR) {
                // ERROR es el final del camino automático: nadie lo va a reintentar solo.
                // Se loguea en ERROR justamente para que dispare una alerta.
                log.error("Email a {} agotó los {} intentos y queda para intervención manual. Asunto: {}. Último error: {}",
                        pendiente.getDestinatario(), intentosMaximos, pendiente.getAsunto(), pendiente.getUltimoError());
            }
        });
    }

    private String recortar(String mensaje) {
        if (mensaje == null) {
            return null;
        }
        return mensaje.substring(0, Math.min(mensaje.length(), EmailPendiente.LARGO_MAXIMO_ERROR));
    }
}
