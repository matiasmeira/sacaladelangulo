package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
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
import static org.hamcrest.Matchers.nullValue;
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
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        Establecimiento establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo E2E")
                .direccion("Calle E2E 123")
                .slug("complejo-e2e")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .dueno(dueno));
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

        Establecimiento establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + deporte.name())
                .direccion("Calle " + deporte.name())
                .slug("complejo-" + deporte.name().toLowerCase().replace('_', '-'))
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .dueno(dueno));
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
        Establecimiento establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Sin Horarios")
                .direccion("Calle Sin Horarios 1")
                .slug("complejo-sin-horarios")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .dueno(dueno));
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

    /**
     * El buscador/listado público ya filtraba isActive=true a nivel de query
     * (EstablecimientoRepository.findActivosPorDeporte), pero hasta ahora sólo el detalle
     * (obtenerDetalle_ComplejoInactivo_Devuelve404, más abajo) tenía un test que lo
     * confirmara explícitamente -- este cubre el mismo criterio en el listado.
     */
    @Test
    @DisplayName("GET /publico/complejos no devuelve establecimientos inactivos")
    void buscarComplejos_ExcluyeEstablecimientosInactivos() throws Exception {
        Establecimiento activo = seedComplejoActivo();

        Usuario duenoInactivo = usuarioRepository.save(Usuario.builder()
                .email("dueno-buscador-inactivo@test.com")
                .password("hash")
                .nombre("Dueño Inactivo")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento inactivo = Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo Inactivo Buscador")
                .direccion("Calle Inactiva 1")
                .slug("complejo-inactivo-buscador")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .dueno(duenoInactivo));
        inactivo = establecimientoRepository.save(inactivo);

        canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(inactivo)
                .build());

        mockMvc.perform(get("/api/v1/publico/complejos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.slug == '" + activo.getSlug() + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.slug == 'complejo-inactivo-buscador')]").doesNotExist());
    }

    @ParameterizedTest(name = "GET /publico/complejos no devuelve establecimientos {0}")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("buscarComplejos_ExcluyeEstablecimientosNoVerificados")
    void buscarComplejos_ExcluyeEstablecimientosNoVerificados(EstadoVerificacion estadoVerificacion) throws Exception {
        Establecimiento activo = seedComplejoActivo();

        Usuario duenoNoVerificado = usuarioRepository.save(Usuario.builder()
                .email("dueno-buscador-nv-" + estadoVerificacion + "@test.com")
                .password("hash")
                .nombre("Dueño No Verificado")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        String slugNoVerificado = "complejo-nv-buscador-" + estadoVerificacion.name().toLowerCase();
        Establecimiento noVerificado = Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo No Verificado Buscador")
                .direccion("Calle No Verificada 1")
                .slug(slugNoVerificado)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .estadoVerificacion(estadoVerificacion)
                .dueno(duenoNoVerificado));
        establecimientoRepository.save(noVerificado);

        mockMvc.perform(get("/api/v1/publico/complejos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.slug == '" + activo.getSlug() + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.slug == '" + slugNoVerificado + "')]").doesNotExist());
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

    /**
     * Establecimiento mínimo (sin horarios ni canchas, que no hacen falta para el detalle)
     * con slug PROPIO Y ÚNICO por test -- a propósito NO reutiliza seedComplejoActivo()/
     * "complejo-e2e" acá: la ficha se cachea por slug (ver ComplejoDetalleCache) y esa caché
     * no es transaccional, así que reusar un slug que otro test ya haya leído con éxito
     * arriesgaría un hit contra una respuesta de otro método.
     */
    private Establecimiento crearEstablecimientoConEstado(String slug, boolean isActive, EstadoVerificacion estadoVerificacion) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-" + slug + "@test.com")
                .password("hash")
                .nombre("Dueño " + slug)
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        return establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + slug)
                .direccion("Calle " + slug)
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(isActive)
                .estadoVerificacion(estadoVerificacion)
                .dueno(dueno)));
    }

    @ParameterizedTest(name = "GET detalle de un complejo {0} responde 404")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("obtenerDetalle_ComplejoNoVerificado_Devuelve404")
    void obtenerDetalle_ComplejoNoVerificado_Devuelve404(EstadoVerificacion estadoVerificacion) throws Exception {
        String slug = "complejo-nv-detalle-" + estadoVerificacion.name().toLowerCase();
        crearEstablecimientoConEstado(slug, true, estadoVerificacion);

        mockMvc.perform(get("/api/v1/publico/complejos/" + slug))
                .andExpect(status().isNotFound());
    }

    /**
     * Requisito explícito del guard: un tercero no debe poder distinguir "no existe" de
     * "existe pero está inactivo/no verificado" -- ni por status ni por body.
     */
    @Test
    @DisplayName("obtenerDetalle_404_EsIndistinguibleEntreNoExisteInactivoYNoVerificado")
    void obtenerDetalle_404_EsIndistinguibleEntreNoExisteInactivoYNoVerificado() throws Exception {
        String bodyNoExiste = mockMvc.perform(get("/api/v1/publico/complejos/complejo-indist-no-existe"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        crearEstablecimientoConEstado("complejo-indist-inactivo", false, EstadoVerificacion.VERIFICADO);
        String bodyInactivo = mockMvc.perform(get("/api/v1/publico/complejos/complejo-indist-inactivo"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        crearEstablecimientoConEstado("complejo-indist-no-verificado", true, EstadoVerificacion.PENDIENTE);
        String bodyNoVerificado = mockMvc.perform(get("/api/v1/publico/complejos/complejo-indist-no-verificado"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Los tres tienen slugs distintos en la URL, pero el body de la respuesta (armado por
        // EntityNotFoundException, sin eco del path) tiene que ser exactamente el mismo en
        // los tres casos: eso es lo que hace opaco al 404.
        assertThatBodiesSonElMismoErrorOpaco(bodyNoExiste, bodyInactivo);
        assertThatBodiesSonElMismoErrorOpaco(bodyNoExiste, bodyNoVerificado);
    }

    private void assertThatBodiesSonElMismoErrorOpaco(String bodyA, String bodyB) throws Exception {
        com.fasterxml.jackson.databind.JsonNode nodoA = objectMapper.readTree(bodyA);
        com.fasterxml.jackson.databind.JsonNode nodoB = objectMapper.readTree(bodyB);
        org.junit.jupiter.api.Assertions.assertEquals(nodoA.get("error"), nodoB.get("error"));
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug}/disponibilidad responde 200 sin Authorization y sin datos de jugador")
    void obtenerDisponibilidad_SinAuth_Devuelve200SinPii() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e/disponibilidad")
                        .param("fecha", LocalDate.of(2026, 8, 10).toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("jugador"))))
                .andExpect(content().string(not(containsString("titular"))))
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").value(nullValue()));
    }
}
