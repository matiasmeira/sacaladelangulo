package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Degrada automáticamente a FREE a los usuarios que quedaron en TRIAL después de que venció
 * su prueba gratuita (ver Usuario.fechaFinPrueba): hoy AvisoFinPruebaService solo notifica en
 * los umbrales de 7/3/1 día, pero nunca toca planSuscripcion, así que un dueño que no elige
 * plan pago se queda en TRIAL para siempre.
 *
 * <p>De instancia única, igual que ReservaExpiracionService, el rate limiter y
 * EmailReintentoJob: si en el futuro se escala horizontalmente, esto necesita un lock
 * compartido (ShedLock) para que dos instancias no procesen el mismo usuario dos veces.
 *
 * <p>NO es @Transactional: cada usuario se degrada en su propia transacción a través de
 * DegradacionPlanService.degradarPorVencimiento (bean aparte, REQUIRES_NEW), así que un
 * usuario que falla no aborta ni el resto del lote ni el resto de la corrida — se loguea y
 * se sigue. Vuelve a pedir la página 0 después de cada lote (en vez de avanzar con
 * page.next()) porque cada usuario procesado deja de matchear el filtro planSuscripcion=TRIAL,
 * así que el resultado se va achicando solo hasta vaciarse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiracionPruebaService {

    private final UsuarioRepository usuarioRepository;
    private final DegradacionPlanService degradacionPlanService;

    @Value("${app.suscripcion.expiracion-lote:100}")
    private int tamanioLote;

    @Scheduled(cron = "${app.suscripcion.expiracion-cron:0 0 4 * * *}")
    public void degradarPruebasVencidas() {
        Pageable pageable = PageRequest.of(0, tamanioLote);
        int totalDegradados = 0;
        int totalFallidos = 0;

        Page<Usuario> pagina = buscarVencidos(pageable);
        while (!pagina.isEmpty()) {
            for (Usuario usuario : pagina.getContent()) {
                try {
                    degradacionPlanService.degradarPorVencimiento(usuario.getId());
                    totalDegradados++;
                } catch (RuntimeException ex) {
                    totalFallidos++;
                    log.error("No se pudo degradar al usuario {} de TRIAL a FREE", usuario.getId(), ex);
                }
            }
            pagina = buscarVencidos(pageable);
        }

        if (totalDegradados > 0 || totalFallidos > 0) {
            log.info("Degradación de pruebas vencidas finalizada. Degradados: {}, fallidos: {}",
                    totalDegradados, totalFallidos);
        }
    }

    private Page<Usuario> buscarVencidos(Pageable pageable) {
        return usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, LocalDateTime.now(), pageable);
    }
}
