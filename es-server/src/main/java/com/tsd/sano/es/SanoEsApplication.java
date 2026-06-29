package com.tsd.sano.es;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SanoEsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SanoEsApplication.class, args);
    }

}
