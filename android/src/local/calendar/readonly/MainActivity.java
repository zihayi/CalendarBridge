package local.calendar.readonly;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public class MainActivity extends Activity {
    enum Work {
        IDLE(""), POLL(""), SEARCH("正在搜索…"), CHOOSE("请选择电脑"), PAIR("等待配对确认…"),
        RECONNECT("正在重连…"), TEST("正在测试…"), SYNC("正在同步…");
        final String label;
        Work(String label) { this.label = label; }
    }
    private static final int READ = 1, WRITE = 2;
    private Work work = Work.IDLE;
    private BridgeDashboard dashboard;
    private ManualPairingDialog manual;
    private AlertDialog peerDialog;
    private AtomicBoolean pairingCancellation;
    private PairingConfig consentPeer;
    private boolean active, initialSearch, syncOnResume;
    private Runnable afterPoll;
    private android.window.OnBackInvokedCallback tabBack;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (work == Work.IDLE && manual == null && PairingConfig.configured(MainActivity.this)) runTask(Work.POLL);
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable firstSearch = () -> {
        if (active && !initialSearch && work == Work.IDLE && manual == null && !dashboard.settings && !PairingConfig.configured(this)) findComputers(true);
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PairingConfig.migrate(this);
        dashboard = new BridgeDashboard(this);
        initialSearch = state != null && state.getBoolean("searched");
        selectTab(state != null && state.getBoolean("settings"));
        render();
        if (state != null && state.getBoolean("manual")) openAdvanced();
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void render() { dashboard.render(work, afterPoll != null); }
    private void phase(Work next) { work = next; render(); }
    private void info(String text) { dashboard.message(text, false); }
    private void error(String text) { dashboard.message(text, true); }
    private static String problem(Exception e) {
        if (e instanceof org.json.JSONException) return "电脑返回的信息格式无效。";
        return e.getMessage() == null ? "请检查 Wi-Fi 连接并重试。" : e.getMessage();
    }

    void selectTab(boolean settings) {
        dashboard.clearMessage();
        dashboard.showTab(settings);
        if (Build.VERSION.SDK_INT >= 33) {
            if (settings && tabBack == null) {
                tabBack = () -> selectTab(false);
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, tabBack);
            } else if (!settings && tabBack != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(tabBack);
                tabBack = null;
            }
        }
    }
    @Override public void onBackPressed() {
        if (dashboard.settings) selectTab(false); else super.onBackPressed();
    }

    // Foreground polling is silent. A tap during a poll runs once after it, instead of getting lost.
    private void userAction(Runnable action) {
        if (work == Work.POLL && afterPoll == null) {
            afterPoll = action;
            info("当前检查结束后将继续。");
            render();
        } else if (work == Work.IDLE) {
            dashboard.clearMessage();
            action.run();
        }
    }
    private void drainQueue() {
        if (!active || work != Work.IDLE || afterPoll == null) return;
        Runnable action = afterPoll; afterPoll = null;
        dashboard.clearMessage(); render(); action.run();
    }
    void primaryAction() {
        if (PairingConfig.configured(this)) userAction(() -> requestSync(false)); else searchAction();
    }
    void searchAction() { userAction(() -> findComputers(false)); }
    void testAction() { userAction(() -> runTask(Work.TEST)); }
    void openAdvanced() {
        if (manual != null || (work != Work.IDLE && work != Work.POLL)) return;
        dashboard.clearMessage();
        manual = new ManualPairingDialog(this);
        manual.show();
    }
    void manualClosed(ManualPairingDialog closed) { if (manual == closed) manual = null; }
    void saveManual(PairingConfig config) throws Exception {
        if (work != Work.IDLE) throw new Exception("当前连接尚未结束，请稍后保存。");
        config.save(this);
        render(); info("配对已保存，可以测试连接或立即同步。");
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != ManualPairingDialog.PICK_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        if (manual == null) openAdvanced();
        if (manual != null) manual.importFile(data.getData());
    }

    void setAutomatic(boolean on) {
        if (on && (!PairingConfig.configured(this) || !CalendarSync.permitted(this))) {
            render(); error("请先配对并授予日历读取权限。"); return;
        }
        JobScheduler scheduler = (JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);
        if (on) {
            JobInfo job = new JobInfo.Builder(CalendarSync.JOB, new ComponentName(this, SyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true).setBackoffCriteria(60000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) { render(); error("后台任务未能安排，请手动同步。"); return; }
        } else scheduler.cancel(CalendarSync.JOB);
        CalendarSync.prefs(this).edit().putBoolean("automatic", on).apply();
        render();
    }
    void changeWriteConsent(boolean on) {
        if (on) {
            try { consentPeer = PairingConfig.load(this); }
            catch (Exception e) { render(); error("请先配对电脑。"); return; }
            if (!CalendarSync.permitted(this) || checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                render();
                requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, WRITE);
                return;
            }
        }
        setWriting(on);
    }
    private void setWriting(boolean on) {
        consentPeer = null;
        CalendarSync.prefs(this).edit().putBoolean("allowWrite", on).apply();
        render(); info(on ? "已允许这台电脑新增、修改日程。" : "电脑编辑已关闭，仍可同步日历。");
        if (on) userAction(() -> runTask(Work.SYNC));
    }

    private void requestSync(boolean justPaired) {
        if (!PairingConfig.configured(this)) return;
        if (!CalendarSync.permitted(this)) { requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, READ); return; }
        if (justPaired && !CalendarSync.prefs(this).getBoolean("automatic", false)) setAutomatic(true);
        runTask(Work.SYNC);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        render();
        if (code == READ) {
            if (CalendarSync.permitted(this)) userAction(() -> requestSync(true));
            else error("未获得读取权限。可在系统应用设置中允许读取日历后再同步。");
        } else if (code == WRITE) {
            if (!CalendarSync.permitted(this) || checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                consentPeer = null; error("未获得日历读写权限，电脑编辑保持关闭。"); return;
            }
            // A permission result must not enable writing for a different computer or a recreated Activity.
            try {
                PairingConfig saved = PairingConfig.load(this);
                if (consentPeer != null && saved.fingerprint.equals(consentPeer.fingerprint) && saved.token.equals(consentPeer.token)) { setWriting(true); return; }
            } catch (Exception ignored) { }
            consentPeer = null; info("请重新确认要授权的电脑，再开启编辑。");
        }
    }

    private void findComputers(boolean quiet) {
        initialSearch = true; phase(Work.SEARCH);
        new Thread(() -> {
            try {
                List<WifiDiscovery.Computer> computers = WifiDiscovery.find(this);
                runOnUiThread(() -> {
                    if (!alive()) return;
                    if (computers.isEmpty() || !active) {
                        phase(Work.IDLE);
                        if (active && !quiet) error("未发现电脑。请在 WallNote 开启「配对手机」，并检查同一 Wi-Fi 是否允许设备互访。");
                        return;
                    }
                    chooseComputer(computers);
                });
            } catch (Exception e) { runOnUiThread(() -> { if (alive()) { phase(Work.IDLE); if (!quiet) error(problem(e)); } }); }
        }, "wifi-discovery").start();
    }
    private void chooseComputer(List<WifiDiscovery.Computer> computers) {
        phase(Work.CHOOSE);
        String[] labels = new String[computers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = computers.get(i).name + (computers.get(i).pairing ? " · 可配对" : " · 已发现");
        peerDialog = new AlertDialog.Builder(this).setTitle("选择 WallNote 电脑").setItems(labels, (d, index) -> pairComputer(computers.get(index)))
                .setNegativeButton(R.string.cancel, (d, which) -> phase(Work.IDLE)).create();
        peerDialog.setOnCancelListener(d -> phase(Work.IDLE));
        peerDialog.show();
    }
    private void pairedSync() {
        phase(Work.IDLE);
        if (active) requestSync(true); else syncOnResume = true;
    }
    private void pairComputer(WifiDiscovery.Computer computer) {
        try {
            PairingConfig saved = PairingConfig.load(this);
            if (saved.fingerprint.equals(computer.fingerprint)) {
                phase(Work.RECONNECT);
                new Thread(() -> {
                    try {
                        PairingConfig next = new PairingConfig(computer.server, saved.token, saved.fingerprint);
                        CalendarSync.exchange(this, next.server, next.fingerprint, next.token, "/api/health", null);
                        next.moveEndpoint(this);
                        runOnUiThread(() -> { if (alive()) pairedSync(); });
                    } catch (Exception e) { runOnUiThread(() -> { if (alive()) { phase(Work.IDLE); error(problem(e)); } }); }
                }, "wifi-reconnect").start();
                return;
            }
        } catch (Exception ignored) { }
        if (!computer.pairing) { phase(Work.IDLE); info("请在这台电脑的 WallNote 中点击「配对手机」，再重新搜索。"); return; }
        phase(Work.PAIR);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        pairingCancellation = cancelled;
        View body = getLayoutInflater().inflate(R.layout.dialog_code, null);
        ((TextView)body.findViewById(R.id.pair_computer)).setText("配对电脑：" + computer.name);
        TextView digits = body.findViewById(R.id.verification_code);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("核对验证码").setView(body)
                .setNegativeButton(R.string.cancel, (d, which) -> cancelled.set(true)).create();
        peerDialog = dialog;
        dialog.setOnCancelListener(d -> cancelled.set(true));
        dialog.show();
        new Thread(() -> {
            try {
                String secret = WifiDiscovery.randomHex(32);
                JSONObject first = WifiDiscovery.pairRequest(this, computer, "/pair/begin", new JSONObject().put("commitment", WifiDiscovery.digest(secret)).put("clientId", CalendarSync.clientId(this)).put("device", Build.MANUFACTURER + " " + Build.MODEL));
                String id = first.getString("id"), nonce = first.getString("nonce");
                if (!id.matches("[0-9a-f]{32}") || !nonce.matches("[0-9a-f]{64}")) throw new Exception("电脑配对响应无效。");
                String code = WifiDiscovery.verificationCode(id, computer.fingerprint, nonce, secret);
                JSONObject proof = new JSONObject().put("id", id).put("secret", secret);
                WifiDiscovery.pairRequest(this, computer, "/pair/reveal", proof);
                runOnUiThread(() -> { if (!cancelled.get() && alive()) digits.setText(code); });
                long end = SystemClock.elapsedRealtime() + 120000;
                while (!cancelled.get() && SystemClock.elapsedRealtime() < end) {
                    JSONObject result = WifiDiscovery.pairRequest(this, computer, "/pair/finish", proof);
                    if (result.optString("state").equals("approved")) {
                        if (!computer.fingerprint.equals(result.optString("certificateSha256"))) throw new Exception("电脑身份不一致，请重新配对。");
                        PairingConfig config = new PairingConfig(computer.server, result.getString("token"), computer.fingerprint);
                        runOnUiThread(() -> {
                            if (!alive()) return;
                            dialog.dismiss(); phase(Work.IDLE);
                            if (cancelled.get()) { info("配对已取消。"); return; }
                            try { config.save(this); info("配对成功。"); pairedSync(); }
                            catch (Exception e) { error(problem(e)); }
                        });
                        return;
                    }
                    if (result.optString("state").equals("rejected")) throw new Exception("电脑已拒绝配对。");
                    Thread.sleep(1500);
                }
                throw new Exception("配对超时，请在电脑重新开启配对。");
            } catch (Exception e) { runOnUiThread(() -> {
                if (alive()) { dialog.dismiss(); phase(Work.IDLE); if (cancelled.get()) info("配对已取消。"); else error(problem(e)); }
            }); }
        }, "wifi-pairing").start();
    }

    private void runTask(Work task) {
        if (work != Work.IDLE || !PairingConfig.configured(this)) return;
        phase(task);
        new Thread(() -> {
            String result;
            boolean failed = false;
            try { result = task == Work.TEST ? CalendarSync.test(this) : task == Work.SYNC ? CalendarSync.sync(this) : CalendarSync.poll(this); }
            catch (Exception e) {
                failed = true; result = "未完成：" + problem(e) + "\n请确认 WallNote 已打开，且手机和电脑连接同一 Wi-Fi。";
                if (task != Work.TEST) CalendarSync.prefs(this).edit().putString("lastResult", result).apply();
            }
            final String message = result;
            final boolean isError = failed;
            runOnUiThread(() -> {
                if (!alive()) return;
                phase(Work.IDLE);
                if (task != Work.POLL) dashboard.message(message.split("\n", 2)[0], isError);
                drainQueue();
            });
        }, "calendar-sync").start();
    }

    @Override protected void onResume() {
        super.onResume(); active = true; render(); drainQueue();
        if (syncOnResume) { syncOnResume = false; userAction(() -> requestSync(true)); }
        handler.postDelayed(tick, 1500);
        if (!initialSearch) handler.postDelayed(firstSearch, 500);
    }
    @Override protected void onPause() {
        active = false; handler.removeCallbacks(tick); handler.removeCallbacks(firstSearch); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("settings", dashboard.settings); state.putBoolean("manual", manual != null); state.putBoolean("searched", initialSearch);
    }
    @Override protected void onDestroy() {
        if (pairingCancellation != null) pairingCancellation.set(true);
        if (peerDialog != null) peerDialog.dismiss();
        if (manual != null) manual.dismiss();
        if (Build.VERSION.SDK_INT >= 33 && tabBack != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(tabBack);
        super.onDestroy();
    }
}
