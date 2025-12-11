package io.basquiat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.reactive.config.EnableWebFlux;

@EnableWebFlux
@SpringBootApplication
public class BasquiatApplication {

	public static void main(String[] args) {
		SpringApplication.run(BasquiatApplication.class, args);
	}

}