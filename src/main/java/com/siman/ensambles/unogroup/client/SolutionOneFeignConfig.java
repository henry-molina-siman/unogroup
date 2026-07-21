package com.siman.ensambles.unogroup.client;

import feign.Retryer;
import feign.codec.Encoder;
import org.springframework.context.annotation.Bean;

/**
 * Sin Retryer propio de Feign — la política de reintentos hacia Solution
 * One vive por completo en {@code service.SolutionOneRetryPolicy}, no aquí,
 * para no duplicar la lógica en dos capas (Diseño §2.11).
 */
public class SolutionOneFeignConfig {

    @Bean
    public Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Encoder encoder() {
        return new SolutionOneBinaryEncoder();
    }
}
