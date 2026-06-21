package com.kdy.stock.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductStockConcurrencyTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductStockService productStockService;

    @Autowired
    private ProductStockSynchronizedService productStockSynchronizedService;

    @Autowired
    private ProductStockPessimisticLockService productStockPessimisticLockService;

    @Autowired
    private ProductStockOptimisticLockService productStockOptimisticLockService;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @Disabled("락 없는 단순 차감 방식의 Lost Update 재현용 실패 테스트")
    @Test
    @DisplayName("락 없이 동시에 재고를 차감하면 재고 정합성이 깨진다")
    void decreaseStockWithoutLock_RaceCondition() throws InterruptedException {
        // given
        int threadCount = 100;
        Product product = productRepository.saveAndFlush(
                new Product("테스트 상품", threadCount)
        );

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    productStockService.decreaseStock(product.getId(), 1);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Product result = productRepository.findById(product.getId())
                .orElseThrow();

        int actualStock = result.getStock();

        assertThat(actualStock)
                .as("최종 재고 = %d", actualStock)
                .isZero();
    }

    @Disabled("synchronized 테스트")
    @Test
    @DisplayName("synchronized를 적용하면 단일 서버에서 재고 정합성이 보장된다")
    void decreaseStockWithSynchronized_Success() throws InterruptedException {
        // given
        int threadCount = 100;
        Product product = productRepository.saveAndFlush(
                new Product("테스트 상품", threadCount)
        );

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    productStockSynchronizedService.decreaseStock(product.getId(), 1);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Product result = productRepository.findById(product.getId())
                .orElseThrow();

        int actualStock = result.getStock();

        assertThat(actualStock)
                .as("최종 재고 = %d", actualStock)
                .isZero();
    }

    @Test
    @DisplayName("Pessimistic Lock을 적용하면 동시 차감 시 재고 정합성이 보장된다")
    void decreaseStockWithPessimisticLock_Success() throws InterruptedException {
        // given
        int threadCount = 100;
        Product product = productRepository.saveAndFlush(
                new Product("테스트 상품", threadCount)
        );

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    productStockPessimisticLockService.decreaseStock(product.getId(), 1);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Product result = productRepository.findById(product.getId())
                .orElseThrow();

        int actualStock = result.getStock();

        assertThat(actualStock)
                .as("최종 재고 = %d", actualStock)
                .isZero();
    }

    @Test
    @DisplayName("Optimistic Lock을 적용하면 충돌을 감지하고 재시도하여 재고 정합성이 보장된다")
    void decreaseStockWithOptimisticLock_Success() throws InterruptedException {
        // given
        int threadCount = 100;
        Product product = productRepository.saveAndFlush(
                new Product("테스트 상품", threadCount)
        );

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    productStockOptimisticLockService.decreaseStock(product.getId(), 1);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        // then
        Product result = productRepository.findById(product.getId())
                .orElseThrow();

        int actualStock = result.getStock();

        assertThat(actualStock)
                .as("최종 재고 = %d", actualStock)
                .isZero();
    }
}