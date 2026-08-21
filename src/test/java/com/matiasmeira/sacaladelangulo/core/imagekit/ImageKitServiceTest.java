package com.matiasmeira.sacaladelangulo.core.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.models.files.FileUploadParams;
import io.imagekit.models.files.FileUploadResponse;
import io.imagekit.services.blocking.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageKitService - aislamiento del SDK de ImageKit")
class ImageKitServiceTest {

    @Mock
    private ImageKitClient imageKitClient;

    @Mock
    private FileService fileService;

    private ImageKitService imageKitService;

    @BeforeEach
    void setUp() {
        imageKitService = new ImageKitService(imageKitClient);
    }

    @Test
    @DisplayName("subir_devuelveUrlYFileId_yMandaLaCarpetaAlSdk")
    void subir_devuelveUrlYFileId_yMandaLaCarpetaAlSdk() {
        FileUploadResponse respuesta = FileUploadResponse.builder()
                .url("https://ik.imagekit.io/demo/foto_abc.jpg")
                .fileId("file_abc")
                .build();
        when(imageKitClient.files()).thenReturn(fileService);
        when(fileService.upload(any(FileUploadParams.class))).thenReturn(respuesta);

        FotoSubida resultado = imageKitService.subir(new byte[]{1, 2, 3}, "foto.jpg", "/establecimientos/7/");

        assertThat(resultado.url()).isEqualTo("https://ik.imagekit.io/demo/foto_abc.jpg");
        assertThat(resultado.fileId()).isEqualTo("file_abc");

        // folder() devuelve Optional<String>: hasValue() compara el contenido exacto.
        ArgumentCaptor<FileUploadParams> captor = ArgumentCaptor.forClass(FileUploadParams.class);
        verify(fileService).upload(captor.capture());
        assertThat(captor.getValue().folder()).hasValue("/establecimientos/7/");
    }

    @Test
    @DisplayName("subir_lanzaImageKitException_siLaRespuestaNoTraeFileId")
    void subir_lanzaImageKitException_siLaRespuestaNoTraeFileId() {
        FileUploadResponse sinFileId = FileUploadResponse.builder()
                .url("https://ik.imagekit.io/demo/foto_abc.jpg")
                .build();
        when(imageKitClient.files()).thenReturn(fileService);
        when(fileService.upload(any(FileUploadParams.class))).thenReturn(sinFileId);

        assertThatThrownBy(() -> imageKitService.subir(new byte[]{1}, "foto.jpg", "/establecimientos/7/"))
                .isInstanceOf(ImageKitException.class);
    }

    @Test
    @DisplayName("subir_envuelveLaExcepcionDelSdk_enImageKitException")
    void subir_envuelveLaExcepcionDelSdk_enImageKitException() {
        when(imageKitClient.files()).thenReturn(fileService);
        when(fileService.upload(any(FileUploadParams.class)))
                .thenThrow(new RuntimeException("timeout hablando con ImageKit"));

        assertThatThrownBy(() -> imageKitService.subir(new byte[]{1}, "foto.jpg", "/establecimientos/7/"))
                .isInstanceOf(ImageKitException.class);
    }

    @Test
    @DisplayName("borrar_delegaElFileIdAlSdk")
    void borrar_delegaElFileIdAlSdk() {
        when(imageKitClient.files()).thenReturn(fileService);

        imageKitService.borrar("file_abc");

        verify(fileService).delete("file_abc");
    }

    @Test
    @DisplayName("borrar_envuelveLaExcepcionDelSdk_enImageKitException")
    void borrar_envuelveLaExcepcionDelSdk_enImageKitException() {
        when(imageKitClient.files()).thenReturn(fileService);
        org.mockito.Mockito.doThrow(new RuntimeException("500 de ImageKit"))
                .when(fileService).delete("file_abc");

        assertThatThrownBy(() -> imageKitService.borrar("file_abc"))
                .isInstanceOf(ImageKitException.class);
    }
}
