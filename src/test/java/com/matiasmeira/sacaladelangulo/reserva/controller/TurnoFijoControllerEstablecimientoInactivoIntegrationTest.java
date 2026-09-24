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
import com.matiasmeira.sacaladelangulo.reserva.dto.EditarClienteTurnoFijoRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.TurnoFijoRepository;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cierra la tercera brecha del mismo tipo (ver EstablecimientoOperativoGuard): crear/renovar
 * (ambos delegan en TurnoFijoService.crearInterno) generaban reservas nuevas sin validar el
 * establecimiento, solo la cancha. cancelar y editarCliente, en cambio, operan sobre
 * ocurrencias YA creadas y a propósito siguen funcionando con el establecimiento
 * deshabilitado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-turnofijo-establecimiento-inactivo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("POST /api/v1/turnos-fijos(/renovar|/cancelar|/cliente) - establecimiento deshabilitado")
class TurnoFijoControllerEstablecimientoInactivoIntegrationTest {

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

    @Autowired
    private TurnoFijoRepository turnoFijoRepository;

    @Autowired
    private ReservaRepository reservaRepository;

    private Cancha ultimaCanchaCreada;

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

    /** Período de un solo día (mañana): exactamente 1 ocurrencia, sin depender de qué día es "hoy". */
    private ReservaSemanalRequest requestUnaOcurrencia() {
        LocalDate maniana = LocalDate.now().plusDays(1);
        return new ReservaSemanalRequest(
                ultimaCanchaCreada.getId(), maniana, maniana, maniana.getDayOfWeek(),
                LocalTime.of(10, 0), LocalTime.of(11, 0), Deporte.FUTBOL_5,
                null, "Grupo Test", "1122334455");
    }

    @Test
    @WithMockUser(username = "dueno-crear-a@test.com", roles = "OWNER")
    @DisplayName("crear sobre establecimiento inactivo -> 400 explícito, no persiste ni la regla ni ninguna ocurrencia")
    void crear_EstablecimientoInactivo_NoPersisteNada() throws Exception {
        Usuario dueno = crearUsuario("dueno-crear-a@test.com", Role.OWNER);
        crearEstablecimientoConCancha("complejo-turnofijo-crear-a", dueno, false);

        long turnosAntes = turnoFijoRepository.count();
        long reservasAntes = reservaRepository.count();

        mockMvc.perform(post("/api/v1/turnos-fijos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestUnaOcurrencia())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Este establecimiento está deshabilitado. No se pueden cargar reservas nuevas mientras esté así."));

        assertEquals(turnosAntes, turnoFijoRepository.count(), "no debe persistir la regla");
        assertEquals(reservasAntes, reservaRepository.count(), "no debe persistir ninguna ocurrencia");
    }

    @Test
    @WithMockUser(username = "dueno-renovar@test.com", roles = "OWNER")
    @DisplayName("renovar sobre establecimiento inactivo -> 400 explícito (mismo gate que crear, vía crearInterno)")
    void renovar_EstablecimientoInactivo_Devuelve400() throws Exception {
        Usuario dueno = crearUsuario("dueno-renovar@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-turnofijo-renovar", dueno, true);

        String body = mockMvc.perform(post("/api/v1/turnos-fijos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestUnaOcurrencia())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long turnoFijoId = objectMapper.readTree(body).get("id").asLong();

        establecimiento.setIsActive(false);
        establecimientoRepository.save(establecimiento);

        long turnosAntes = turnoFijoRepository.count();

        mockMvc.perform(post("/api/v1/turnos-fijos/" + turnoFijoId + "/renovar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Este establecimiento está deshabilitado. No se pueden cargar reservas nuevas mientras esté así."));

        assertEquals(turnosAntes, turnoFijoRepository.count(), "no debe persistir la serie renovada");
    }

    @Test
    @WithMockUser(username = "dueno-cancelar@test.com", roles = "OWNER")
    @DisplayName("cancelar de un turno fijo existente sigue funcionando con el establecimiento inactivo")
    void cancelar_EstablecimientoInactivo_SigueFuncionando() throws Exception {
        Usuario dueno = crearUsuario("dueno-cancelar@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-turnofijo-cancelar", dueno, true);

        String body = mockMvc.perform(post("/api/v1/turnos-fijos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestUnaOcurrencia())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long turnoFijoId = objectMapper.readTree(body).get("id").asLong();

        establecimiento.setIsActive(false);
        establecimientoRepository.save(establecimiento);

        mockMvc.perform(post("/api/v1/turnos-fijos/" + turnoFijoId + "/cancelar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceladas").value(1));
    }

    @Test
    @WithMockUser(username = "dueno-cliente@test.com", roles = "OWNER")
    @DisplayName("editarCliente de un turno fijo existente sigue funcionando con el establecimiento inactivo")
    void editarCliente_EstablecimientoInactivo_SigueFuncionando() throws Exception {
        Usuario dueno = crearUsuario("dueno-cliente@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-turnofijo-cliente", dueno, true);

        String body = mockMvc.perform(post("/api/v1/turnos-fijos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestUnaOcurrencia())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long turnoFijoId = objectMapper.readTree(body).get("id").asLong();

        establecimiento.setIsActive(false);
        establecimientoRepository.save(establecimiento);

        EditarClienteTurnoFijoRequest request = new EditarClienteTurnoFijoRequest("Nuevo Nombre", "1155556666");

        mockMvc.perform(patch("/api/v1/turnos-fijos/" + turnoFijoId + "/cliente")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreClienteManual").value("Nuevo Nombre"));
    }
}
