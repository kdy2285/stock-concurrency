package com.kdy.stock.product;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/products")
public class ProductController {

    private final ProductStockService productStockService;

    @PostMapping("/{productId}/decrease")
    public void decreaseStock(
            @PathVariable Long productId,
            @RequestParam int quantity
    ) {
        productStockService.decreaseStock(productId, quantity);
    }

    @GetMapping("/{productId}/stock")
    public int getStock(@PathVariable Long productId) {
        return productStockService.getStock(productId);
    }
}
