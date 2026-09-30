package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/auth/empleados/login (pendiente 50): el login de mostrador por PIN. Lo que autoriza a
 * pedirlo es la COOKIE del dispositivo de caja; el PIN sólo dice qué empleado es. La chain lo deja
 * pasar (permitAll en /api/v1/auth/**, SecurityConfig:65) y la decisión real se toma en
 * AuthController.loginEmpleado (AuthController:87-92): DispositivoCajaGate.exigirDispositivo y después
 * AuthService.authenticateEmpleado con el establecimiento DEL DISPOSITIVO, no el del body.
 *
 * <p>Cuerpos que se fijan:
 * <ul>
 *   <li>403 {"error":"Dispositivo no autorizado"}: cookie ausente o vacía (DispositivoCajaGate:45-46),
 *       inexistente (DispositivoCajaService:197) o de un dispositivo revocado (DispositivoCajaService:199-200).</li>
 *   <li>401 {"error":"Credenciales inválidas"}: id de otro local (AuthService:137-138), nombre que no
 *       existe en el local del dispositivo (AuthService:154-156), PIN incorrecto o empleado deshabilitado
 *       (AuthService:163-166). Sale de GlobalExceptionHandler:63-67, que ignora el mensaje de la excepción.</li>
 * </ul>
 *
 * <p>Aislamiento: el bucket por IP de RateLimitFilter (30 cada 5 min, RateLimitFilter:44) y el bucket por
 * nombre (AuthService:145-148, clave con el estId, que se repite entre tests) viven en el contexto compartido
 * y no se resetean. Por eso cada test usa una IP propia ({@link #ipUnica()}) y nombres únicos.
 */
@DisplayName("POST /api/v1/auth/empleados/login")
class ModoCajaLoginEmpleadoAutorizacionTest extends AbstractSecurityWebTest {

    private static final String RUTA = "/api/v1/auth/empleados/login";
    private static final String PIN = "4827";
    private static final String CUERPO_403 = "{\"error\":\"Dispositivo no autorizado\"}";
    private static final String CUERPO_401 = "{\"error\":\"Credenciales inválidas\"}";
    private static final String CUERPO_429_IP =
            "{\"error\":\"Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.\"}";

    @Autowired
    private DispositivoCajaRepository dispositivoCajaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static String cuerpo(Long establecimientoId, String nombre, String pin) {
        StringBuilder sb = new StringBuilder("{");
        if (establecimientoId != null) {
            sb.append("\"establecimientoId\":").append(establecimientoId).append(",");
        }
        return sb.append("\"nombre\":\"").append(nombre).append("\",\"pin\":\"").append(pin).append("\"}").toString();
    }

    private org.springframework.test.web.servlet.ResultActions login(Cookie cookie, String ip, String body) throws Exception {
        var req = post(RUTA).contentType("application/json").content(body).with(desdeIp(ip));
        if (cookie != null) {
            req = req.cookie(cookie);
        }
        return mockMvc.perform(req);
    }

    /** El login rechazado no toca a los dispositivos: siguen los que había y siguen activos. */
    private void assertDispositivosIntactos(int esperados) {
        List<DispositivoCaja> todos = dispositivoCajaRepository.findAll();
        assertEquals(esperados, todos.size());
        assertTrue(todos.stream().allMatch(d -> Boolean.TRUE.equals(d.getActivo())));
    }

    @Test
    @DisplayName("sinCookie_Devuelve403YNoEmiteToken")
    void sinCookie_Devuelve403YNoEmiteToken() throws Exception {
        empleadoConPin(establecimientoA, "Julian", PIN);

        login(null, ipUnica(), cuerpo(establecimientoA.getId(), "Julian", PIN))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
        assertDispositivosIntactos(0);
    }

    @Test
    @DisplayName("cookieVacia_Devuelve403")
    void cookieVacia_Devuelve403() throws Exception {
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);

        login(new Cookie(COOKIE_DISPOSITIVO, ""), ipUnica(), cuerpo(establecimientoA.getId(), nombre, PIN))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
        assertDispositivosIntactos(0);
    }

    @Test
    @DisplayName("cookieBasura_Devuelve403")
    void cookieBasura_Devuelve403() throws Exception {
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        cookieDispositivo(establecimientoA, duenoA);

        login(new Cookie(COOKIE_DISPOSITIVO, "esto-no-es-un-token-de-dispositivo"), ipUnica(),
                cuerpo(establecimientoA.getId(), nombre, PIN))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
        assertDispositivosIntactos(1);
    }

    @Test
    @DisplayName("dispositivoRevocado_Devuelve403")
    void dispositivoRevocado_Devuelve403() throws Exception {
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookie = cookieDispositivo(establecimientoA, duenoA);
        DispositivoCaja dispositivo = dispositivoCajaRepository.findAll().get(0);
        dispositivo.setActivo(false);
        dispositivoCajaRepository.save(dispositivo);

        login(cookie, ipUnica(), cuerpo(establecimientoA.getId(), nombre, PIN))
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
        // Sigue revocado: el intento no lo reactiva.
        assertFalse(dispositivoCajaRepository.findById(dispositivo.getId()).orElseThrow().getActivo());
    }

    @Test
    @DisplayName("cookieDeAConIdDeB_EmpleadoDeBConPinCorrecto_Devuelve401")
    void cookieDeAConIdDeB_EmpleadoDeBConPinCorrecto_Devuelve401() throws Exception {
        // El caso que distingue "el establecimiento sale del dispositivo" de "sale del body": el empleado
        // de B existe y el PIN es el bueno, así que si el service confiara en el id del body entraría.
        String nombre = nombreUnico();
        empleadoConPin(establecimientoB, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(establecimientoB.getId(), nombre, PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(CUERPO_401));
        assertDispositivosIntactos(1);
    }

    @Test
    @DisplayName("cookieDeAConIdDeB_EmpleadoDeAConPinCorrecto_Devuelve401")
    void cookieDeAConIdDeB_EmpleadoDeAConPinCorrecto_Devuelve401() throws Exception {
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(establecimientoB.getId(), nombre, PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(CUERPO_401));
    }

    @Test
    @DisplayName("cookieDeAConIdDeA_Devuelve200ConTokenDelEmpleado")
    void cookieDeAConIdDeA_Devuelve200ConTokenDelEmpleado() throws Exception {
        String nombre = nombreUnico();
        Usuario empleado = empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        MvcResult resultado = login(cookieA, ipUnica(), cuerpo(establecimientoA.getId(), nombre, PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(resultado.getResponse().getContentAsString()).get("token").asText();
        assertEquals(empleado.getEmail(), jwtService.extractUsername(token));
    }

    @Test
    @DisplayName("sinEstablecimientoIdEnElBody_UsaElDelDispositivo")
    void sinEstablecimientoIdEnElBody_UsaElDelDispositivo() throws Exception {
        // AuthService:137: establecimientoId es opcional en el body (EmpleadoLoginRequest); si falta no hay
        // mismatch que detectar y se usa el del dispositivo.
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(null, nombre, PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("nombreDeEmpleadoDeOtroLocal_Devuelve401")
    void nombreDeEmpleadoDeOtroLocal_Devuelve401() throws Exception {
        // Sin id en el body: el nombre se busca sólo en el local del dispositivo (AuthService:154-155).
        String nombre = nombreUnico();
        empleadoConPin(establecimientoB, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(null, nombre, PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(CUERPO_401));
    }

    @Test
    @DisplayName("empleadoInactivo_Devuelve401")
    void empleadoInactivo_Devuelve401() throws Exception {
        String nombre = nombreUnico();
        Usuario empleado = empleadoConPin(establecimientoA, nombre, PIN);
        empleado.setIsActive(false);
        usuarioRepository.save(empleado);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(establecimientoA.getId(), nombre, PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(CUERPO_401));
    }

    @Test
    @DisplayName("empleadoConDeletedAtPeroActivo_Devuelve401")
    void empleadoConDeletedAtPeroActivo_Devuelve401() throws Exception {
        // isActive sigue en true: el finder de AuthService:154 (sólo filtra isActive) lo encuentra, y lo
        // frena el AuthenticationManager porque UsuarioUserDetailsMapper:23 arma enabled = isActive && deletedAt == null.
        String nombre = nombreUnico();
        Usuario empleado = empleadoConPin(establecimientoA, nombre, PIN);
        empleado.setDeletedAt(LocalDateTime.now().minusDays(1));
        usuarioRepository.save(empleado);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(establecimientoA.getId(), nombre, PIN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(CUERPO_401));
    }

    @Test
    @DisplayName("pinIncorrecto_Devuelve401")
    void pinIncorrecto_Devuelve401() throws Exception {
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(establecimientoA.getId(), nombre, "1111"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(CUERPO_401));
    }

    @Test
    @DisplayName("nombreInexistenteInactivoYPinIncorrecto_MismoCuerpo_SinOraculo")
    void nombreInexistenteInactivoYPinIncorrecto_MismoCuerpo_SinOraculo() throws Exception {
        // Quien pega desde afuera no puede distinguir "el nombre no existe" de "existe pero está dado de baja"
        // ni de "existe y el PIN está mal": status y cuerpo idénticos, byte a byte.
        String activo = nombreUnico();
        String inactivo = nombreUnico();
        String eliminado = nombreUnico();
        empleadoConPin(establecimientoA, activo, PIN);
        Usuario baja = empleadoConPin(establecimientoA, inactivo, PIN);
        baja.setIsActive(false);
        usuarioRepository.save(baja);
        Usuario borrado = empleadoConPin(establecimientoA, eliminado, PIN);
        borrado.setDeletedAt(LocalDateTime.now().minusDays(1));
        usuarioRepository.save(borrado);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        List<String> cuerpos = new ArrayList<>();
        for (String body : List.of(
                cuerpo(establecimientoA.getId(), nombreUnico(), PIN),
                cuerpo(establecimientoA.getId(), inactivo, PIN),
                cuerpo(establecimientoA.getId(), eliminado, PIN),
                cuerpo(establecimientoA.getId(), activo, "1111"),
                cuerpo(establecimientoB.getId(), activo, PIN))) {
            MvcResult r = login(cookieA, ipUnica(), body).andReturn();
            assertEquals(401, r.getResponse().getStatus(), body);
            cuerpos.add(r.getResponse().getContentAsString());
        }
        assertTrue(cuerpos.stream().allMatch(CUERPO_401::equals), cuerpos::toString);
    }

    @Test
    @DisplayName("nombreConMayusculasYEspacios_SeNormalizaYEntra")
    void nombreConMayusculasYEspacios_SeNormalizaYEntra() throws Exception {
        // AuthService:144 recorta y pasa a minúsculas; el finder compara ignorando mayúsculas.
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        login(cookieA, ipUnica(), cuerpo(establecimientoA.getId(), "  " + nombre.toUpperCase() + "  ", PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("nombreEnBlanco_Devuelve400")
    void nombreEnBlanco_Devuelve400() throws Exception {
        // La validación del DTO corre antes que el controller: ni siquiera hace falta la cookie.
        login(null, ipUnica(), cuerpo(establecimientoA.getId(), "  ", PIN))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"nombre\":\"El nombre es obligatorio\"}"));
    }

    @Test
    @DisplayName("pinConFormatoInvalido_Devuelve400")
    void pinConFormatoInvalido_Devuelve400() throws Exception {
        String nombre = nombreUnico();
        empleadoConPin(establecimientoA, nombre, PIN);
        Cookie cookieA = cookieDispositivo(establecimientoA, duenoA);

        for (String pinInvalido : List.of("123", "12345", "abcd", "12 4")) {
            login(cookieA, ipUnica(), cuerpo(establecimientoA.getId(), nombre, pinInvalido))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string("{\"pin\":\"El PIN debe tener exactamente 4 dígitos\"}"));
        }
    }

    @Test
    @DisplayName("sinPin_Devuelve400")
    void sinPin_Devuelve400() throws Exception {
        login(null, ipUnica(), "{\"nombre\":\"Julian\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"pin\":\"El PIN es obligatorio\"}"));
    }

    @Test
    @DisplayName("jsonMalFormado_Devuelve400")
    void jsonMalFormado_Devuelve400() throws Exception {
        login(null, ipUnica(), "{no es json")
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"error\":\"Cuerpo de la petición inválido o mal formado\"}"));
    }

    @Test
    @DisplayName("porIp_ElRequest31DesdeLaMismaIpDevuelve429_YOtraIpNoSeAfecta")
    void porIp_ElRequest31DesdeLaMismaIpDevuelve429_YOtraIpNoSeAfecta() throws Exception {
        // Límite por IP de RateLimitFilter:44 (30 cada 5 min); el cuerpo sale de RateLimitFilter.responder429.
        // No estaba cubierto: RateLimitFilterTest sólo prueba /auth/login y /registro/iniciar con el limitador
        // mockeado, y el test de integración sólo /register/owner.
        String ip = ipUnica();
        String body = cuerpo(establecimientoA.getId(), "Julian", PIN);
        for (int i = 1; i <= 30; i++) {
            login(null, ip, body).andExpect(status().isForbidden());
        }

        login(null, ip, body)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(CUERPO_429_IP));

        login(null, ipUnica(), body)
                .andExpect(status().isForbidden())
                .andExpect(content().string(CUERPO_403));
    }
}
