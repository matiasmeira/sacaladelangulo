package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-auditoria-degradacion-plan;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("AuditoriaDegradacionPlan - persistencia")
class AuditoriaDegradacionPlanTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;

    @Test
    @DisplayName("guardarYReleer_ConservaUsuarioFechaYDetalle")
    void guardarYReleer_ConservaUsuarioFechaYDetalle() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("degradado@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        AuditoriaDegradacionPlan registro = auditoriaDegradacionPlanRepository.save(AuditoriaDegradacionPlan.builder()
                .usuario(usuario)
                .fechaHora(ahora)
                .detalle("Prueba vencida")
                .build());

        AuditoriaDegradacionPlan recargado = auditoriaDegradacionPlanRepository.findById(registro.getId()).orElseThrow();

        assertEquals(usuario.getId(), recargado.getUsuario().getId());
        assertEquals(ahora, recargado.getFechaHora());
        assertEquals("Prueba vencida", recargado.getDetalle());
    }
}
