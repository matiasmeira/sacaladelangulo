package com.matiasmeira.sacaladelangulo.core.imagekit;

import io.imagekit.client.ImageKitClient;
import io.imagekit.client.okhttp.ImageKitOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Único punto donde se construye el cliente del SDK de ImageKit. El SDK recomienda un
 * solo cliente por aplicación (tiene su propio pool de conexiones y de hilos), así que va
 * como bean singleton.
 */
@Configuration
public class ImageKitConfig {

    /**
     * Siempre se llama a privateKey(), aunque el valor sea vacío: omitir el setter hace
     * que build() lance IllegalStateException("`privateKey` is required, but was not
     * set") y tumbe el arranque. Con la clave vacía el cliente se construye bien y falla
     * recién al llamar a la API, que es el comportamiento que se quiere en dev/tests.
     */
    @Bean
    public ImageKitClient imageKitClient(@Value("${imagekit.private-key}") String privateKey) {
        return ImageKitOkHttpClient.builder()
                .privateKey(privateKey)
                .build();
    }
}
