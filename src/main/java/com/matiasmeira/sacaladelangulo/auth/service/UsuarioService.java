package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.PerfilMapper;
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
import com.matiasmeira.sacaladelangulo.auth.model.CodigoVerificacion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.security.TokenHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Servicio de negocio para gestión de verificación de teléfono mediante OTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UsuarioService {

    /**
     * Cantidad máxima de intentos fallidos permitidos antes de invalidar el código
     * y exigir solicitar uno nuevo (mitiga fuerza bruta sobre el OTP de 6 dígitos).
     */
    private static final int MAX_INTENTOS = 5;

    private final UsuarioRepository usuarioRepository;
    private final CodigoVerificacionRepository codigoVerificacionRepository;
    private final PerfilMapper perfilMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * Solicita un código OTP para verificar un número de teléfono.
     *
     * @param email Email del usuario autenticado
     * @param telefono Número de teléfono a verificar
     */
    public void solicitarCodigo(String email, String telefono) {
        // Eliminar códigos anteriores
        codigoVerificacionRepository.deleteByEmail(email);

        // Generar código aleatorio de 6 dígitos
        String codigo = String.format("%06d", random.nextInt(1000000));

        // Crear y guardar el código con expiración a 5 minutos. Solo se persiste el hash
        // (ver M-05 en la auditoría): el valor crudo únicamente viaja por SMS/log de dev.
        CodigoVerificacion codigoVerificacion = CodigoVerificacion.builder()
                .email(email)
                .codigoHash(TokenHasher.sha256Hex(codigo))
                .telefonoPendiente(telefono)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(5))
                .build();

        try {
            codigoVerificacionRepository.saveAndFlush(codigoVerificacion);
        } catch (DataIntegrityViolationException ex) {
            // El deleteByEmail + save no es atómico: dos solicitudes casi simultáneas para
            // el mismo email pueden pasar ambas el delete antes de que cualquiera inserte.
            // La constraint única sobre "email" (ver CodigoVerificacion) evita que queden dos
            // filas vivas para el mismo usuario; acá se traduce en un mensaje claro en vez de
            // un 500 no controlado.
            log.debug("Carrera al solicitar código OTP para {}", email);
            throw new IllegalArgumentException("Ya se generó un código recientemente. Esperá unos segundos e intentá de nuevo.");
        }

        // TODO: integrar proveedor real de SMS. Nunca loguear el código en INFO/producción.
        log.debug("Código OTP generado para {} (envío de SMS simulado)", email);
    }

    /**
     * Verifica un código OTP y vincula el teléfono al usuario si es válido.
     *
     * @param email Email del usuario autenticado
     * @param codigo Código OTP a verificar
     */
    public void verificarCodigo(String email, String codigo) {
        // Buscar el código en la base de datos
        CodigoVerificacion codigoVerificacion = codigoVerificacionRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Código inválido o expirado"));

        if (LocalDateTime.now().isAfter(codigoVerificacion.getFechaExpiracion())) {
            codigoVerificacionRepository.deleteByEmail(email);
            throw new IllegalArgumentException("Código inválido o expirado");
        }

        if (codigoVerificacion.getIntentos() >= MAX_INTENTOS) {
            codigoVerificacionRepository.deleteByEmail(email);
            log.warn("OTP bloqueado por exceso de intentos para {}", email);
            throw new IllegalArgumentException("Se superó el número máximo de intentos. Solicitá un nuevo código.");
        }

        if (!codigoVerificacion.getCodigoHash().equals(TokenHasher.sha256Hex(codigo))) {
            codigoVerificacion.setIntentos(codigoVerificacion.getIntentos() + 1);
            codigoVerificacionRepository.save(codigoVerificacion);
            throw new IllegalArgumentException("Código inválido o expirado");
        }

        // Buscar el usuario y actualizar su teléfono
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        usuario.setTelefono(codigoVerificacion.getTelefonoPendiente());
        usuario.setTelefonoVerificado(true);
        usuario.setIsActive(true);

        usuarioRepository.save(usuario);

        // Eliminar el código de la base de datos
        codigoVerificacionRepository.deleteByEmail(email);
    }

    /**
     * Perfil del usuario autenticado (GET /api/v1/usuarios/me).
     *
     * @param email Email del usuario autenticado
     */
    public PerfilResponse obtenerPerfil(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        return perfilMapper.mapToResponse(usuario);
    }
}
