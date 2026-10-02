package com.matiasmeira.sacaladelangulo.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivoEmailTransportTest {

    @TempDir
    Path dir;

    @Test
    void escribeUnaLineaPorMailConLinksYCodigo() throws Exception {
        Path archivo = dir.resolve("mails.jsonl");
        var t = new ArchivoEmailTransport(archivo, new ObjectMapper());
        t.vaciar();
        t.enviar("a@x.com", "Tu código", "<p>Tu código es <b>482913</b></p><a href=\"http://localhost:3001/verificar?t=abc\">Verificar</a>");
        t.enviar("b@x.com", "Hola", "<p>sin código</p>");
        List<String> lineas = Files.readAllLines(archivo);
        assertEquals(2, lineas.size());
        JsonNode m = new ObjectMapper().readTree(lineas.get(0));
        assertEquals("a@x.com", m.get("destinatario").asText());
        assertEquals("482913", m.get("codigo").asText());
        assertEquals("http://localhost:3001/verificar?t=abc", m.get("links").get(0).asText());
        assertTrue(new ObjectMapper().readTree(lineas.get(1)).get("codigo").isNull());
    }

    @Test
    void vaciarBorraLasCorridasAnteriores() throws Exception {
        Path archivo = dir.resolve("mails.jsonl");
        Files.writeString(archivo, "{\"viejo\":true}\n");
        var t = new ArchivoEmailTransport(archivo, new ObjectMapper());
        t.vaciar();
        assertEquals(0, Files.readAllLines(archivo).size());
    }
}
