package com.kdy.stock.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ProductStockSynchronizedService {

    private final ProductRepository productRepository;
    private final TransactionTemplate transactionTemplate;

    private final Object stockLock = new Object();

    public void decreaseStock(Long productId, int quantity) {
        synchronized (stockLock) {
            transactionTemplate.executeWithoutResult(status -> {
                Product product = productRepository.findById(productId)
                        .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다"));

                product.decreaseStock(quantity);
            });
        }
    }
}
