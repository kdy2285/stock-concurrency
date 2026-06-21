package com.kdy.stock.product;

import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import static java.lang.Thread.sleep;

@Service
@RequiredArgsConstructor
public class ProductStockOptimisticLockService {

    private final ProductStockOptimisticLockTxService txService;

    public void decreaseStock(Long productId, int quantity) {
        int maxRetryCount = 100;

        for (int i = 0; i < maxRetryCount; i++) {
            try {
                txService.decreaseStockOnce(productId, quantity);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                sleep(10);
            }
        }

        throw new IllegalStateException("재고 차감 재시도 횟수를 초과했습니다.");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트가 발생했습니다.");
        }
    }
}
