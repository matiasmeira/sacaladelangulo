package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.EmpleadoLoginRequest;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduce contra una base real (H2) el escenario que un UsuarioRepository mockeado no
 * puede ejercitar: DOS empleados con el mismo nombre en el mismo establecimiento, uno dado
 * de baja y uno vigente.
 *
 * <p>Es un estado alcanzable con el flujo normal del producto, porque el alta valida
 * unicidad de nombre sólo entre los ACTIVOS (ver
 * EmpleadoService.crearEmpleado -> existsBy...AndIsActiveTrue): dar de baja a "Juan" y
 * volver a darlo de alta deja dos filas "Juan" EMPLOYEE en el mismo establecimiento. Si la
 * consulta del login no filtra por activo, devuelve 2 filas para un finder que declara
 * Optional y Spring Data corta con IncorrectResultSizeDataAccessException — un 500 en el
 * login de mostrador, con PIN correcto o incorrecto, y sin forma de arreglarlo desde la API
 * (no hay endpoint de reactivación ni de renombrado).
 *
 * <p>AuthService se arma a mano con el repositorio REAL y mocks para el resto de sus
 * colaboradores: lo que se prueba acá es exactamente la consulta contra la base, así que
 * mockear el repositorio (como hace AuthServiceTest) haría el test inútil para este caso.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("AuthService - Login de mostrador con un homónimo desactivado")
class AuthServiceEmpleadoHomonimoTest {

    private static final String EMAIL_EMPLEADO_VIGENTE = "empleado-vigente@empleados.sacaladelangulo.interno";
    private static final String EMAIL_EMPLEADO_DE_BAJA = "empleado-de-baja@empleados.sacaladelangulo.interno";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private AuthService authService;
    private AuthenticationManager authenticationManager;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        jwtService = mock(JwtService.class);
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);

        authService = new AuthService(usuarioRepository, mock(PasswordEncoder.class),
                jwtService, authenticationManager, rateLimiterService);
    }

    @Test
    @DisplayName("authenticateEmpleado_ConHomonimoDesactivado_AutenticaAlEmpleadoVigente")
    void authenticateEmpleado_ConHomonimoDesactivado_AutenticaAlEmpleadoVigente() {
        Long establecimientoId = establecimientoConDosJuanes();
        stubearAutenticacionExitosaDe(EMAIL_EMPLEADO_VIGENTE);

        AuthResponse response = authService.authenticateEmpleado(
                new EmpleadoLoginRequest(null, "Juan", "9137"), establecimientoId);

        assertEquals("jwt-de-mostrador", response.token());

        // Y se autenticó contra el empleado VIGENTE, no contra el que está de baja.
        ArgumentCaptor<UsernamePasswordAuthenticationToken> captor =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertEquals(EMAIL_EMPLEADO_VIGENTE, captor.getValue().getPrincipal());
    }

    @Test
    @DisplayName("authenticateEmpleado_SoloExisteUnEmpleadoDesactivado_RechazaConCredencialesInvalidas")
    void authenticateEmpleado_SoloExisteUnEmpleadoDesactivado_RechazaConCredencialesInvalidas() {
        Establecimiento establecimiento = establecimientoDePrueba();
        entityManager.persist(empleado("Juan", EMAIL_EMPLEADO_DE_BAJA, establecimiento, false));
        entityManager.flush();

        // Guard: dar de baja a un empleado tiene que seguir cerrándole el mostrador, sea
        // el chequeo explícito del service o el filtro de la consulta el que lo resuelva.
        assertThrows(BadCredentialsException.class, () -> authService.authenticateEmpleado(
                new EmpleadoLoginRequest(null, "Juan", "9137"), establecimiento.getId()));
    }

    /**
     * El estado que deja dar de baja a "Juan" y volver a darlo de alta: dos filas con el
     * mismo nombre en el mismo establecimiento, sólo una activa.
     */
    private Long establecimientoConDosJuanes() {
        Establecimiento establecimiento = establecimientoDePrueba();
        entityManager.persist(empleado("Juan", EMAIL_EMPLEADO_DE_BAJA, establecimiento, false));
        entityManager.persist(empleado("Juan", EMAIL_EMPLEADO_VIGENTE, establecimiento, true));
        entityManager.flush();
        return establecimiento.getId();
    }

    private Establecimiento establecimientoDePrueba() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        return entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Test")
                .direccion("Calle Falsa 123")
                .slug("complejo-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    private Usuario empleado(String nombre, String email, Establecimiento establecimiento, boolean activo) {
        return Usuario.builder()
                .email(email)
                .password("hash-del-pin")
                .nombre(nombre)
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .isActive(activo)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build();
    }

    private void stubearAutenticacionExitosaDe(String email) {
        UserDetails userDetails = User.withUsername(email)
                .password("hash-del-pin")
                .authorities("ROLE_EMPLOYEE")
                .build();
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        when(jwtService.generateToken(eq(userDetails), anyMap(), anyLong())).thenReturn("jwt-de-mostrador");
    }
}
