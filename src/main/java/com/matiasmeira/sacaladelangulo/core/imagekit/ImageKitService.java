package com.matiasmeira.sacaladelangulo.core.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.models.files.FileUploadParams;
import io.imagekit.models.files.FileUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Única clase del proyecto que toca el SDK de ImageKit. El resto del código habla de
 * FotoSubida y de fileIds, sin conocer los tipos del SDK: si mañana se cambia de
 * proveedor, el cambio queda contenido acá.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageKitService {

    private final ImageKitClient imageKitClient;

    /**
     * Sube el archivo y devuelve la URL pública con la que se sirve más el fileId con el
     * que después se lo borra.
     *
     * useUniqueFileName(true): ImageKit le agrega un sufijo al nombre, así que dos fotos
     * con el mismo nombre de archivo no se pisan entre sí.
     */
    public FotoSubida subir(byte[] contenido, String nombreArchivo, String carpeta) {
        FileUploadResponse respuesta;
        try {
            respuesta = imageKitClient.files().upload(FileUploadParams.builder()
                    .file(contenido)
                    .fileName(nombreArchivo)
                    .folder(carpeta)
                    .useUniqueFileName(true)
                    .build());
        } catch (RuntimeException ex) {
            throw new ImageKitException("Falló la subida del archivo a ImageKit", ex);
        }

        // url() y fileId() son Optional en el SDK. Sin los dos la foto no se puede ni
        // mostrar ni borrar después, así que se corta acá en vez de persistir una fila
        // inutilizable.
        String url = respuesta.url()
                .orElseThrow(() -> new ImageKitException("ImageKit no devolvió la url del archivo subido"));
        String fileId = respuesta.fileId()
                .orElseThrow(() -> new ImageKitException("ImageKit no devolvió el fileId del archivo subido"));

        return new FotoSubida(url, fileId);
    }

    public void borrar(String fileId) {
        try {
            imageKitClient.files().delete(fileId);
        } catch (RuntimeException ex) {
            throw new ImageKitException("Falló el borrado del archivo " + fileId + " en ImageKit", ex);
        }
    }
}
