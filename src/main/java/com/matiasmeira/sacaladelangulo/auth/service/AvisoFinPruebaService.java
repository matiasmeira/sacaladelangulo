package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Avisa a los usuarios en período de prueba cuando les quedan 7, 3 o 1 día antes de que
 * fechaFinPrueba venza (ver Usuario.fechaFinPrueba), para que puedan actuar antes del corte.
 * Corre 1 vez por día: los umbrales son granulares a nivel de día calendario, así que no
 * hace falta una frecuencia mayor (a diferencia de ReservaExpiracionService, que sí necesita
 * reaccionar en minutos).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvisoFinPruebaService {

    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedRate = 24 * 60 * 60 * 1000L)
    @Transactional
    public void avisarFinDePrueba() {
        procesarUmbral7();
        procesarUmbral3();
        procesarUmbral1();
    }

    private void procesarUmbral7() {
        LocalDateTime[] rango = rangoParaUmbral(7);
        List<Usuario> usuarios = usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(
                rango[0], rango[1]);
        if (usuarios.isEmpty()) {
            return;
        }

        List<Usuario> aGuardar = new ArrayList<>();
        for (Usuario usuario : usuarios) {
            eventPublisher.publishEvent(new AvisoFinPruebaEvent(usuario.getId(), 7));
            usuario.setAvisoFinPrueba7Enviado(true);
            aGuardar.add(usuario);
        }
        usuarioRepository.saveAll(aGuardar);
        log.info("Aviso de fin de prueba (7 días): {} usuarios notificados", aGuardar.size());
    }

    private void procesarUmbral3() {
        LocalDateTime[] rango = rangoParaUmbral(3);
        List<Usuario> usuarios = usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba3EnviadoFalseAndDeletedAtIsNull(
                rango[0], rango[1]);
        if (usuarios.isEmpty()) {
            return;
        }

        List<Usuario> aGuardar = new ArrayList<>();
        for (Usuario usuario : usuarios) {
            eventPublisher.publishEvent(new AvisoFinPruebaEvent(usuario.getId(), 3));
            usuario.setAvisoFinPrueba3Enviado(true);
            aGuardar.add(usuario);
        }
        usuarioRepository.saveAll(aGuardar);
        log.info("Aviso de fin de prueba (3 días): {} usuarios notificados", aGuardar.size());
    }

    private void procesarUmbral1() {
        LocalDateTime[] rango = rangoParaUmbral(1);
        List<Usuario> usuarios = usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba1EnviadoFalseAndDeletedAtIsNull(
                rango[0], rango[1]);
        if (usuarios.isEmpty()) {
            return;
        }

        List<Usuario> aGuardar = new ArrayList<>();
        for (Usuario usuario : usuarios) {
            eventPublisher.publishEvent(new AvisoFinPruebaEvent(usuario.getId(), 1));
            usuario.setAvisoFinPrueba1Enviado(true);
            aGuardar.add(usuario);
        }
        usuarioRepository.saveAll(aGuardar);
        log.info("Aviso de fin de prueba (1 día): {} usuarios notificados", aGuardar.size());
    }

    /**
     * Devuelve [desde, hasta) delimitando el día calendario que cae exactamente a "dias"
     * días desde ahora, para que la comparación no dependa de la hora exacta en la que corre
     * el job.
     */
    private LocalDateTime[] rangoParaUmbral(int dias) {
        LocalDateTime desde = LocalDateTime.now().plusDays(dias).truncatedTo(ChronoUnit.DAYS);
        LocalDateTime hasta = desde.plusDays(1);
        return new LocalDateTime[]{desde, hasta};
    }
}
