package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.TurnoFijoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Análoga a TurnoFijoControllerEstablecimientoInactivoIntegrationTest pero para la otra
 * mitad del criterio de EstablecimientoOperativoGuard: estadoVerificacion. cancelar opera
 * sobre una serie YA creada y a propósito no lleva el gate: sigue funcionando aunque el
 * establecimiento nunca haya sido verificado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-turnofijo-establecimiento-no-verificado;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("POST /api/v1/turnos-fijos(/cancelar) - establecimiento no verificado")
class TurnoFijoControllerEstablecimientoNoVerificadoIntegrationTest {

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
    private Establecimiento crearEstablecimientoConCancha(String slug, Usuario dueno, EstadoVerificacion estadoVerificacion) {
        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .estadoVerificacion(estadoVerificacion)
                .dueno(dueno)
                .build());

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

    @ParameterizedTest(name = "crear sobre establecimiento {0} -> 400 explícito sobre la verificación, no persiste nada")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("crear_EstablecimientoNoVerificado_NoPersisteNada")
    void crear_EstablecimientoNoVerificado_NoPersisteNada(EstadoVerificacion estadoVerificacion) throws Exception {
        String emailDueno = "dueno-crear-a-" + estadoVerificacion + "@test.com";
        Usuario dueno = crearUsuario(emailDueno, Role.OWNER);
        crearEstablecimientoConCancha("complejo-turnofijo-crear-a-" + estadoVerificacion.name().toLowerCase(), dueno, estadoVerificacion);

        long turnosAntes = turnoFijoRepository.count();
        long reservasAntes = reservaRepository.count();

        mockMvc.perform(post("/api/v1/turnos-fijos")
                        .with(user(emailDueno).roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestUnaOcurrencia())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "Este establecimiento todavía no está verificado. No se pueden cargar reservas nuevas hasta que se apruebe la verificación."));

        assertEquals(turnosAntes, turnoFijoRepository.count(), "no debe persistir la regla");
        assertEquals(reservasAntes, reservaRepository.count(), "no debe persistir ninguna ocurrencia");
    }

    @Test
    @WithMockUser(username = "dueno-cancelar-nv@test.com", roles = "OWNER")
    @DisplayName("cancelar de un turno fijo existente sigue funcionando con el establecimiento sin verificar")
    void cancelar_EstablecimientoNoVerificado_SigueFuncionando() throws Exception {
        Usuario dueno = crearUsuario("dueno-cancelar-nv@test.com", Role.OWNER);
        // Se crea con VERIFICADO para poder sembrar la serie por el endpoint real, y
        // recién después se le retira la verificación: la ocurrencia YA existe cuando eso pasa.
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-turnofijo-cancelar-nv", dueno, EstadoVerificacion.VERIFICADO);

        String body = mockMvc.perform(post("/api/v1/turnos-fijos")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestUnaOcurrencia())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long turnoFijoId = objectMapper.readTree(body).get("id").asLong();

        establecimiento.setEstadoVerificacion(EstadoVerificacion.PENDIENTE);
        establecimientoRepository.save(establecimiento);

        mockMvc.perform(post("/api/v1/turnos-fijos/" + turnoFijoId + "/cancelar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceladas").value(1));
    }
}
