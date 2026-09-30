package com.fherrmann.habits.cohabit.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Die Weboberflaeche unter {@code /cohabit/}: Dateien aus {@code static/cohabit/},
 * {@code /cohabit/rechtliches} als eigene Seite, jede andere Unterseite liefert
 * {@code index.html} (SPA - die Seite kennt ihre Routen selbst). Nie unter
 * {@code /cohabit/api/}: ein unbekannter API-Pfad bleibt ein 404 mit JSON.
 *
 * <p>{@code no-cache}: ohne Build-Kette haben die Dateien keine Hashes im Namen,
 * der Browser soll also bei jedem Laden nachfragen (ETag spart die Uebertragung).
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/cohabit/**")
                .addResourceLocations("classpath:/static/cohabit/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.equals("api") || resourcePath.startsWith("api/")) {
                            return null;
                        }
                        Resource resource = super.getResource(resourcePath, location);
                        if (resource != null) {
                            return resource;
                        }
                        String page = resourcePath.equals("rechtliches") ? "rechtliches.html" : "index.html";
                        Resource fallback = location.createRelative(page);
                        return fallback.exists() && fallback.isReadable() ? fallback : null;
                    }
                });
    }

    /** {@code /cohabit/} selbst ist fuer den Resource-Handler ein leerer Pfad - daher weiterreichen. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/cohabit", "/cohabit/");
        registry.addViewController("/cohabit/").setViewName("forward:/cohabit/index.html");
    }
}
