package com.example.billingdemo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.List;

/**
 * BillingHelper - Google Play Billing v8 訂閱確認範例
 *
 * 功能：
 * 1) 初始化 BillingClient（含 Pending 支援）
 * 2) 連線並查詢現有訂閱
 * 3) 確認 (acknowledge) 尚未確認的訂單
 * 4) 失敗重試（簡易 backoff）
 *
 * 提醒：
 * - 正式環境建議先把 purchaseToken 傳到 Server，
 *   由 Server 以 Google Play Developer API 驗證後再進行 acknowledge。
 */
public class BillingHelper {

    private final Context context;
    private BillingClient billingClient;
    private static final String TAG = "BillingHelper";

    public BillingHelper(Context context) {
        this.context = context;
    }

    /** 外部呼叫入口：檢查並補單 */
    public void checkPaymentStatus() {
        initBillingClient();
        startBillingConnection();
    }

    /** 初始化 BillingClient（v5+ 必須啟用 Pending 支援） */
    private void initBillingClient() {
        PendingPurchasesParams pendingPurchasesParams = PendingPurchasesParams.newBuilder().build();

        billingClient = BillingClient.newBuilder(context)
                .enablePendingPurchases(pendingPurchasesParams)
                .setListener((billingResult, purchases) -> {
                    int responseCode = billingResult.getResponseCode();
                    if (responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                        handlePurchases(purchases);
                    } else if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                        log("[INFO] User canceled purchase");
                    } else if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                        log("[INFO] Item already owned");
                    } else {
                        log("[ERROR] onPurchasesUpdated failed, response=" + responseCode);
                    }

                    // 額外顯示 PENDING 狀態（例如電信帳單）
                    if (purchases != null) {
                        for (Purchase purchase : purchases) {
                            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                                log("[INFO] Purchase pending, Order ID=" + purchase.getOrderId());
                            }
                        }
                    }
                })
                .build();
    }

    /** 開始與 Play Billing 服務連線 */
    private void startBillingConnection() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    log("[INFO] Billing setup finished successfully");
                    queryPurchases();
                } else {
                    log("[ERROR] Billing setup failed, code=" + billingResult.getResponseCode());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                log("[ERROR] Billing service disconnected");
            }
        });
    }

    /** 查詢目前有效訂閱（SUBS） */
    private void queryPurchases() {
        if (!billingClient.isReady()) {
            log("[ERROR] BillingClient not ready");
            return;
        }

        QueryPurchasesParams queryParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();

        billingClient.queryPurchasesAsync(queryParams, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                log("[INFO] Found " + purchases.size() + " purchases.");
                handlePurchases(purchases);
            } else {
                log("[ERROR] queryPurchases failed, response=" + billingResult.getResponseCode());
            }
        });
    }

    /** 處理查到的訂單：找出未 acknowledge 的購買 */
    private void handlePurchases(List<Purchase> purchases) {
        for (Purchase purchase : purchases) {
            log("[INFO] Processing purchase, State=" + purchase.getPurchaseState()
                    + ", Acknowledged=" + purchase.isAcknowledged()
                    + ", Order ID=" + purchase.getOrderId());

            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                if (purchase.isAcknowledged()) {
                    log("[INFO] Already acknowledged, Order ID=" + purchase.getOrderId());
                } else {
                    log("[INFO] Found unacknowledged purchase, Order ID=" + purchase.getOrderId());
                    acknowledgePurchase(purchase);
                }
            }
        }
    }

    /** 執行 acknowledge（示範：直接 ack；正式環境建議先走 Server 驗證） */
    private void acknowledgePurchase(Purchase purchase) {
        if (!billingClient.isReady()) {
            log("[ERROR] BillingClient not ready for acknowledgement");
            return;
        }

        String orderId = (purchase.getOrderId() != null) ? purchase.getOrderId() : "UNKNOWN_ORDER";
        log("[INFO] (Demo) Skipping server validation, directly acknowledging. Order ID=" + orderId);

        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        performAcknowledgePurchase(params, purchase, 0);
    }

    /** 分離 acknowledge 邏輯（含簡易重試） */
    private void performAcknowledgePurchase(AcknowledgePurchaseParams params, Purchase purchase, int retryCount) {
        billingClient.acknowledgePurchase(params, billingResult -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                log("[INFO] Purchase acknowledged successfully, Order ID=" + purchase.getOrderId());
            } else {
                log("[ERROR] Failed to acknowledge, code=" + billingResult.getResponseCode()
                        + ", msg=" + billingResult.getDebugMessage());

                // 失敗重試（最多 3 次，1s/2s/3s 遞增）
                if (retryCount < 3) {
                    retryAcknowledgePurchase(params, purchase, retryCount + 1);
                }
            }
        });
    }

    /** 簡易重試（遞增延遲） */
    private void retryAcknowledgePurchase(AcknowledgePurchaseParams params, Purchase purchase, int retryCount) {
        log("[INFO] Retrying acknowledgement, attempt " + retryCount + " for Order ID=" + purchase.getOrderId());

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (billingClient.isReady()) {
                performAcknowledgePurchase(params, purchase, retryCount);
            }
        }, 1000L * retryCount);
    }

    /** 清理資源 */
    public void cleanup() {
        if (billingClient != null && billingClient.isReady()) {
            billingClient.endConnection();
        }
    }

    /** Demo 用 log（正式專案可換成自家 Logger） */
    private void log(String msg) {
        System.out.println(TAG + ": " + msg);
    }
}
