package com.ledgerline.anomaly;

import com.ledgerline.config.AsyncConfig;
import com.ledgerline.money.MoneyRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evaluates the anomaly rules only after the money transaction has committed, on a separate thread and in its own
 * transaction ({@link AnomalyService#evaluate} is REQUIRES_NEW). A failure here can never roll back or block a
 * money movement: it is caught and logged.
 */
@Component
public class AnomalyListener {

    private static final Logger log = LoggerFactory.getLogger(AnomalyListener.class);

    private final AnomalyService anomalyService;

    public AnomalyListener(AnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @Async(AsyncConfig.ANOMALY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMoneyRecorded(MoneyRecordedEvent event) {
        try {
            anomalyService.evaluate(event.transactionId());
        } catch (RuntimeException e) {
            log.warn("Anomaly evaluation failed for transaction {}: {}", event.transactionId(), e.getMessage());
        }
    }
}
