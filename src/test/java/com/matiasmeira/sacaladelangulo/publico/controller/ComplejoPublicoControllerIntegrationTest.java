package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jwt.secret=test-secret-de-al-menos-32-bytes-1234567890"
})
@Transactional
@DisplayName("ComplejoPublicoController - Zona pública sin autenticación (end-to-end)")
class ComplejoPublicoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    private Establecimiento seedComplejoActivo() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-e2e@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Complejo E2E")
                .direccion("Calle E2E 123")
                .slug("complejo-e2e")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build();
        establecimiento.setHorariosAtencion(new ArrayList<>(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento)
                .build())));
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(establecimiento)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(6000))
                .build()));
        canchaRepository.save(cancha);

        return establecimiento;
    }

    /**
     * Complejo dedicado a un único deporte, con horario de atención los lunes
     * cubriendo toda la franja de búsqueda de los tests parametrizados de
     * abajo -- a diferencia de seedComplejoActivo (fijo en FUTBOL_5), este
     * recibe el deporte a probar para que el mismo test corra sobre los 29
     * valores de Deporte, incluidos los agregados después de la versión
     * original de 6 valores genéricos (ver V17__eliminar_capacidad_cancha).
     */
    private Establecimiento seedComplejoConDeporte(Deporte deporte) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-" + deporte.name().toLowerCase() + "@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Complejo " + deporte.name())
                .direccion("Calle " + deporte.name())
                .slug("complejo-" + deporte.name().toLowerCase().replace('_', '-'))
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build();
        establecimiento.setHorariosAtencion(new ArrayList<>(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento)
                .build())));
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha " + deporte.name())
                .deportes(Set.of(deporte))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(establecimiento)
                .build();
        canchaRepository.save(cancha);

        return establecimiento;
    }

    @ParameterizedTest(name = "deporte={0}")
    @EnumSource(Deporte.class)
    @DisplayName("GET /publico/complejos?deporte=X trae el complejo para CADA valor del enum Deporte, con fecha/hora (como manda siempre el front público)")
    void buscarComplejos_PorCadaDeporteDelEnumConFechaYHora_TraeElComplejoConCanchaDeEseDeporte(Deporte deporte) throws Exception {
        Establecimiento establecimiento = seedComplejoConDeporte(deporte);

        // 2026-08-10 es lunes: coincide con el único HorarioAtencion cargado en seedComplejoConDeporte.
        mockMvc.perform(get("/api/v1/publico/complejos")
                        .param("deporte", deporte.name())
                        .param("fecha", "2026-08-10")
                        .param("hora", "10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value(establecimiento.getSlug()));
    }

    @Test
    @DisplayName("GET /publico/complejos?deporte=X con un valor que no es del enum responde 400 explícito, no una lista vacía")
    void buscarComplejos_DeporteInvalido_Devuelve400EnVezDeListaVacia() throws Exception {
        mockMvc.perform(get("/api/v1/publico/complejos").param("deporte", "no-es-un-deporte"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Parámetro 'deporte' inválido"));
    }

    /**
     * Reproduce el bug reportado: un complejo recién creado desde el panel del
     * dueño (ModalCrearEstablecimiento manda horariosAtencion: [] a propósito,
     * "lo cargás después desde Configuración") tiene una cancha activa de PADEL
     * pero cero filas en horarios_atencion. ComplejoPublicoService.
     * estaAbiertoEnVentana no encuentra HorarioAtencion para NINGÚN día de la
     * semana y devuelve false vía .orElse(false): el complejo queda excluido de
     * toda búsqueda con fecha/hora, aunque sea PADEL y esté activo. Sin
     * fecha/hora (el front nunca lo hace, pero /buscar lo permite) el filtro de
     * disponibilidad no corre y el complejo sí aparece -- confirma que la
     * exclusión es específica de la ventana pedida, no de que el alta haya
     * salido mal.
     */
    @Test
    @DisplayName("GET /publico/complejos?fecha&hora excluye un complejo activo con cancha de PADEL activa que no tiene NINGÚN horario de atención cargado")
    void buscarComplejos_ComplejoSinHorariosCargados_QuedaExcluidoSoloEnLaBusquedaConFechaYHora() throws Exception {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-sin-horarios@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        // Sin setHorariosAtencion: nace con la lista vacía por defecto, igual que
        // un alta real desde el panel (ver ModalCrearEstablecimiento en el front).
        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Complejo Sin Horarios")
                .direccion("Calle Sin Horarios 1")
                .slug("complejo-sin-horarios")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build();
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha Padel")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(establecimiento)
                .build();
        canchaRepository.save(cancha);

        mockMvc.perform(get("/api/v1/publico/complejos")
                        .param("deporte", "PADEL")
                        .param("fecha", "2026-08-10")
                        .param("hora", "10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/publico/complejos").param("deporte", "PADEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("complejo-sin-horarios"));
    }

    @Test
    @DisplayName("GET /publico/complejos responde 200 sin Authorization")
    void buscarComplejos_SinAuth_Devuelve200() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} responde 200 sin Authorization y sin duenoId")
    void obtenerDetalle_SinAuth_Devuelve200SinDuenoId() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("complejo-e2e"))
                .andExpect(jsonPath("$.precioDesde").value(5000))
                .andExpect(jsonPath("$.senaDesde").value(1000))
                .andExpect(content().string(not(containsString("duenoId"))));
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} con slug inexistente responde 404")
    void obtenerDetalle_SlugInexistente_Devuelve404() throws Exception {
        mockMvc.perform(get("/api/v1/publico/complejos/no-existe"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} con complejo inactivo responde 404")
    void obtenerDetalle_ComplejoInactivo_Devuelve404() throws Exception {
        Establecimiento activo = seedComplejoActivo();
        activo.setIsActive(false);
        establecimientoRepository.save(activo);

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug}/disponibilidad responde 200 sin Authorization y sin datos de jugador")
    void obtenerDisponibilidad_SinAuth_Devuelve200SinPii() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e/disponibilidad")
                        .param("fecha", LocalDate.of(2026, 8, 10).toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("jugador"))))
                .andExpect(content().string(not(containsString("titular"))));
    }
}
