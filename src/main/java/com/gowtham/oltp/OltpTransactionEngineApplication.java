package com.gowtham.oltp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class OltpTransactionEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(OltpTransactionEngineApplication.class, args);
    }
}
