package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ReordenarFotosRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-fotos-establecimiento;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("/api/v1/establecimientos/{id}/fotos")
class FotoEstablecimientoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Se mockea el borde externo y nada más: el resto del camino (security, multipart,
     * validación, JPA) corre de verdad. Sin esto el test pegaría contra ImageKit.
     */
    @MockitoBean
    private ImageKitService imageKitService;

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private Usuario seedDueno(String email) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    private Establecimiento seedEstablecimiento(Usuario dueno, String slug, List<FotoEstablecimiento> fotos) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle 1")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .fotos(new ArrayList<>(fotos))
                .build());
    }

    private String tokenDe(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }

    @Test
    @DisplayName("POST_comoDueno_devuelve201ConUrlYFileId")
    void post_comoDueno_devuelve201ConUrlYFileId() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-ok@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-ok", List.of());
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://ik.imagekit.io/demo/nueva.jpg", "file_nueva"));

        mockMvc.perform(multipart("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + tokenDe(dueno)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://ik.imagekit.io/demo/nueva.jpg"))
                .andExpect(jsonPath("$.fileId").value("file_nueva"));
    }

    @Test
    @DisplayName("POST_aEstablecimientoAjeno_devuelve403")
    void post_aEstablecimientoAjeno_devuelve403() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-propio@test.com");
        Usuario intruso = seedDueno("dueno-fotos-intruso@test.com");
        Establecimiento ajeno = seedEstablecimiento(dueno, "complejo-fotos-ajeno", List.of());

        mockMvc.perform(multipart("/api/v1/establecimientos/" + ajeno.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + tokenDe(intruso)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST_conPdfDisfrazadoDeJpeg_devuelve400")
    void post_conPdfDisfrazadoDeJpeg_devuelve400() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-pdf@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-pdf", List.of());
        byte[] pdf = new byte[64];
        System.arraycopy("%PDF-".getBytes(), 0, pdf, 0, 5);

        mockMvc.perform(multipart("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "trampa.jpg", "image/jpeg", pdf))
                        .header("Authorization", "Bearer " + tokenDe(dueno)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_orden_cambiaLaFotoPrincipal")
    void put_orden_cambiaLaFotoPrincipal() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-orden@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-orden", List.of(
                FotoEstablecimiento.builder().url("https://ik.io/a.jpg").fileId("file_a").build(),
                FotoEstablecimiento.builder().url("https://ik.io/b.jpg").fileId("file_b").build()));

        mockMvc.perform(put("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos/orden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReordenarFotosRequest(List.of("file_b", "file_a"))))
                        .header("Authorization", "Bearer " + tokenDe(dueno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileId").value("file_b"))
                .andExpect(jsonPath("$[1].fileId").value("file_a"));
    }

    /**
     * Mide que la subida siga funcionando cuando el cliente manda Idempotency-Key: el
     * filtro de idempotencia corre dentro de la cadena de Spring Security, antes de que
     * el DispatcherServlet resuelva el multipart, así que si tocara el input stream el
     * controller recibiría el archivo vacío.
     */
    @Test
    @DisplayName("POST_conIdempotencyKey_elArchivoLlegaCompleto")
    void post_conIdempotencyKey_elArchivoLlegaCompleto() throws Exception {
        Usuario dueno = seedDueno("dueno-fotos-idem@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-fotos-idem", List.of());
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://ik.imagekit.io/demo/idem.jpg", "file_idem"));

        mockMvc.perform(multipart("/api/v1/establecimientos/" + establecimiento.getId() + "/fotos")
                        .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                        .header("Authorization", "Bearer " + tokenDe(dueno))
                        .header("Idempotency-Key", "clave-de-prueba-1"))
                .andExpect(status().isCreated());
    }
}
