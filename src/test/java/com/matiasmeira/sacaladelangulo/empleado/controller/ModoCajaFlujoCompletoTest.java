package com.matiasmeira.sacaladelangulo.empleado.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo completo del Modo Caja, tal como lo vive la PC del mostrador.
 *
 * Lo que este modo resuelve: los empleados no tienen usuario y contraseña propios.
 * Sin esto, cada vez que uno necesita operar dependería del dueño o de su credencial.
 * La idea es la del "recordar este dispositivo" del home banking: el dueño valida
 * fuerte UNA vez (empareja la PC con un código) y desde ahí alcanza con algo simple
 * (el PIN del empleado).
 *
 * La distinción que se malentiende fácil, y que este test fija:
 *
 *   - lo que AUTORIZA a pedir un PIN sin el dueño delante es la COOKIE del
 *     dispositivo de confianza;
 *   - el PIN sólo dice QUÉ empleado es.
 *
 * Son dos cosas separadas. Por eso GET /empleados/activos NO puede exigir un JWT:
 * cuando el kiosco muestra la lista todavía no hay ninguna sesión abierta, y
 * exigirla obligaría a dejar la credencial del dueño guardada en la tablet — que es
 * exactamente lo que este modo evita.
 *
 * Cubre además el alta de empleados, que fallaba con 409: registrarAdministrativa era
 * REQUIRES_NEW y abría una transacción nueva que no podía ver al empleado recién creado
 * y sin commitear, violando la FK registro_auditoria_empleados.empleado_id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-modo-caja;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("Modo Caja - emparejar, listar empleados y entrar con PIN, sin sesión del dueño")
class ModoCajaFlujoCompletoTest {

    private static final String COOKIE = "saque_caja_device";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    private Establecimiento sembrarLocalConEmpleado(String sufijo) throws Exception {
        String emailDueno = "dueno-" + sufijo + "@modo-caja-test.com";
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email(emailDueno)
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Modo Caja")
                .direccion("Calle Falsa 123")
                .slug("complejo-modo-caja-" + sufijo)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        // Alta de empleado por HTTP y no llamando al service: es el camino real y deja
        // que la transacción del request abarque el insert del usuario y el de su
        // registro de auditoría. Sin el fix de propagación, esto devuelve 409.
        mockMvc.perform(post("/api/v1/establecimientos/" + establecimiento.getId() + "/empleados")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user(emailDueno).roles("OWNER"))
                        .contentType("application/json")
                        .content("{\"nombre\":\"Julian\",\"pin\":\"7391\",\"permisos\":[\"OPERAR_CAJA\"]}"))
                .andExpect(status().isCreated());

        return establecimiento;
    }

    @Test
    @DisplayName("mostrador_ListaEmpleadosYEntraConPin_SinJwtDelDueno")
    void mostrador_ListaEmpleadosYEntraConPin_SinJwtDelDueno() throws Exception {
        Establecimiento establecimiento = sembrarLocalConEmpleado("flujo");
        Long estId = establecimiento.getId();

        // 1. Sin cookie no hay nada: la PC todavía no es de confianza.
        mockMvc.perform(get("/api/v1/establecimientos/" + estId + "/empleados/activos"))
                .andExpect(status().isForbidden());

        // 2. El dueño empareja la PC. (Se toma la cookie de la respuesta, que es lo que
        //    haría el browser al completar el emparejamiento.)
        MvcResult emparejamiento = mockMvc.perform(
                        post("/api/v1/establecimientos/" + estId + "/caja/dispositivos/activar-local")
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                        .user("dueno-flujo@modo-caja-test.com").roles("OWNER"))
                                .contentType("application/json")
                                .content("{\"label\":\"Caja mostrador\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = emparejamiento.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("el emparejamiento tiene que devolver la cookie").isNotNull();
        String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        Cookie cookieDispositivo = new Cookie(COOKIE, token);

        // 3. Con la PC de confianza y SIN ninguna sesión, el kiosco lista los nombres.
        //    Este es el paso que devolvía 401 y dejaba el Modo Caja inutilizable.
        mockMvc.perform(get("/api/v1/establecimientos/" + estId + "/empleados/activos")
                        .cookie(cookieDispositivo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Julian"))
                // Sólo id y nombre: la pantalla de mostrador no expone nada más.
                .andExpect(jsonPath("$[0].permisos").doesNotExist());

        // 4. El empleado toca su nombre y pone el PIN. Tampoco hace falta sesión previa.
        mockMvc.perform(post("/api/v1/auth/empleados/login")
                        .cookie(cookieDispositivo)
                        .contentType("application/json")
                        .content("{\"establecimientoId\":" + estId
                                + ",\"nombre\":\"Julian\",\"pin\":\"7391\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("cookieDeOtroLocal_NoListaEmpleados")
    void cookieDeOtroLocal_NoListaEmpleados() throws Exception {
        Establecimiento propio = sembrarLocalConEmpleado("ajeno");

        Usuario otroDueno = usuarioRepository.save(Usuario.builder()
                .email("otro@modo-caja-test.com")
                .password("hash")
                .nombre("Otro Dueno")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
        Establecimiento ajeno = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Ajeno")
                .direccion("Otra Calle 456")
                .slug("complejo-ajeno-modo-caja")
                .latitud(-34.7)
                .longitud(-58.5)
                .requiereSena(false)
                .isActive(true)
                .dueno(otroDueno)
                .build());

        MvcResult emparejamiento = mockMvc.perform(
                        post("/api/v1/establecimientos/" + ajeno.getId() + "/caja/dispositivos/activar-local")
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                        .user("otro@modo-caja-test.com").roles("OWNER"))
                                .contentType("application/json")
                                .content("{\"label\":\"Caja ajena\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = emparejamiento.getResponse().getHeader("Set-Cookie");
        String token = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));

        // Que el endpoint sea permitAll NO lo deja abierto: la cookie ata el
        // dispositivo a SU establecimiento y el controller lo verifica.
        mockMvc.perform(get("/api/v1/establecimientos/" + propio.getId() + "/empleados/activos")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isForbidden());
    }
}
