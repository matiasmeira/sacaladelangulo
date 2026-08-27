package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.imagekit.FotoSubida;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitException;
import com.matiasmeira.sacaladelangulo.core.imagekit.ImageKitService;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.FotoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.FotoEstablecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FotoEstablecimientoService - gestión de fotos del complejo")
class FotoEstablecimientoServiceTest {

    private static final Long ESTABLECIMIENTO_ID = 7L;
    private static final String EMAIL_DUENO = "dueno@test.com";

    @Mock
    private EstablecimientoRepository establecimientoRepository;
    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock
    private ImageKitService imageKitService;
    @Mock
    private RegistroAuditoriaService registroAuditoriaService;
    @Mock
    private ComplejoDetalleCache complejoDetalleCache;
    @Mock
    private PlatformTransactionManager transactionManager;

    private FotoEstablecimientoService servicio;
    private Establecimiento establecimiento;
    private Usuario dueno;

    @BeforeEach
    void setUp() {
        // El TransactionTemplate necesita un PlatformTransactionManager real (aunque sea
        // mockeado) para ejecutar de verdad los callbacks de las tres fases, tal como hace
        // Spring en producción; SimpleTransactionStatus está pensado justo para esto (ver su
        // javadoc: "as part of a mock PlatformTransactionManager").
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        servicio = new FotoEstablecimientoService(
                establecimientoRepository,
                autorizacionEmpleadoService,
                imageKitService,
                new ValidadorFoto(),
                registroAuditoriaService,
                complejoDetalleCache,
                transactionManager);

        dueno = Usuario.builder().id(1L).email(EMAIL_DUENO).rol(Role.OWNER).build();
        establecimiento = Establecimiento.builder()
                .id(ESTABLECIMIENTO_ID)
                .nombre("Complejo Test")
                .dueno(dueno)
                .fotos(new ArrayList<>())
                .build();

        when(establecimientoRepository.findById(ESTABLECIMIENTO_ID)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(any(), anyString())).thenReturn(dueno);
    }

    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static FotoEstablecimiento foto(String fileId) {
        return FotoEstablecimiento.builder()
                .url("https://ik.imagekit.io/demo/" + fileId + ".jpg")
                .fileId(fileId)
                .build();
    }

