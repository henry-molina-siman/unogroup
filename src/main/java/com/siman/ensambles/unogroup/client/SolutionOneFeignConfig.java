package com.siman.ensambles.unogroup.client;

import feign.Client;
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

    /**
     * {@code capturingFeignClient} se resuelve desde el contexto principal
     * (padre del contexto propio de este cliente Feign) — un único bean
     * compartido con {@code service.SolutionOneRetryPolicy}, que lee de él
     * la transacción capturada justo después de cada llamada (Guía de
     * Transacciones HTTP §3.2).
     */
    @Bean
    public Client client(CapturingFeignClient capturingFeignClient) {
        return capturingFeignClient;
    }
}
