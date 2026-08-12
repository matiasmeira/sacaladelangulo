package com.matiasmeira.sacaladelangulo.buffet.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-venta-buffet-listar;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/buffet/ventas")
class VentaBuffetControllerListarTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private VentaRepository ventaRepository;

    @Autowired
    private JwtService jwtService;

    private Usuario crearDueno(String email) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueño Test")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    private Establecimiento crearEstablecimiento(String slug, Usuario dueno) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    private void crearVenta(Establecimiento establecimiento, EstadoVenta estado, LocalDateTime fechaHora, BigDecimal total) {
        ventaRepository.save(Venta.builder()
                .establecimiento(establecimiento)
                .fechaHora(fechaHora)
                .total(total)
                .estado(estado)
                .metodoPago(MetodoPago.EFECTIVO)
                .build());
    }

    @Test
    @DisplayName("dueno_SinEstado_ListaConfirmadaYCanceladaOrdenadasPorFechaHoraDesc")
    void dueno_SinEstado_ListaConfirmadaYCanceladaOrdenadasPorFechaHoraDesc() throws Exception {
        Usuario dueno = crearDueno("dueno-listar1@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-1", dueno);
        crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        crearVenta(establecimiento, EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));
        crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 2, 1, 10, 0), BigDecimal.valueOf(3000));

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(dueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].estado").value("CANCELADA"))
                .andExpect(jsonPath("$.content[1].estado").value("CONFIRMADA"));
    }

    @Test
    @DisplayName("dueno_ConEstadoCancelada_FiltraSoloCanceladas")
    void dueno_ConEstadoCancelada_FiltraSoloCanceladas() throws Exception {
        Usuario dueno = crearDueno("dueno-listar2@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-2", dueno);
        crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        crearVenta(establecimiento, EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(dueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .param("estado", "CANCELADA")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].estado").value("CANCELADA"));
    }

    @Test
    @DisplayName("dueno_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos")
    void dueno_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos() throws Exception {
        Usuario dueno = crearDueno("dueno-listar3@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-3", dueno);
        for (int i = 1; i <= 25; i++) {
            crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, i, 10, 0), BigDecimal.valueOf(100L * i));
        }

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(dueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(20));
    }

    @Test
    @DisplayName("ownerDeOtroEstablecimiento_Devuelve403")
    void ownerDeOtroEstablecimiento_Devuelve403() throws Exception {
        Usuario dueno = crearDueno("dueno-listar4@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-4", dueno);

        Usuario otroDueno = crearDueno("otro-dueno-listar4@test.com");
        crearEstablecimiento("otro-establecimiento-listar4", otroDueno);

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(otroDueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", "1")
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31"))
                .andExpect(status().isUnauthorized());
    }
}
