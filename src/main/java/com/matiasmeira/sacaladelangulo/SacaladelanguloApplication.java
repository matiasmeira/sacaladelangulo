package com.matiasmeira.sacaladelangulo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class SacaladelanguloApplication {

	public static void main(String[] args) {
		// Se fija antes de SpringApplication.run(...), no en un @PostConstruct: el orden
		// de inicialización de beans no está definido respecto de otros beans sin
		// dependencia explícita, así que un @PostConstruct en este mismo bean no
		// garantiza que la zona horaria ya esté aplicada cuando se inicialicen el
		// DataSource/Hibernate u otro código que dependa del default de la JVM
		// (ver M13 en la auditoría: sin esto, LocalDateTime.now() en toda la app
		// queda a merced de la zona horaria del sistema operativo/contenedor).
		TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
		SpringApplication.run(SacaladelanguloApplication.class, args);
	}

}
