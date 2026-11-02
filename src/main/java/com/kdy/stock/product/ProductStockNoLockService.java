package com.kdy.stock.product;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 락도 버전 검사도 없는 차감. Lost Update 재현 전용이다.
 *
 * Product 엔티티에는 @Version이 있어서 JPA 변경 감지로 차감하면
 * UPDATE ... WHERE version = ? 가 나가고, 동시 요청은 덮어쓰기 대신 낙관적 락 예외로 실패한다.
 * 그래서 무락 상황은 JDBC로 "읽은 값 - 수량"을 그대로 덮어쓰는 방식으로 분리했다.
 */
@Service
@RequiredArgsConstructor
public class ProductStockNoLockService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void decreaseStock(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("차감 수량은 1 이상이어야 합니다.");
        }

        Integer stock;
        try {
            stock = jdbcTemplate.queryForObject(
                    "SELECT stock FROM product WHERE id = ?",
                    Integer.class,
                    productId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        }

        if (stock == null || stock < quantity) {
            throw new IllegalStateException("재고가 부족합니다.");
        }

        // 읽은 시점의 값으로 덮어쓴다. 그 사이 다른 트랜잭션이 커밋한 차감은 사라진다.
        jdbcTemplate.update(
                "UPDATE product SET stock = ? WHERE id = ?",
                stock - quantity,
                productId
        );
    }
}
