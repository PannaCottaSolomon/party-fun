package payment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Small infrastructure beans that services take as constructor arguments, so tests can replace them. Scheduling is
 * enabled for {@code RefundRecoveryJob}.
 */
@Configuration
@EnableScheduling
public class AppConfig {

    /** Database timestamps use the service's local time zone, like MySQL CURRENT_TIMESTAMP (UTC in containers). */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Programmatic transactions. Used where a Stripe call must happen between two short
     * transactions, so no database transaction is held open during a network call.
     */
    @Bean
    public TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
