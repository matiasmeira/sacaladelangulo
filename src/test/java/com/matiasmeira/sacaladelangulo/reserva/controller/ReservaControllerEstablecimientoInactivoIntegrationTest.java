package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cierra la brecha por la que un establecimiento deshabilitado (isActive=false) seguía
 * aceptando reservas: ni crearReserva (jugador) ni crearReservaManual (panel) validaban el
 * establecimiento, solo la cancha (ver EstablecimientoOperativoGuard). confirmarReserva, en
 * cambio, opera sobre una reserva YA creada -no crea compromiso nuevo- y a propósito no
 * lleva el gate: no debe romperse por una deshabilitación posterior a la creación.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-reserva-establecimiento-inactivo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("POST /api/v1/reservas(/manual) - establecimiento deshabilitado")
class ReservaControllerEstablecimientoInactivoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    private Usuario crearUsuario(String email, Role rol) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario Test")
                .rol(rol)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
    }

    /** Horario de atención los 7 días de la semana, para no depender de qué día es "hoy". */
    private Establecimiento crearEstablecimientoConCancha(String slug, Usuario dueno, boolean establecimientoActivo) {
        // Este test suite aísla la dimensión isActive: siempre VERIFICADO para que
        // el gate que se ejercite acá sea el de "deshabilitado", no el de verificación.
        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(establecimientoActivo)
                .dueno(dueno)));

        Establecimiento establecimientoFinal = establecimiento;
        List<HorarioAtencion> horarios = Arrays.stream(DayOfWeek.values())
                .map(dia -> HorarioAtencion.builder()
                        .diaSemana(dia)
                        .horaApertura(LocalTime.of(6, 0))
                        .horaCierre(LocalTime.of(23, 0))
                        .establecimiento(establecimientoFinal)
                        .build())
                .collect(Collectors.toList());
        establecimiento.setHorariosAtencion(new ArrayList<>(horarios));
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .build());
        this.ultimaCanchaCreada = cancha;
        return establecimiento;
    }

    private Cancha ultimaCanchaCreada;

    private LocalDateTime pasadoManiana10hs() {
        return LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    @Test
    @WithMockUser(username = "jugador-crear-a@test.com", roles = "PLAYER")
    @DisplayName("crearReserva sobre establecimiento inactivo -> 404 opaco (mismo tratamiento que 'no existe')")
    void crearReserva_EstablecimientoInactivo_Devuelve404() throws Exception {
        Usuario dueno = crearUsuario("dueno-crear-a@test.com", Role.OWNER);
        crearEstablecimientoConCancha("complejo-crear-a", dueno, false);
        crearUsuario("jugador-crear-a@test.com", Role.PLAYER);

        LocalDateTime inicio = pasadoManiana10hs();
        ReservaRequest request = new ReservaRequest(ultimaCanchaCreada.getId(), inicio, inicio.plusHours(1), Deporte.FUTBOL_5);

        mockMvc.perform(post("/api/v1/reservas")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @WithMockUser(username = "dueno-crear-b@test.com", roles = "OWNER")
    @DisplayName("crearReservaManual sobre establecimiento inactivo -> 400 explícito sobre el establecimiento")
    void crearReservaManual_EstablecimientoInactivo_Devuelve400() throws Exception {
        Usuario dueno = crearUsuario("dueno-crear-b@test.com", Role.OWNER);
        crearEstablecimientoConCancha("complejo-crear-b", dueno, false);

        LocalDateTime inicio = pasadoManiana10hs();
        ReservaManualRequest request = new ReservaManualRequest(
                ultimaCanchaCreada.getId(), inicio, inicio.plusHours(1), Deporte.FUTBOL_5,
                "Cliente Mostrador", "1122334455", false);

        mockMvc.perform(post("/api/v1/reservas/manual")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Este establecimiento está deshabilitado. No se pueden cargar reservas nuevas mientras esté así."));
    }

    @Test
    @DisplayName("confirmarReserva de una prereserva ya creada sigue funcionando aunque el establecimiento se deshabilite después")
    void confirmarReserva_EstablecimientoDeshabilitadoDespuesDeCrearLaReserva_SigueFuncionando() throws Exception {
        Usuario dueno = crearUsuario("dueno-confirmar@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-confirmar", dueno, true);
        Usuario jugador = crearUsuario("jugador-confirmar@test.com", Role.PLAYER);

        LocalDateTime inicio = pasadoManiana10hs();
        ReservaRequest request = new ReservaRequest(ultimaCanchaCreada.getId(), inicio, inicio.plusHours(1), Deporte.FUTBOL_5);

        // La crea el jugador con el establecimiento todavía activo.
        String body = mockMvc.perform(post("/api/v1/reservas")
                        .with(user(jugador.getEmail()).roles("PLAYER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long reservaId = objectMapper.readTree(body).get("id").asLong();

        // El establecimiento se deshabilita DESPUÉS de que la prereserva ya existe.
        establecimiento.setIsActive(false);
        establecimientoRepository.save(establecimiento);

        mockMvc.perform(put("/api/v1/reservas/" + reservaId + "/confirmar")
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));
    }
}
