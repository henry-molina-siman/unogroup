package com.siman.ensambles.unogroup.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ensambles.callback")
public class CallbackProperties {

    private String orquestadorUrl;
    private Reintentos reintentos = new Reintentos();

    @Getter
    @Setter
    public static class Reintentos {
        private int maxIntentos;
        private long backoffInicialMs;
        private int backoffMultiplicador;
    }
}
