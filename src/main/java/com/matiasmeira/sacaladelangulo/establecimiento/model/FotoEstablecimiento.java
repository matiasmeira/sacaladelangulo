package com.matiasmeira.sacaladelangulo.establecimiento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una foto del establecimiento: la URL con la que se sirve y el fileId de ImageKit con el
 * que se la borra y se la identifica en los endpoints de gestión.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class FotoEstablecimiento {

    @Column(name = "foto_url", nullable = false, length = 1000)
    private String url;

    /**
     * Nullable a propósito: las fotos cargadas a mano contra la base antes de integrar
     * ImageKit no tienen fileId. Quedan visibles en el marketplace pero no se pueden
     * gestionar por API (no son direccionables) — ver el spec, "Decisiones descartadas".
     */
    @Column(name = "file_id", length = 255)
    private String fileId;
}
