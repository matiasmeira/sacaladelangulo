package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Nuevo orden de las fotos, por fileId. La primera de la lista pasa a ser la foto
 * principal de la card pública.
 */
public record ReordenarFotosRequest(
        @NotEmpty(message = "Hay que mandar la lista de fileIds en el nuevo orden.")
        List<String> fileIds
) {
}
