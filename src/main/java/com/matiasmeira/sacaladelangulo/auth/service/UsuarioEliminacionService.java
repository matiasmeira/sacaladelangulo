package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaEliminacionUsuario;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.TipoEliminacionCuenta;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaEliminacionUsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.exception.EstablecimientosActivosException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaCanceladaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Baja de cuenta: soft-delete + anonimización de PII, preservando integridad referencial
 * (reservas y auditoría siguen apuntando a la fila, ya anonimizada). Ver spec en
 * docs/superpowers/specs/2026-08-15-eliminacion-cuenta-design.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UsuarioEliminacionService {

    private static final List<EstadoReserva> ESTADOS_A_CANCELAR = List.of(EstadoReserva.CONFIRMADA, EstadoReserva.PENDIENTE_SENA);

    private final UsuarioRepository usuarioRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final ReservaRepository reservaRepository;
    private final AuditoriaEliminacionUsuarioRepository auditoriaEliminacionUsuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Autoeliminación: solo PLAYER/OWNER, requiere la contraseña actual. EMPLOYEE lo
     * gestiona el dueño (ver EmpleadoService); ADMIN queda fuera de alcance.
     */
    public void autoeliminar(String email, String password) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuario.getDeletedAt() != null) {
            return;
        }

        if (usuario.getRol() == Role.EMPLOYEE || usuario.getRol() == Role.ADMIN) {
            throw new AccessDeniedException("Este endpoint no está disponible para tu rol");
        }

        if (!passwordEncoder.matches(password, usuario.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        eliminar(usuario, null, TipoEliminacionCuenta.AUTOELIMINACION, false);
    }

    /**
     * Baja por ADMIN: cualquier cuenta salvo EMPLOYEE (tiene su propio ciclo de vida vía
     * EmpleadoService). No pide la contraseña del target. forzar=true saltea el guardrail
     * de OWNER con establecimientos activos, sin cascadear (quedan activos, ahora bajo el
     * dueño anonimizado).
     */
    public void eliminarComoAdmin(String emailAdmin, Long usuarioId, boolean forzar) {
        Usuario admin = usuarioRepository.findByEmail(emailAdmin)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (admin.getRol() != Role.ADMIN) {
            throw new AccessDeniedException("No está autorizado para eliminar cuentas");
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuario.getDeletedAt() != null) {
            return;
        }

        if (usuario.getRol() == Role.EMPLOYEE) {
            throw new IllegalArgumentException("Los empleados se gestionan desde el establecimiento");
        }

        eliminar(usuario, admin.getId(), TipoEliminacionCuenta.ELIMINACION_ADMIN, forzar);
    }

    private void eliminar(Usuario usuario, Long actorId, TipoEliminacionCuenta tipo, boolean forzar) {
        String detalleAuditoria = null;
        if (usuario.getRol() == Role.OWNER) {
            List<Establecimiento> activos = establecimientoRepository.findByDuenoIdAndIsActiveTrue(usuario.getId());
            if (!activos.isEmpty()) {
                if (!forzar) {
                    throw new EstablecimientosActivosException(
                            "Desactivá o transferí tus complejos antes de eliminar la cuenta");
                }
                detalleAuditoria = "Forzado: " + activos.size() + " establecimiento(s) activo(s) sin desactivar";
                log.warn("Eliminación forzada de OWNER {} con {} establecimiento(s) activo(s)",
                        usuario.getId(), activos.size());
            }
        }

        String emailReal = usuario.getEmail();
        String nombreReal = usuario.getNombre();

        List<Reserva> reservasActivas = reservaRepository.findByJugadorIdAndEstadoInAndFechaHoraInicioAfter(
                usuario.getId(), ESTADOS_A_CANCELAR, LocalDateTime.now());
        for (Reserva reserva : reservasActivas) {
            reserva.setEstado(EstadoReserva.CANCELADA);
            reservaRepository.save(reserva);
            eventPublisher.publishEvent(new ReservaCanceladaEvent(reserva.getId(), usuario.getId()));
        }

        usuario.setEmail("deleted+" + usuario.getId() + "@saque.deleted");
        usuario.setNombre("Usuario eliminado");
        usuario.setTelefono(null);
        usuario.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        usuario.setAceptaMarketing(false);
        usuario.setIsActive(false);
        usuario.setDeletedAt(LocalDateTime.now());
        usuario.setTokenVersion(usuario.getTokenVersion() + 1);
        usuarioRepository.save(usuario);

        auditoriaEliminacionUsuarioRepository.save(AuditoriaEliminacionUsuario.builder()
                .usuario(usuario)
                .actorId(actorId)
                .tipo(tipo)
                .detalle(detalleAuditoria)
                .fechaHora(LocalDateTime.now())
                .build());

        eventPublisher.publishEvent(new CuentaEliminadaEvent(emailReal, nombreReal));

        log.info("Cuenta {} eliminada ({})", usuario.getId(), tipo);
    }
}
