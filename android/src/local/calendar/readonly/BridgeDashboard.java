package local.calendar.readonly;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Native presentation for the two tabs; pairing and calendar operations live in MainActivity. */
final class BridgeDashboard {
    private static final DateTimeFormatter BEIJING = DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm", Locale.CHINA)
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final MainActivity host;
    private final Button primary, search, test, advanced, syncTab, settingsTab;
    private final Switch automatic, writing;
    private final TextView badge, title, caption, time, result, access, address, hint, notice;
    private boolean updating;
    boolean settings;

    BridgeDashboard(MainActivity host) {
        this.host = host;
        host.setContentView(R.layout.activity_main);
        fitSystemBars(host.getWindow(), host.findViewById(R.id.shell));
        primary = host.findViewById(R.id.main_action);
        search = host.findViewById(R.id.search_action);
        test = host.findViewById(R.id.test_action);
        advanced = host.findViewById(R.id.advanced_action);
        syncTab = host.findViewById(R.id.tab_sync);
        settingsTab = host.findViewById(R.id.tab_settings);
        automatic = host.findViewById(R.id.automatic);
        writing = host.findViewById(R.id.writing);
        badge = host.findViewById(R.id.pair_badge);
        title = host.findViewById(R.id.hero_title);
        caption = host.findViewById(R.id.hero_caption);
        time = host.findViewById(R.id.upload_time);
        result = host.findViewById(R.id.sync_result);
        access = host.findViewById(R.id.access_summary);
        address = host.findViewById(R.id.computer_address);
        hint = host.findViewById(R.id.settings_hint);
        notice = host.findViewById(R.id.notice_text);

        primary.setOnClickListener(v -> host.primaryAction());
        search.setOnClickListener(v -> host.searchAction());
        test.setOnClickListener(v -> host.testAction());
        advanced.setOnClickListener(v -> host.openAdvanced());
        syncTab.setOnClickListener(v -> host.selectTab(false));
        settingsTab.setOnClickListener(v -> host.selectTab(true));
        host.findViewById(R.id.notice_close).setOnClickListener(v -> clearMessage());
        switchLabel(automatic, R.string.auto_title, R.string.auto_summary);
        switchLabel(writing, R.string.write_title, R.string.write_summary);
        automatic.setOnCheckedChangeListener((button, on) -> { if (!updating) host.setAutomatic(on); });
        writing.setOnCheckedChangeListener((button, on) -> { if (!updating) host.changeWriteConsent(on); });
        ((TextView)host.findViewById(R.id.version)).setText("CalendarBridge  ·  v" + BuildConfig.VERSION_NAME + "  /  Android");

        Configuration config = host.getResources().getConfiguration();
        if (config.fontScale > 1.5f || config.screenWidthDp < 320) visible(R.id.device_scene, false);
    }

    private static void fitSystemBars(Window window, View shell) {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        shell.requestApplyInsets();
    }

    private void switchLabel(Switch toggle, int heading, int detail) {
        String name = host.getString(heading);
        SpannableString text = new SpannableString(name + "\n" + host.getString(detail));
        int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, start = name.length() + 1;
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, name.length(), flags);
        text.setSpan(new RelativeSizeSpan(0.86f), start, text.length(), flags);
        text.setSpan(new ForegroundColorSpan(host.getColor(R.color.muted)), start, text.length(), flags);
        toggle.setText(text);
        toggle.setLineSpacing(2 * host.getResources().getDisplayMetrics().density, 1f);
    }

    private void visible(int id, boolean show) { host.findViewById(id).setVisibility(show ? View.VISIBLE : View.GONE); }
    private static void text(TextView view, String value) { if (!TextUtils.equals(value, view.getText())) view.setText(value); }

    void showTab(boolean showSettings) {
        settings = showSettings;
        visible(R.id.page_sync, !settings);
        visible(R.id.page_settings, settings);
        syncTab.setSelected(!settings);
        settingsTab.setSelected(settings);
    }

    void message(String value, boolean error) {
        visible(R.id.notice, true);
        host.findViewById(R.id.notice).setBackgroundColor(error ? 0xfffaf0eb : host.getColor(R.color.green_soft));
        notice.setTextColor(host.getColor(error ? R.color.error : R.color.green_dark));
        text(notice, value);
    }

    void clearMessage() { visible(R.id.notice, false); }

    void render(MainActivity.Work work, boolean queued) {
        SharedPreferences prefs = CalendarSync.prefs(host);
        boolean paired = PairingConfig.configured(host), read = CalendarSync.permitted(host), write = CalendarWriter.enabled(host);
        boolean auto = prefs.getBoolean("automatic", false);
        boolean ready = !queued && (work == MainActivity.Work.IDLE || work == MainActivity.Work.POLL);
        text(badge, paired ? "已配对" : "未配对");
        badge.getCompoundDrawablesRelative()[0].mutate().setTint(paired ? host.getColor(R.color.green) : 0xff929d95);
        text(title, paired ? "WallNote 电脑" : "连接到电脑");
        text(caption, host.getString(paired ? R.string.paired_caption : R.string.unpaired_caption));
        String action = paired ? (read ? "立即同步" : "授权并同步") : host.getString(R.string.search);
        if (queued) action = "等待当前检查结束…";
        else if (!ready) action = work.label;
        text(primary, action);
        primary.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, paired ? R.drawable.ic_sync : R.drawable.ic_wifi, 0);
        primary.setEnabled(ready);
        search.setEnabled(ready);
        test.setEnabled(paired && ready);
        advanced.setEnabled(ready);
        text(search, work == MainActivity.Work.SEARCH ? work.label : host.getString(R.string.search));
        text(test, work == MainActivity.Work.TEST ? work.label : host.getString(R.string.test_connection));
        visible(R.id.guide, !paired);
        visible(R.id.sync_record, paired);
        if (paired) {
            long uploaded = prefs.getLong("lastUploadMs", 0);
            text(time, uploaded > 0 ? BEIJING.format(Instant.ofEpochMilli(uploaded)) + "  北京时间" : "暂无上传记录");
            String detail = prefs.getString("lastResult", "完成首次同步后，上传结果将显示在这里。");
            // Successful legacy summaries also contain the server's raw ISO timestamp.
            if (detail.startsWith("已同步 ") && detail.contains("\n")) detail = detail.substring(0, detail.indexOf('\n'));
            String lastWrite = prefs.getString("lastWrite", "");
            text(result, lastWrite.isEmpty() ? detail : detail + "\n上次编辑：" + lastWrite);
            text(access, (write ? "已允许电脑编辑" : read ? "仅允许读取日历" : "尚未授予日历权限") + "   ·   后台同步" + (auto ? "已开启" : "已关闭"));
        }
        text(address, paired ? prefs.getString("server", "") : "尚未配对。可以搜索电脑，也可以导入配对文件。");
        updating = true;
        try {
            automatic.setChecked(auto);
            writing.setChecked(write);
            automatic.setEnabled(paired && (read || auto));
            writing.setEnabled(paired);
        } finally { updating = false; }
        text(hint, !paired ? "配对后可设置后台同步和电脑编辑权限。" : !read ? "先在同步页授予日历读取权限，即可开启后台同步。" : "保持应用在前台时，约每 5 秒检查一次更新。");
    }
}
