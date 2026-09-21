package com.matiasmeira.sacaladelangulo.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RedSocialUrlUtils - Validación permisiva de URL de Instagram/Facebook")
class RedSocialUrlUtilsTest {

    @ParameterizedTest(name = "esUrlValida_{0}_Valida")
    @ValueSource(strings = {
            "https://www.instagram.com/miclub",
            "http://www.instagram.com/miclub",
            "https://instagram.com/miclub",
            "instagram.com/miclub",
            "www.instagram.com/miclub",
            "instagram.com",
            "https://www.facebook.com/miclub",
            "facebook.com/miclub/",
            "https://fb.com/miclub",
            "fb.com/miclub",
            "HTTPS://WWW.INSTAGRAM.COM/MiClub",
    })
    @DisplayName("esUrlValida_PerfilesDeInstagramOFacebookEnCualquierFormato_Valida")
    void esUrlValida_PerfilesDeInstagramOFacebookEnCualquierFormato_Valida(String url) {
        assertTrue(RedSocialUrlUtils.esUrlValida(url));
    }

    @ParameterizedTest(name = "esUrlValida_{0}_Invalida")
    @ValueSource(strings = {
            "https://www.tiktok.com/@miclub",
            "https://twitter.com/miclub",
            "no es una url",
            "instagramcom/miclub",
            "https://notinstagram.com/miclub",
            "https://instagram.com.evil.com/miclub",
    })
    @DisplayName("esUrlValida_OtroDominioOTextoInvalido_Invalida")
    void esUrlValida_OtroDominioOTextoInvalido_Invalida(String url) {
        assertFalse(RedSocialUrlUtils.esUrlValida(url));
    }

    @Test
    @DisplayName("esUrlValida_Null_Invalida")
    void esUrlValida_Null_Invalida() {
        assertFalse(RedSocialUrlUtils.esUrlValida(null));
    }

    @Test
    @DisplayName("esUrlValida_Blank_Invalida")
    void esUrlValida_Blank_Invalida() {
        assertFalse(RedSocialUrlUtils.esUrlValida("   "));
    }
}
