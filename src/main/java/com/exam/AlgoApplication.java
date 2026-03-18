package com.exam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlgoApplication {

	public static void main(String[] args) {
		configureLocalJmxRmiPort();
		SpringApplication.run(AlgoApplication.class, args);
	}

	private static void configureLocalJmxRmiPort() {
		String jmxPort = System.getProperty("com.sun.management.jmxremote.port", "").trim();
		if (jmxPort.isEmpty()) {
			return;
		}

		if (System.getProperty("com.sun.management.jmxremote.rmi.port") == null) {
			System.setProperty("com.sun.management.jmxremote.rmi.port", jmxPort);
		}

		String hostname = System.getProperty("java.rmi.server.hostname");
		if (hostname == null || hostname.isBlank()) {
			System.setProperty("java.rmi.server.hostname", "127.0.0.1");
		}
	}

}
