package payment.stripe;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** SGD amount helpers. Stripe (and the Notification Service) use integer cents. */
public final class Money {

    private Money() {
    }

    /** 40.00 -> 4000. Throws if the amount has more than 2 decimal places. */
    public static long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
}
