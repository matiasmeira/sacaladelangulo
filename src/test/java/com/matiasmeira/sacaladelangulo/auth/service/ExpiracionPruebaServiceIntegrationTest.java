package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Prueba el flujo completo de ExpiracionPruebaService contra un Spring context real: que el
 * cambio de plan se persista, que la auditoría quede registrada, que el email de aviso salga
 * recién AFTER_COMMIT (nunca con una transacción de base todavía abierta) y que los
 * establecimientos del usuario NO se toquen. Necesita un contenedor real de Spring, no
 * new ExpiracionPruebaService(...): @TransactionalEventListener(AFTER_COMMIT) sólo dispara a
 * través de un commit real gestionado por el contenedor (mismo motivo que
 * FotoEstablecimientoServiceFronterasTransaccionalesTest).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-expiracion-prueba;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "app.suscripcion.expiracion-lote=2"
})
@DisplayName("ExpiracionPruebaService - Flujo completo de degradación TRIAL -> FREE")
class ExpiracionPruebaServiceIntegrationTest {

    @Autowired
    private ExpiracionPruebaService expiracionPruebaService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;

    @MockitoBean
    private EmailService emailService;

    @Test
    @DisplayName("degradarPruebasVencidas_UsuarioTrialVencido_PasaAFreeAuditaYMandaElEmailSoloAfterCommit")
    void degradarPruebasVencidas_UsuarioTrialVencido_PasaAFreeAuditaYMandaElEmailSoloAfterCommit() {
        Usuario dueno = usuarioRepository.save(usuarioTrialVencido("dueno-vencido@test.com"));

        AtomicBoolean transaccionActivaAlEnviarEmail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transaccionActivaAlEnviarEmail.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(emailService).enviar(any(), any(), any());

        expiracionPruebaService.degradarPruebasVencidas();

        verify(emailService, timeout(5000)).enviar(eq(dueno.getEmail()), any(), any());
        assertThat(transaccionActivaAlEnviarEmail.get())
                .as("el email debe salir solo después del commit, sin una transacción de base todavía abierta")
                .isFalse();

        Usuario recargado = usuarioRepository.findById(dueno.getId()).orElseThrow();
        assertThat(recargado.getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);

        assertThat(auditoriaDegradacionPlanRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("degradarPruebasVencidas_DuenoConEstablecimientoActivo_NoLoDesactiva")
    void degradarPruebasVencidas_DuenoConEstablecimientoActivo_NoLoDesactiva() {
        Usuario dueno = usuarioRepository.save(usuarioTrialVencido("dueno-con-complejo@test.com"));
        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Test")
                .direccion("Calle 1")
                .slug("complejo-expiracion-test")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        expiracionPruebaService.degradarPruebasVencidas();

        verify(emailService, timeout(5000)).enviar(eq(dueno.getEmail()), any(), any());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_DegradaATodos")
    void degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_DegradaATodos() {
        Usuario u1 = usuarioRepository.save(usuarioTrialVencido("u1@test.com"));
        Usuario u2 = usuarioRepository.save(usuarioTrialVencido("u2@test.com"));
        Usuario u3 = usuarioRepository.save(usuarioTrialVencido("u3@test.com"));

        expiracionPruebaService.degradarPruebasVencidas();

        assertThat(usuarioRepository.findById(u1.getId()).orElseThrow().getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
        assertThat(usuarioRepository.findById(u2.getId()).orElseThrow().getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
        assertThat(usuarioRepository.findById(u3.getId()).orElseThrow().getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
    }

    private Usuario usuarioTrialVencido(String email) {
        return Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueño de prueba")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .fechaFinPrueba(LocalDateTime.now().minusDays(1))
                .build();
    }
}
