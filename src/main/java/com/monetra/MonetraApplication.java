package com.monetra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonetraApplication {

	public static void main(String[] args) {
		SpringApplication.run(MonetraApplication.class, args);
	}

}
