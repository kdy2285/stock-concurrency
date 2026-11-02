package com.kdy.stock.product;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;

    /**
     * 명시적 락은 없지만 Product의 @Version 때문에 변경 감지 UPDATE에 버전 조건이 붙는다.
     * 즉 "재시도 없는 낙관적 락"으로 동작한다. 동시 요청 중 일부는 덮어쓰지 않고 예외로 실패한다.
     */
    @Transactional
    public void decreaseStock(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));

        product.decreaseStock(quantity);
    }

    @Transactional(readOnly = true)
    public int getStock(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));

        return product.getStock();
    }
}
