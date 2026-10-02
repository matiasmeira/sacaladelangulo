package com.matiasmeira.sacaladelangulo.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matiasmeira.sacaladelangulo.core.email.EmailTransport;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transporte del perfil e2e: en vez de enviar, agrega una línea JSON por mail a un archivo
 * (destinatario, asunto, links, código de 6 dígitos si lo hay, fecha) para que los tests
 * e2e lo lean. Se vacía al arrancar.
 */
@Service
@Primary
@Profile("e2e")
class ArchivoEmailTransport implements EmailTransport {

    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETIQUETA = Pattern.compile("<[^>]+>");
    private static final Pattern CODIGO = Pattern.compile("\\b\\d{6}\\b");

    private final Path archivo;
    private final ObjectMapper mapper;

    @Autowired
    ArchivoEmailTransport(@Value("${app.e2e.mails-archivo:target-e2e/mails.jsonl}") String ruta, ObjectMapper mapper) {
        this(Path.of(ruta), mapper);
    }

    ArchivoEmailTransport(Path archivo, ObjectMapper mapper) {
        this.archivo = archivo;
        this.mapper = mapper;
    }

    @PostConstruct
    void vaciar() {
        try {
            crearDirectorio();
            Files.write(archivo, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void enviar(String destinatario, String asunto, String htmlBody) {
        String html = htmlBody == null ? "" : htmlBody;
        ObjectNode mail = mapper.createObjectNode();
        mail.put("destinatario", destinatario);
        mail.put("asunto", asunto);
        ArrayNode links = mail.putArray("links");
        Matcher href = HREF.matcher(html);
        while (href.find()) {
            links.add(HtmlUtils.htmlUnescape(href.group(1)));
        }
        Matcher codigo = CODIGO.matcher(ETIQUETA.matcher(html).replaceAll(" "));
        if (codigo.find()) {
            mail.put("codigo", codigo.group());
        } else {
            mail.putNull("codigo");
        }
        mail.put("fecha", OffsetDateTime.now().toString());
        try {
            crearDirectorio();
            Files.writeString(archivo, mapper.writeValueAsString(mail) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void crearDirectorio() throws IOException {
        Path padre = archivo.toAbsolutePath().getParent();
        if (padre != null) {
            Files.createDirectories(padre);
        }
    }
}
