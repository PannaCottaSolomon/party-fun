package events;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableRetry  // activates @Retryable (BookingReservationService.reserve)
@EnableScheduling
public class EventsApplication {

	// Builds the Spring application context and starts the embedded web server (port 8080 by default).
	public static void main(String[] args) {
		SpringApplication.run(EventsApplication.class, args);

		// Printed once the application context has started successfully.
		System.out.println("Events Microservice launched successfully!");

	}

}
