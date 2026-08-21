package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET .../publico/complejos/{slug} serializa establecimiento.servicios y
 * establecimiento.fotos (ambas @ElementCollection, FetchType.LAZY por default) fuera de la
 * transacción de ComplejoPublicoService (spring.jpa.open-in-view=false): sin materializarlas
 * dentro de ComplejoPublicoService#obtenerDetalle, cualquier llamada real tira
 * LazyInitializationException. A diferencia de ComplejoPublicoControllerIntegrationTest (que
 * es @Transactional a nivel de clase y por eso mantiene una única sesión de Hibernate abierta
 * durante todo el test, escondiendo el bug "por accidente"), este test NO usa @Transactional:
 * el seed se commitea de verdad y la request HTTP corre en su propia sesión, separada de la
 * de seeding. Mismo patrón que EmpleadoControllerListarTest para el mismo tipo de bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-publico-detalle;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/publico/complejos/{slug} - sin @Transactional (no debe esconder LazyInitializationException)")
class ComplejoPublicoControllerDetalleNoTransactionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Test
    @DisplayName("anonimo_ObtieneDetalle_ConServiciosYFotosSerializadosSinLazyInitializationException")
    void anonimo_ObtieneDetalle_ConServiciosYFotosSerializadosSinLazyInitializationException() throws Exception {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno@publico-detalle-test.com")
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Detalle Test")
                .direccion("Calle Falsa 456")
                .slug("complejo-detalle-no-tx")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .servicios(Set.of(Servicio.PARRILLA, Servicio.WIFI))
                .fotos(new java.util.ArrayList<>(List.of(FotoEstablecimiento.builder()
                        .url("https://cdn.example.com/foto1.jpg")
                        .fileId("file_seed_1")
                        .build())))
                .build());

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-detalle-no-tx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("complejo-detalle-no-tx"))
                .andExpect(jsonPath("$.servicios", org.hamcrest.Matchers.containsInAnyOrder("PARRILLA", "WIFI")))
                .andExpect(jsonPath("$.fotos[0]").value("https://cdn.example.com/foto1.jpg"));
    }
}
