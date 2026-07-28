package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Libera periódicamente las reservas de jugador cuya ventana de 10 minutos para
 * confirmar/pagar la seña venció sin acción (ver ReservaService.crearReserva y
 * Reserva.expiraEn). Corre cada 1 minuto para que la auditoría (estado
 * CANCELADA_PRERESERVA) refleje el vencimiento con poca demora; la disponibilidad
 * en sí ya deja de bloquearse apenas vence, sin depender de este job (ver
 * ReservaRepository.findSuperpuestas).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservaExpiracionService {

    private final ReservaRepository reservaRepository;

    @Scheduled(fixedRate = 60 * 1000L)
    @Transactional
    public void liberarReservasVencidas() {
        int liberadas = reservaRepository.liberarReservasVencidas(LocalDateTime.now());
        if (liberadas > 0) {
            log.info("Liberación automática de reservas: {} reservas vencidas pasadas a CANCELADA_PRERESERVA", liberadas);
        }
    }
}
