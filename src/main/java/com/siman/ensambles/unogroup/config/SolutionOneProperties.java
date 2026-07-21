package com.siman.ensambles.unogroup.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ensambles.adapter.solutionone")
public class SolutionOneProperties {

    private String baseUrl;
    private String tokenPath;
    private String uploadPath;
    private String usuario;
    private String password;
    private String rutaBase;
    private boolean mkdirParents;
    private Reintentos reintentos = new Reintentos();

    @Getter
    @Setter
    public static class Reintentos {
        private int maxIntentos;
        private long backoffInicialMs;
        private int backoffMultiplicador;
    }
}
