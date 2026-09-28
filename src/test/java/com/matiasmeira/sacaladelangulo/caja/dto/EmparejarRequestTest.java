package com.matiasmeira.sacaladelangulo.caja.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EmparejarRequest - Validación de bean del label")
class EmparejarRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void cerrarValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("label_De40Caracteres_SinViolaciones")
    void label_De40Caracteres_SinViolaciones() {
        String label40 = "a".repeat(40);

        Set<ConstraintViolation<EmparejarRequest>> violaciones =
                validator.validate(new EmparejarRequest(label40));

        assertTrue(violaciones.isEmpty());
    }

    @Test
    @DisplayName("label_De41Caracteres_UnaViolacionConMensaje")
    void label_De41Caracteres_UnaViolacionConMensaje() {
        String label41 = "a".repeat(41);

        Set<ConstraintViolation<EmparejarRequest>> violaciones =
                validator.validate(new EmparejarRequest(label41));

        assertEquals(1, violaciones.size());
        ConstraintViolation<EmparejarRequest> violacion = violaciones.iterator().next();
        assertEquals("label", violacion.getPropertyPath().toString());
        assertEquals("El nombre no puede tener más de 40 caracteres", violacion.getMessage());
    }

    @Test
    @DisplayName("label_Null_SinViolaciones")
    void label_Null_SinViolaciones() {
        Set<ConstraintViolation<EmparejarRequest>> violaciones =
                validator.validate(new EmparejarRequest(null));

        assertTrue(violaciones.isEmpty());
    }
}
