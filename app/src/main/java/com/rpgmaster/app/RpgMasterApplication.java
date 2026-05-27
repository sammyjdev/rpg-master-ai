package com.rpgmaster.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.rpgmaster.app.config")
public class RpgMasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RpgMasterApplication.class, args);
    }
}
