package com.matiasmeira.sacaladelangulo.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Todos los CUITs "válidos" de este test se calcularon con el mismo algoritmo módulo 11 que
 * implementa CuitUtils (no son inventados) -- ver el dígito verificador de cada uno.
 */
@DisplayName("CuitUtils - Normalización y validación de CUIT")
class CuitUtilsTest {

    @ParameterizedTest(name = "esValido_CuitReal_{0}_Valido")
    @ValueSource(strings = {
            "20123456786", // persona física (prefijo 20)
            "23234567896", // persona física (prefijo 23)
            "24345678901", // persona física (prefijo 24)
            "27276543216", // persona física (prefijo 27)
            "30712345671", // persona jurídica / SRL (prefijo 30)
            "33334567899", // persona jurídica / SRL (prefijo 33)
    })
    @DisplayName("esValido_CuitsRealesDeDistintosPrefijos_Valido")
    void esValido_CuitsRealesDeDistintosPrefijos_Valido(String cuit) {
        assertTrue(CuitUtils.esValido(cuit));
    }

    @Test
    @DisplayName("esValido_DigitoVerificadorIncorrecto_Invalido")
    void esValido_DigitoVerificadorIncorrecto_Invalido() {
        // Mismo cuerpo que el CUIT válido "20123456786", último dígito cambiado de 6 a 0.
        assertFalse(CuitUtils.esValido("20123456780"));
    }

    @Test
    @DisplayName("esValido_LongitudCorta_Invalido")
    void esValido_LongitudCorta_Invalido() {
        assertFalse(CuitUtils.esValido("2012345678"));
    }

    @Test
    @DisplayName("esValido_LongitudLarga_Invalido")
    void esValido_LongitudLarga_Invalido() {
        assertFalse(CuitUtils.esValido("201234567860"));
    }

    @Test
    @DisplayName("esValido_ConGuiones_Valido")
    void esValido_ConGuiones_Valido() {
        assertTrue(CuitUtils.esValido("20-12345678-6"));
    }

    @Test
    @DisplayName("esValido_ConCaracteresNoNumericos_Invalido")
    void esValido_ConCaracteresNoNumericos_Invalido() {
        assertFalse(CuitUtils.esValido("20-1234567X-6"));
    }

    @Test
    @DisplayName("esValido_Null_Invalido")
    void esValido_Null_Invalido() {
        assertFalse(CuitUtils.esValido(null));
    }

    @Test
    @DisplayName("esValido_RestoDaOnce_MapeaDigitoVerificadorACero_Valido")
    void esValido_RestoDaOnce_MapeaDigitoVerificadorACero_Valido() {
        // "2012345684": 11 - (suma % 11) == 11 -> el dígito verificador real es 0, no 11.
        assertTrue(CuitUtils.esValido("20123456840"));
    }

    @Test
    @DisplayName("esValido_RestoDaDiez_CombinacionNoEmitida_Invalido")
    void esValido_RestoDaDiez_CombinacionNoEmitida_Invalido() {
        // "2012345693": 11 - (suma % 11) == 10 -> no se emite en Argentina. Ningún dígito
        // final lo vuelve válido (ni el "9" al que alguien podría querer mapearlo por error).
        assertFalse(CuitUtils.esValido("20123456930"));
        assertFalse(CuitUtils.esValido("20123456939"));
        assertFalse(CuitUtils.esValido("20123456935"));
    }

    @Test
    @DisplayName("normalizar_ConGuiones_DejaSoloDigitos")
    void normalizar_ConGuiones_DejaSoloDigitos() {
        assertEquals("20123456786", CuitUtils.normalizar("20-12345678-6"));
    }

    @Test
    @DisplayName("normalizar_Null_DevuelveNull")
    void normalizar_Null_DevuelveNull() {
        assertNull(CuitUtils.normalizar(null));
    }
}
