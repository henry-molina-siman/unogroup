package com.siman.ensambles.unogroup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executor explícito de virtual threads para el hand-off "202 inmediato,
 * luego procesa" del controller — se prefiere sobre {@code @Async} porque
 * el corte de sincronía queda visible en el código del controller (no
 * detrás de un proxy AOP) y es trivial de reemplazar en tests por un
 * executor síncrono ({@code Runnable::run}).
 */
@Configuration
public class VirtualThreadConfig {

    @Bean(name = "unogroupProcessingExecutor")
    public ExecutorService unogroupProcessingExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
