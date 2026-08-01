package com.matiasmeira.sacaladelangulo.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;

/**
 * Habilita @Async, usado hoy por RegistroVerificacionEmailListener para desacoplar el
 * envío del email de verificación de la transacción que crea el token (ver A12 en la
 * auditoría): sin esto, el envío se ejecutaría igual pero de forma sincrónica.
 *
 * Con LogEmailService (solo loguea) el executor por defecto de Spring y no manejar
 * excepciones nunca importó, porque loguear no puede fallar. Con un envío real (ver
 * ResendEmailService) sí puede fallar (timeout, rate limit, credenciales inválidas), así
 * que se define un pool acotado (en vez del SimpleAsyncTaskExecutor sin límite por
 * defecto) y un manejador de excepciones no capturadas (en vez de perderlas como
 * "uncaught exception" sin ningún log estructurado).
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean
    public TaskExecutor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
                "Excepción no capturada en método @Async {}.{} con argumentos {}",
                method.getDeclaringClass().getSimpleName(), method.getName(), Arrays.toString(params), ex);
    }
}
