package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prueba la FRONTERA transaccional de subir()/borrar(), no el comportamiento observable (eso
 * ya lo cubren FotoEstablecimientoServiceTest y FotoEstablecimientoControllerIntegrationTest,
 * que esta tarea no toca).
 *
 * <p>Necesita un bean real de Spring, no {@code new FotoEstablecimientoService(...)}:
 * {@code @Transactional} y el {@code TransactionTemplate} sólo abren una transacción de
 * verdad a través del contenedor. Un test de Mockito puro (como
 * {@code FotoEstablecimientoServiceTest}) nunca tiene una transacción activa, así que
 * {@code isActualTransactionActive()} daría {@code false} tanto con el bug presente como
 * arreglado — no serviría como evidencia de nada.
 *
 * <p>Ver docs/superpowers/plans/2026-08-22-fotos-fronteras-transaccionales.md.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-fotos-fronteras-transaccionales;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("FotoEstablecimientoService - fronteras transaccionales frente a ImageKit")
class FotoEstablecimientoServiceFronterasTransaccionalesTest {

    @Autowired
    private FotoEstablecimientoService fotoEstablecimientoService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Se mockea el borde externo y nada más, igual que en
     * FotoEstablecimientoControllerIntegrationTest: el resto (seguridad, JPA, transacciones
     * reales) corre de verdad. Sin esto no habría forma de observar el estado de la
     * transacción en el momento exacto de la llamada.
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
                .planSuscripcion(PlanSuscripcion.PREMIUM)
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

    @Test
    @DisplayName("subir_llamaAImageKit_sinTransaccionDeBaseActiva")
    void subir_llamaAImageKit_sinTransaccionDeBaseActiva() {
        Usuario dueno = seedDueno("dueno-tx-subir@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-tx-subir", List.of());

        AtomicBoolean transaccionActivaAlLlamarImageKit = new AtomicBoolean(true);
        when(imageKitService.subir(any(), anyString(), anyString())).thenAnswer(invocation -> {
            transaccionActivaAlLlamarImageKit.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new FotoSubida("https://ik.imagekit.io/demo/tx.jpg", "file_tx_subir");
        });

        fotoEstablecimientoService.subir(establecimiento.getId(), jpeg(), "foto.jpg", dueno.getEmail());

        assertThat(transaccionActivaAlLlamarImageKit.get())
                .as("la conexión de base no debe seguir retenida durante la llamada HTTP a ImageKit")
                .isFalse();
    }

    @Test
    @DisplayName("borrar_llamaAImageKit_sinTransaccionDeBaseActiva")
    void borrar_llamaAImageKit_sinTransaccionDeBaseActiva() {
        Usuario dueno = seedDueno("dueno-tx-borrar@test.com");
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-tx-borrar", List.of(
                FotoEstablecimiento.builder().url("https://ik.io/a.jpg").fileId("file_a").build()));

        AtomicBoolean transaccionActivaAlLlamarImageKit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transaccionActivaAlLlamarImageKit.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(imageKitService).borrar(anyString());

        fotoEstablecimientoService.borrar(establecimiento.getId(), "file_a", dueno.getEmail());

        assertThat(transaccionActivaAlLlamarImageKit.get())
                .as("la conexión de base no debe seguir retenida durante la llamada HTTP a ImageKit")
                .isFalse();
    }

    @Test
    @DisplayName("subir_topeSuperadoEntreFase1YFase3_lanza400_yBorraElArchivoReciénSubidoDeImageKitPorElRollback")
    void subir_topeSuperadoEntreFase1YFase3_lanza400_yBorraElArchivoReciénSubidoDeImageKitPorElRollback() {
        Usuario dueno = seedDueno("dueno-tx-tope@test.com");
        List<FotoEstablecimiento> nueveFotos = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nueveFotos.add(FotoEstablecimiento.builder()
                    .url("https://ik.io/existente" + i + ".jpg")
                    .fileId("file_existente_" + i)
                    .build());
        }
        Establecimiento establecimiento = seedEstablecimiento(dueno, "complejo-tx-tope", nueveFotos);

        when(imageKitService.subir(any(), anyString(), anyString())).thenAnswer(invocation -> {
            // Simula que, mientras esta subida está "en vuelo" hacia ImageKit (fase 2, sin
            // transacción propia abierta), otra subida concurrente terminó primero y dejó al
            // establecimiento en el tope de 10 fotos. Va directo por JDBC (no por el
            // repositorio JPA) para que sea una transacción de verdad, separada e
            // independiente de la de este test, tal como haría un segundo request real en
            // paralelo — y para no tocar la colección lazy de otra sesión ya cerrada.
            jdbcTemplate.update(
                    "INSERT INTO establecimiento_fotos (establecimiento_id, orden, foto_url, file_id) "
                            + "VALUES (?, ?, ?, ?)",
                    establecimiento.getId(), 9, "https://ik.io/concurrente.jpg", "file_concurrente");

            return new FotoSubida("https://ik.imagekit.io/demo/nueva-tope.jpg", "file_nueva_tope");
        });

        assertThatThrownBy(() -> fotoEstablecimientoService.subir(
                establecimiento.getId(), jpeg(), "onceava.jpg", dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class);

        // El borrado en ImageKit no sale de una línea explícita de compensación: sale del
        // rollback de la fase 3, disparando el hook que subir() registró ANTES de revalidar
        // el tope. Si el hook se registrara después, este caso dejaría el archivo huérfano
        // sin loguear ni borrar (ver "El ordering dentro de fase 3 es load-bearing" del plan).
        verify(imageKitService).borrar("file_nueva_tope");
    }
}
