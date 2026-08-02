package com.matiasmeira.sacaladelangulo.caja.service;

import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispositivoCajaGate - Tests de lectura de la cookie de dispositivo")
class DispositivoCajaGateTest {

    @Mock
    private DispositivoCajaService dispositivoCajaService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private DispositivoCajaGate dispositivoCajaGate;

    @Test
    @DisplayName("exigirDispositivo_SinCookies_LanzaAccessDenied")
    void exigirDispositivo_SinCookies_LanzaAccessDenied() {
        when(request.getCookies()).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> dispositivoCajaGate.exigirDispositivo(request));
    }

    @Test
    @DisplayName("exigirDispositivo_SinLaCookieDeDispositivo_LanzaAccessDenied")
    void exigirDispositivo_SinLaCookieDeDispositivo_LanzaAccessDenied() {
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("otra_cookie", "valor")});

        assertThrows(AccessDeniedException.class, () -> dispositivoCajaGate.exigirDispositivo(request));
    }

    @Test
    @DisplayName("exigirDispositivo_ConCookieValida_DelegaEnElServicio")
    void exigirDispositivo_ConCookieValida_DelegaEnElServicio() {
        Establecimiento establecimiento = Establecimiento.builder().id(10L).build();
        DispositivoCaja dispositivo = DispositivoCaja.builder().id(1L).establecimiento(establecimiento).build();

        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("saque_caja_device", "token-crudo")});
        when(dispositivoCajaService.validarToken("token-crudo")).thenReturn(dispositivo);

        DispositivoCaja resultado = dispositivoCajaGate.exigirDispositivo(request);

        assertEquals(10L, resultado.getEstablecimiento().getId());
    }
}
