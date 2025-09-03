public class MainActivity extends AppCompatActivity {

    private BillingHelper billingHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        billingHelper = new BillingHelper(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // App 回到前景時檢查是否有未 acknowledge 的訂單
        billingHelper.checkPaymentStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (billingHelper != null) billingHelper.cleanup();
    }
}
