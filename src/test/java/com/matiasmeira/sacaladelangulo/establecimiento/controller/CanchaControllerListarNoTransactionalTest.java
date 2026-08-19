package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET .../establecimientos/{id}/canchas serializa duracionesPermitidas y
 * preciosPorDuracion (ambas @ElementCollection, LAZY por default) fuera de la transacción de
 * CanchaService (spring.jpa.open-in-view=false). CanchaService#mapToResponse las pasaba por
 * referencia al DTO en vez de copiarlas, así que llegaban al serializador como proxies con la
 * sesión ya cerrada y toda llamada real devolvía 500 con LazyInitializationException.
 *
 * El síntoma era engañoso: tarifas y canchasFisicas no fallaban porque el mapper les hace
 * .stream() y eso las inicializa; sólo reventaban las dos colecciones que nadie recorría.
 *
 * Este test NO usa @Transactional a propósito: con la clase transaccional habría una única
 * sesión de Hibernate abierta durante todo el test y el bug quedaría escondido. Mismo patrón
 * que ComplejoPublicoControllerDetalleNoTransactionalTest y EmpleadoControllerListarTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-canchas-listar;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/establecimientos/{id}/canchas - sin @Transactional (no debe esconder LazyInitializationException)")
class CanchaControllerListarNoTransactionalTest {

    private static final String EMAIL_DUENO = "dueno@canchas-listar-test.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    @Test
    @WithMockUser(username = EMAIL_DUENO, roles = "OWNER")
    @DisplayName("dueno_ListaCanchas_ConColeccionesSerializadasSinLazyInitializationException")
    void dueno_ListaCanchas_ConColeccionesSerializadasSinLazyInitializationException() throws Exception {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email(EMAIL_DUENO)
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Canchas Test")
                .direccion("Calle Falsa 789")
                .slug("complejo-canchas-listar")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build());

        canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .establecimiento(establecimiento)
                .precioBase(new BigDecimal("18000"))
                .montoSena(new BigDecimal("6000"))
                .duracionesPermitidas(List.of(60, 90))
                .preciosPorDuracion(Map.of(60, new BigDecimal("18000"), 90, new BigDecimal("25000")))
                .permiteInicioMediaHora(true)
                .build());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/canchas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Cancha 1"))
                .andExpect(jsonPath("$[0].deportes[0]").value("FUTBOL"))
                // Las dos que reventaban:
                .andExpect(jsonPath("$[0].duracionesPermitidas",
                        org.hamcrest.Matchers.containsInAnyOrder(60, 90)))
                .andExpect(jsonPath("$[0].preciosPorDuracion.60").value(18000));
    }
}
