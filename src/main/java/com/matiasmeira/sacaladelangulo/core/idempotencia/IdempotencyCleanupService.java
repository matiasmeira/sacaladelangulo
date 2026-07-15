package com.matiasmeira.sacaladelangulo.core.idempotencia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Elimina periódicamente las claves de idempotencia viejas para que la tabla
 * no crezca indefinidamente. Una vez pasada la ventana de retención ya no
 * tiene sentido seguir recordando la respuesta: un reintento tan tardío del
 * cliente se trata como una solicitud nueva.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyCleanupService {

    private static final Duration RETENCION = Duration.ofHours(24);

    private final SolicitudIdempotenteRepository solicitudIdempotenteRepository;

    @Scheduled(fixedRate = 60 * 60 * 1000L)
    @Transactional
    public void limpiarClavesExpiradas() {
        LocalDateTime limite = LocalDateTime.now().minus(RETENCION);
        int eliminadas = solicitudIdempotenteRepository.borrarExpiradas(limite);
        if (eliminadas > 0) {
            log.info("Limpieza de idempotencia: {} claves expiradas eliminadas", eliminadas);
        }
    }
}
