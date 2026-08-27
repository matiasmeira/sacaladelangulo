package com.matiasmeira.sacaladelangulo.core.email.reintento;

import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La cola de reintento contra Postgres real, con las migraciones de Flyway aplicadas.
 *
 * <p>Que el contexto arranque ya prueba dos cosas que los tests con H2 no pueden: que el
 * SQL de V20 es válido, y que el mapeo de EmailPendiente coincide con el esquema migrado
 * (la config base corre con ddl-auto=validate). El resto verifica la semántica del
 * constraint único, que es una regla de corrección y no una optimización: si dejara pasar
 * dos filas para el mismo destinatario y asunto, el job podría reintentar un email de
 * verificación viejo cuyo token ya fue reemplazado.
 */
@DisplayName("EmailPendiente - cola de reintento contra Postgres real")
class EmailPendientePostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String DESTINATARIO = "jugador@saque.test";
    private static final String ASUNTO_VERIFICACION = "Verificá tu email";

    @Autowired
    private EmailPendienteRepository emailPendienteRepository;

    @Autowired
    private EmailPendienteRegistro emailPendienteRegistro;

    @Test
    @DisplayName("encolarDosVeces_mismoDestinatarioYAsunto_dejaUnaSolaFilaConElCuerpoNuevo")
    void encolarDosVeces_mismoDestinatarioYAsunto_dejaUnaSolaFilaConElCuerpoNuevo() {
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>token viejo</p>", "503");
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>token nuevo</p>", "503");

        List<EmailPendiente> todos = emailPendienteRepository.findAll();
        assertEquals(1, todos.size(), "El constraint unico deberia mantener una sola fila");
        assertEquals("<p>token nuevo</p>", todos.get(0).getCuerpoHtml(),
                "El reencolado deberia pisar el cuerpo: el viejo lleva un token ya reemplazado");
    }

    @Test
    @DisplayName("encolarDistintosAsuntos_mismoDestinatario_sonFilasIndependientes")
    void encolarDistintosAsuntos_mismoDestinatario_sonFilasIndependientes() {
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>a</p>", null);
        emailPendienteRegistro.encolar(DESTINATARIO, "Bienvenido a Saque", "<p>b</p>", null);

        assertEquals(2, emailPendienteRepository.findAll().size());
    }

    @Test
    @DisplayName("unEnvioNuevoQueFalla_reiniciaElContadorYSacaDeError")
    void unEnvioNuevoQueFalla_reiniciaElContadorYSacaDeError() {
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>a</p>", "503");
        Long id = emailPendienteRepository.findAll().get(0).getId();
        emailPendienteRegistro.registrarFallo(id, "fallo 1", 3);
        emailPendienteRegistro.registrarFallo(id, "fallo 2", 3);
        emailPendienteRegistro.registrarFallo(id, "fallo 3", 3);

        assertEquals(EstadoEmailPendiente.ERROR, emailPendienteRepository.findById(id).orElseThrow().getEstado());

        // El usuario vuelve a pedir el código: es un intento fresco, no la continuación
        // de la tanda anterior.
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>b</p>", "503");

        EmailPendiente reencolado = emailPendienteRepository.findById(id).orElseThrow();
        assertEquals(EstadoEmailPendiente.PENDIENTE, reencolado.getEstado());
        assertEquals(0, reencolado.getIntentos());
    }

    @Test
    @DisplayName("resolver_borraLaFilaEncolada")
    void resolver_borraLaFilaEncolada() {
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>a</p>", null);

        emailPendienteRegistro.resolver(DESTINATARIO, ASUNTO_VERIFICACION);

        assertTrue(emailPendienteRepository.findAll().isEmpty());
    }

    /**
     * El job sólo debe levantar PENDIENTE: una fila en ERROR agotó sus intentos y
     * reintentarla en cada corrida gastaría cuota del proveedor para siempre.
     */
    @Test
    @DisplayName("elLoteAReintentar_excluyeLasFilasEnError")
    void elLoteAReintentar_excluyeLasFilasEnError() {
        emailPendienteRegistro.encolar("agotado@saque.test", ASUNTO_VERIFICACION, "<p>a</p>", null);
        Long idAgotado = emailPendienteRepository.findByDestinatarioAndAsunto("agotado@saque.test", ASUNTO_VERIFICACION)
                .orElseThrow().getId();
        emailPendienteRegistro.registrarFallo(idAgotado, "e", 1);

        emailPendienteRegistro.encolar("vigente@saque.test", ASUNTO_VERIFICACION, "<p>b</p>", null);

        List<EmailPendiente> lote = emailPendienteRepository.findLoteAReintentar(PageRequest.of(0, 50));

        assertEquals(1, lote.size());
        assertEquals("vigente@saque.test", lote.get(0).getDestinatario());
    }

    @Test
    @DisplayName("fechaCreacionSeCompletaSola_alPersistir")
    void fechaCreacionSeCompletaSola_alPersistir() {
        emailPendienteRegistro.encolar(DESTINATARIO, ASUNTO_VERIFICACION, "<p>a</p>", null);

        assertNotNull(emailPendienteRepository.findAll().get(0).getFechaCreacion());
    }
}
