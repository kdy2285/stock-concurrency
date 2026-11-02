package com.kdy.stock.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductStockConcurrencyTest {

    private static final int INITIAL_STOCK = 100;
    private static final int REQUEST_COUNT = 100;
    private static final int THREAD_POOL_SIZE = 32;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ProductStockNoLockService productStockNoLockService;

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

    @Test
    @DisplayName("두 트랜잭션이 같은 재고를 읽은 뒤 각자 덮어쓰면 차감 하나가 사라진다 (Lost Update 결정적 재현)")
    void noLock_lostUpdate_deterministic() throws Exception {
        Long productId = createProduct();

        // 두 트랜잭션이 모두 재고를 읽은 뒤에야 UPDATE로 넘어가도록 배리어로 맞춘다.
        // ProductStockNoLockService와 같은 SQL 패턴(읽기 → 읽은 값 - 1로 덮어쓰기)을 직접 실행한다.
        CyclicBarrier bothRead = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        Callable<Void> readThenOverwrite = () -> {
            transactionTemplate.executeWithoutResult(status -> {
                Integer stock = jdbcTemplate.queryForObject(
                        "SELECT stock FROM product WHERE id = ?", Integer.class, productId);
                awaitQuietly(bothRead);
                jdbcTemplate.update(
                        "UPDATE product SET stock = ? WHERE id = ?", stock - 1, productId);
            });
            return null;
        };

        Future<Void> first = executorService.submit(readThenOverwrite);
        Future<Void> second = executorService.submit(readThenOverwrite);

        // 두 트랜잭션 모두 예외 없이 커밋됐는지 확인한다 (예외가 있으면 여기서 던져진다).
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // 두 번 차감했고 둘 다 성공했지만, 둘 다 100을 읽고 99로 덮어써서 재고는 99다.
        assertThat(stockOf(productId)).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    @DisplayName("락과 버전 검사가 없는 서비스에 동시 요청을 보내면 모두 성공한다 (결과 관찰용)")
    void noLock_concurrentRequests_observation() throws InterruptedException {
        Long productId = createProduct();

        Result result = runConcurrently(id -> productStockNoLockService.decreaseStock(id, 1), productId);
        int finalStock = stockOf(productId);
        result.print("락 없음 (JDBC 덮어쓰기)", finalStock);

        // 덮어쓰기 횟수는 스레드 스케줄링에 따라 달라지므로 재고 값은 단언하지 않는다.
        // 실패 없이 모두 성공했는데 재고가 남았다면, 남은 만큼이 덮어써진 차감이다.
        assertThat(result.failureCount())
                .as("실패 요청: %s", result.failures())
                .isZero();
        assertThat(result.successCount()).isEqualTo(REQUEST_COUNT);
    }

    @Test
    @DisplayName("@Version이 있는 엔티티를 락 없이 차감하면 덮어쓰지 않고 일부 요청이 낙관적 락 예외로 실패한다")
    void jpaWithVersion_withoutRetry_failsInsteadOfLostUpdate() throws InterruptedException {
        Long productId = createProduct();

        Result result = runConcurrently(id -> productStockService.decreaseStock(id, 1), productId);
        int finalStock = stockOf(productId);
        result.print("JPA 변경 감지 + @Version (재시도 없음)", finalStock);

        // 성공한 만큼만 정확히 차감됐다: Lost Update는 없다.
        assertThat(result.successCount() + finalStock).isEqualTo(INITIAL_STOCK);
        // 재고가 남은 이유는 덮어쓰기가 아니라 버전 충돌로 실패한 요청이다.
        assertThat(result.failureCount(ObjectOptimisticLockingFailureException.class))
                .as("실패 요청: %s", result.failures())
                .isPositive();
    }

    @Test
    @DisplayName("synchronized를 적용하면 단일 서버에서 모든 요청이 성공하고 재고가 0이 된다")
    void synchronized_success() throws InterruptedException {
        Long productId = createProduct();

        Result result = runConcurrently(id -> productStockSynchronizedService.decreaseStock(id, 1), productId);
        int finalStock = stockOf(productId);
        result.print("synchronized", finalStock);

        assertAllSucceeded(result, finalStock);
    }

    @Test
    @DisplayName("Pessimistic Lock을 적용하면 모든 요청이 성공하고 재고가 0이 된다")
    void pessimisticLock_success() throws InterruptedException {
        Long productId = createProduct();

        Result result = runConcurrently(id -> productStockPessimisticLockService.decreaseStock(id, 1), productId);
        int finalStock = stockOf(productId);
        result.print("Pessimistic Lock", finalStock);

        assertAllSucceeded(result, finalStock);
    }

    @Test
    @DisplayName("Optimistic Lock에 재시도를 붙이면 모든 요청이 성공하고 재고가 0이 된다")
    void optimisticLockWithRetry_success() throws InterruptedException {
        Long productId = createProduct();

        Result result = runConcurrently(id -> productStockOptimisticLockService.decreaseStock(id, 1), productId);
        int finalStock = stockOf(productId);
        result.print("Optimistic Lock + 재시도", finalStock);

        assertAllSucceeded(result, finalStock);
    }

    private void assertAllSucceeded(Result result, int finalStock) {
        assertThat(result.failureCount())
                .as("실패 요청: %s", result.failures())
                .isZero();
        assertThat(result.successCount()).isEqualTo(REQUEST_COUNT);
        assertThat(finalStock).isZero();
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("두 트랜잭션이 재고를 읽는 시점을 맞추지 못했습니다.", e);
        }
    }

    private Long createProduct() {
        return productRepository.saveAndFlush(new Product("테스트 상품", INITIAL_STOCK)).getId();
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getStock();
    }

    private Result runConcurrently(Consumer<Long> task, Long productId) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        Map<Class<?>, AtomicInteger> failures = new ConcurrentHashMap<>();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    task.accept(productId);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failures.computeIfAbsent(e.getClass(), k -> new AtomicInteger()).incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        return new Result(successCount.get(), failures);
    }

    private record Result(int successCount, Map<Class<?>, AtomicInteger> failures) {

        int failureCount() {
            return failures.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        /** 지정한 예외 타입과 그 하위 타입의 실패 건수를 합산한다. */
        int failureCount(Class<? extends Throwable> type) {
            return failures.entrySet().stream()
                    .filter(entry -> type.isAssignableFrom(entry.getKey()))
                    .mapToInt(entry -> entry.getValue().get())
                    .sum();
        }

        void print(String label, int finalStock) {
            System.out.printf("[%s] 성공=%d, 실패=%d %s, 최종 재고=%d%n",
                    label, successCount, failureCount(), failures, finalStock);
        }
    }
}
