package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.CodigoVerificacion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

/**
 * Servicio de negocio para gestión de verificación de teléfono mediante OTP.
 */
@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final CodigoVerificacionRepository codigoVerificacionRepository;
    private final Random random = new Random();

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

        // Crear y guardar el código con expiración a 5 minutos
        CodigoVerificacion codigoVerificacion = CodigoVerificacion.builder()
                .email(email)
                .codigo(codigo)
                .telefonoPendiente(telefono)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(5))
                .build();

        codigoVerificacionRepository.save(codigoVerificacion);

        // Simular envío de SMS
        System.out.println("Simulando SMS al " + telefono + ": Tu código es " + codigo);
    }

    /**
     * Verifica un código OTP y vincula el teléfono al usuario si es válido.
     *
     * @param email Email del usuario autenticado
     * @param codigo Código OTP a verificar
     */
    public void verificarCodigo(String email, String codigo) {
        // Buscar el código en la base de datos
        Optional<CodigoVerificacion> codigoOpt = codigoVerificacionRepository.findByEmail(email);

        if (codigoOpt.isEmpty()) {
            throw new IllegalArgumentException("Código inválido o expirado");
        }

        CodigoVerificacion codigoVerificacion = codigoOpt.get();

        // Validar que el código coincida y no esté expirado
        if (!codigoVerificacion.getCodigo().equals(codigo) ||
                LocalDateTime.now().isAfter(codigoVerificacion.getFechaExpiracion())) {
            throw new IllegalArgumentException("Código inválido o expirado");
        }

        // Buscar el usuario y actualizar su teléfono
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        usuario.setTelefono(codigoVerificacion.getTelefonoPendiente());
        usuario.setTelefonoVerificado(true);

        usuarioRepository.save(usuario);

        // Eliminar el código de la base de datos
        codigoVerificacionRepository.deleteByEmail(email);
    }
}