    @Test
    @DisplayName("subir_agregaLaFotoAlFinal_conUrlYFileId")
    void subir_agregaLaFotoAlFinal_conUrlYFileId() {
        establecimiento.getFotos().add(foto("file_1"));
        when(imageKitService.subir(any(), anyString(), anyString()))
                .thenReturn(new FotoSubida("https://ik.imagekit.io/demo/nueva.jpg", "file_nueva"));

        FotoEstablecimientoResponse resultado =
                servicio.subir(ESTABLECIMIENTO_ID, jpeg(), "nueva.jpg", EMAIL_DUENO);

        assertThat(resultado.url()).isEqualTo("https://ik.imagekit.io/demo/nueva.jpg");
        assertThat(resultado.fileId()).isEqualTo("file_nueva");
        assertThat(establecimiento.getFotos()).hasSize(2);
        assertThat(establecimiento.getFotos().get(1).getFileId()).isEqualTo("file_nueva");
        verify(imageKitService).subir(any(), eq("nueva.jpg"), eq("/establecimientos/7/"));
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.SUBIR_FOTO_ESTABLECIMIENTO), anyLong(), anyString());
    }

    @Test
    @DisplayName("subir_aEstablecimientoAjeno_lanzaAccessDenied_ySinTocarImageKit")
    void subir_aEstablecimientoAjeno_lanzaAccessDenied_ySinTocarImageKit() {
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(any(), anyString()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThatThrownBy(() -> servicio.subir(ESTABLECIMIENTO_ID, jpeg(), "x.jpg", "otro@test.com"))
                .isInstanceOf(AccessDeniedException.class);

        verify(imageKitService, never()).subir(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("subir_archivoQueNoEsImagen_lanza400_ySinTocarImageKit")
    void subir_archivoQueNoEsImagen_lanza400_ySinTocarImageKit() {
        byte[] pdf = new byte[64];
        System.arraycopy("%PDF-".getBytes(), 0, pdf, 0, 5);

        assertThatThrownBy(() -> servicio.subir(ESTABLECIMIENTO_ID, pdf, "trampa.jpg", EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(imageKitService, never()).subir(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("subir_cuandoYaHay10Fotos_lanza400")
    void subir_cuandoYaHay10Fotos_lanza400() {
        for (int i = 0; i < ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO; i++) {
            establecimiento.getFotos().add(foto("file_" + i));
        }

        assertThatThrownBy(() -> servicio.subir(ESTABLECIMIENTO_ID, jpeg(), "onceava.jpg", EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(imageKitService, never()).subir(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("borrar_llamaAImageKit_ySacaLaFotoDeLaLista")
    void borrar_llamaAImageKit_ySacaLaFotoDeLaLista() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));

        servicio.borrar(ESTABLECIMIENTO_ID, "file_1", EMAIL_DUENO);

        verify(imageKitService).borrar("file_1");
        assertThat(establecimiento.getFotos()).hasSize(1);
        assertThat(establecimiento.getFotos().get(0).getFileId()).isEqualTo("file_2");
    }

    @Test
    @DisplayName("borrar_siImageKitFalla_igualSacaLaFotoDeLaLista")
    void borrar_siImageKitFalla_igualSacaLaFotoDeLaLista() {
        establecimiento.getFotos().add(foto("file_1"));
        doThrow(new ImageKitException("500 de ImageKit")).when(imageKitService).borrar("file_1");

        servicio.borrar(ESTABLECIMIENTO_ID, "file_1", EMAIL_DUENO);

        assertThat(establecimiento.getFotos()).isEmpty();
    }

    @Test
    @DisplayName("borrar_fileIdInexistente_lanzaEntityNotFound")
    void borrar_fileIdInexistente_lanzaEntityNotFound() {
        establecimiento.getFotos().add(foto("file_1"));

        assertThatThrownBy(() -> servicio.borrar(ESTABLECIMIENTO_ID, "file_no_existe", EMAIL_DUENO))
                .isInstanceOf(com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class);

        verify(imageKitService, never()).borrar(anyString());
    }

    @Test
    @DisplayName("reordenar_cambiaCualEsLaFotoPrincipal")
    void reordenar_cambiaCualEsLaFotoPrincipal() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));
        establecimiento.getFotos().add(foto("file_3"));

        List<FotoEstablecimientoResponse> resultado =
                servicio.reordenar(ESTABLECIMIENTO_ID, List.of("file_3", "file_1", "file_2"), EMAIL_DUENO);

        assertThat(establecimiento.getFotos().get(0).getFileId()).isEqualTo("file_3");
        assertThat(resultado.get(0).fileId()).isEqualTo("file_3");
        assertThat(resultado).hasSize(3);
    }

    @Test
    @DisplayName("reordenar_conFileIdsQueNoSonPermutacionExacta_lanza400")
    void reordenar_conFileIdsQueNoSonPermutacionExacta_lanza400() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));

        // Falta uno
        assertThatThrownBy(() -> servicio.reordenar(ESTABLECIMIENTO_ID, List.of("file_1"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        // Sobra uno que no existe
        assertThatThrownBy(() -> servicio.reordenar(
                ESTABLECIMIENTO_ID, List.of("file_1", "file_2", "file_9"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);

        // Repetido
        assertThatThrownBy(() -> servicio.reordenar(
                ESTABLECIMIENTO_ID, List.of("file_1", "file_1"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reordenar_conFotoLegacySinFileId_lanza400")
    void reordenar_conFotoLegacySinFileId_lanza400() {
        establecimiento.getFotos().add(FotoEstablecimiento.builder()
                .url("https://cdn.viejo.com/a.jpg")
                .fileId(null)
                .build());
        establecimiento.getFotos().add(foto("file_2"));

        assertThatThrownBy(() -> servicio.reordenar(ESTABLECIMIENTO_ID, List.of("file_2"), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listar_devuelveLasFotosEnOrden")
    void listar_devuelveLasFotosEnOrden() {
        establecimiento.getFotos().add(foto("file_1"));
        establecimiento.getFotos().add(foto("file_2"));

        List<FotoEstablecimientoResponse> resultado = servicio.listar(ESTABLECIMIENTO_ID, EMAIL_DUENO);

        assertThat(resultado).extracting(FotoEstablecimientoResponse::fileId)
                .containsExactly("file_1", "file_2");
    }
}
