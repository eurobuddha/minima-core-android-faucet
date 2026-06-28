package org.minimarex.faucet;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIListener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Native Android port of the Faucet MiniDapp.
 *
 * Talks to the local Minima Core node over the native broadcast-Intent IPC API
 * (org.minimarex.minimaapi) for the single node command it needs - "getaddress".
 * The actual coin request is a direct HTTPS GET to the faucet backend.
 */
public class MainActivity extends AppCompatActivity {

    private static final String FAUCET_API = "https://eurobuddha.com/faucet/api/request";
    private static final String NODE_PKG   = "org.minimarex.minimacore";
    private static final long   NODE_TIMEOUT_MS = 6000;

    private MinimaAPI mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private EditText mAddress;
    private Button   mGetAddrBtn;
    private Button   mRequestBtn;
    private Button   mOpenNodeBtn;
    private TextView mStatus;
    private View     mPairingBanner;

    // The last node action that was blocked by "not enabled" - re-run after the
    // user enables us in Minima Core and returns to this app.
    private Runnable mPendingAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAddress       = findViewById(R.id.addressInput);
        mGetAddrBtn    = findViewById(R.id.getAddrBtn);
        mRequestBtn    = findViewById(R.id.requestBtn);
        mOpenNodeBtn   = findViewById(R.id.openNodeBtn);
        mStatus        = findViewById(R.id.statusBox);
        mPairingBanner = findViewById(R.id.pairingBanner);

        // Edge-to-edge (targetSdk 35 draws under the system bars): pad the root for the status bar,
        // nav bar and keyboard so content never sits under them.
        final View mainRoot = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(mainRoot);

        // Construct the API - this auto-registers us with the node (broadcast).
        // The register reply tells us whether we are enabled yet.
        mApi = new MinimaAPI(this, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (enabled) {
                        hidePairingBanner();
                    } else {
                        showPairingBanner(null);
                    }
                });
            }
        });

        mGetAddrBtn.setOnClickListener(v -> getMyAddress());
        mRequestBtn.setOnClickListener(v -> requestMinima());
        mOpenNodeBtn.setOnClickListener(v -> openMinimaCore());

        // If the node isn't installed at all, say so up front.
        if (!isNodeInstalled()) {
            showPairingBanner(null);
            showStatus("Minima Core is not installed on this device.", false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from Minima Core after (hopefully) enabling us - retry.
        if (mPendingAction != null && mPairingBanner.getVisibility() == View.VISIBLE) {
            Runnable retry = mPendingAction;
            mPendingAction = null;
            retry.run();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mApi != null) {
            mApi.onDestroy();
        }
    }

    // ----- Get My Address (needs the node) -------------------------------------------------

    private void getMyAddress() {
        mGetAddrBtn.setEnabled(false);
        mGetAddrBtn.setText("Fetching…");
        runNodeCommand("getaddress", new NodeResult() {
            @Override
            public void onResponse(JSONObject json) {
                resetGetAddrButton();
                if (isNotEnabled(json)) {
                    showPairingBanner(MainActivity.this::getMyAddress);
                    return;
                }
                hidePairingBanner();
                JSONObject resp = json.optJSONObject("response");
                String addr = "";
                if (resp != null) {
                    addr = resp.optString("miniaddress", resp.optString("address", ""));
                }
                if (!addr.isEmpty()) {
                    mAddress.setText(addr);
                } else {
                    showStatus("Could not read address from response.", false);
                }
            }

            @Override
            public void onTimeout() {
                resetGetAddrButton();
                showStatus("Minima Core didn't respond. Is it installed and running?", false);
            }
        });
    }

    private void resetGetAddrButton() {
        mGetAddrBtn.setEnabled(true);
        mGetAddrBtn.setText("Get My Address");
    }

    // ----- Request Minima (direct HTTPS to the faucet backend, no node) --------------------

    private void requestMinima() {
        final String addr = mAddress.getText().toString().trim();
        if (addr.isEmpty()) {
            showStatus("Please enter a Minima address.", false);
            return;
        }
        mRequestBtn.setEnabled(false);
        mRequestBtn.setText("Requesting…");

        new Thread(() -> {
            String body = null;
            try {
                String url = FAUCET_API + "?address=" + URLEncoder.encode(addr, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                InputStream in = (code >= 200 && code < 400)
                        ? conn.getInputStream() : conn.getErrorStream();
                body = readAll(in);
                conn.disconnect();
            } catch (Exception exc) {
                body = null;
            }

            final String result = body;
            mMain.post(() -> {
                mRequestBtn.setEnabled(true);
                mRequestBtn.setText("Request Minima");
                if (result == null) {
                    showStatus("Failed to reach the faucet. Try again later.", false);
                    return;
                }
                try {
                    JSONObject data = new JSONObject(result);
                    boolean ok = data.optBoolean("status", false);
                    String msg = data.optString("message", "Unexpected response from faucet.");
                    showStatus(msg, ok);
                } catch (Exception e) {
                    showStatus("Failed to reach the faucet. Try again later.", false);
                }
            });
        }).start();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    // ----- Node command helper (timeout + main-thread marshalling) -------------------------

    private interface NodeResult {
        void onResponse(JSONObject json);
        void onTimeout();
    }

    private void runNodeCommand(String cmd, NodeResult cb) {
        final boolean[] done = {false};
        final Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                cb.onTimeout();
            }
        };
        mMain.postDelayed(timeout, NODE_TIMEOUT_MS);

        mApi.Command(cmd, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                // Callback arrives on the broadcast-receiver thread - marshal to UI thread.
                mMain.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    cb.onResponse(zResponse);
                });
            }
        });
    }

    /** True when the node replied that this app is not yet enabled in Minima Core. */
    private boolean isNotEnabled(JSONObject json) {
        return !json.optBoolean("enabled", true);
    }

    // ----- Pairing UX ----------------------------------------------------------------------

    private void showPairingBanner(Runnable retryWhenEnabled) {
        if (retryWhenEnabled != null) {
            mPendingAction = retryWhenEnabled;
        }
        mPairingBanner.setVisibility(View.VISIBLE);
    }

    private void hidePairingBanner() {
        mPairingBanner.setVisibility(View.GONE);
    }

    private void openMinimaCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (launch != null) {
            startActivity(launch);
        } else {
            Toast.makeText(this, "Minima Core is not installed.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isNodeInstalled() {
        return getPackageManager().getLaunchIntentForPackage(NODE_PKG) != null;
    }

    // ----- Status box ----------------------------------------------------------------------

    private void showStatus(String msg, boolean success) {
        mStatus.setText(msg);
        mStatus.setTextColor(ContextCompat.getColor(this,
                success ? R.color.faucet_success : R.color.faucet_error));
        mStatus.setVisibility(View.VISIBLE);
    }
}
