package local.calendar.readonly;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;

/** Drafts only live in this dialog. Polling cannot overwrite them or implicitly save a new peer. */
final class ManualPairingDialog {
    static final int PICK_FILE = 40;
    private final MainActivity host;
    private final AlertDialog dialog;
    private final EditText server, token, pin;
    private final TextView feedback;

    ManualPairingDialog(MainActivity host) {
        this.host = host;
        View form = host.getLayoutInflater().inflate(R.layout.dialog_advanced, null);
        server = form.findViewById(R.id.server);
        token = form.findViewById(R.id.token);
        pin = form.findViewById(R.id.pin);
        feedback = form.findViewById(R.id.form_message);
        SharedPreferences prefs = CalendarSync.prefs(host);
        server.setText(prefs.getString("server", ""));
        token.setText(prefs.getString("token", ""));
        pin.setText(prefs.getString("certSha256", ""));

        form.findViewById(R.id.import_file).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { host.startActivityForResult(pick, PICK_FILE); }
            catch (android.content.ActivityNotFoundException e) { note("无法打开文件选择器，请粘贴配对配置。", true); }
        });
        form.findViewById(R.id.paste_config).setOnClickListener(v -> {
            try {
                ClipboardManager clipboard = (ClipboardManager)host.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) throw new Exception("请先复制配对文件中的 JSON 文本。");
                fill(PairingConfig.parse(clip.getItemAt(0).getText().toString()));
            } catch (Exception e) { note(problem(e), true); }
        });
        dialog = new AlertDialog.Builder(host).setTitle(R.string.advanced).setView(form)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save_pairing, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save()));
        dialog.setOnDismissListener(ignored -> host.manualClosed(this));
    }

    void show() {
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }
    void dismiss() { dialog.dismiss(); }

    void importFile(Uri uri) {
        try (InputStream stream = host.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new Exception("无法读取配对文件。");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[2048];
            int count;
            while ((count = stream.read(block)) != -1) {
                if (bytes.size() + count > 16384) throw new Exception("配对文件过大。");
                bytes.write(block, 0, count);
            }
            fill(PairingConfig.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception e) { note("导入失败：" + problem(e), true); }
    }

    private void fill(PairingConfig config) {
        server.setText(config.server); token.setText(config.token); pin.setText(config.fingerprint);
        note("已填入配置，点击「保存配对」后生效。", false);
    }

    private void save() {
        try {
            PairingConfig config = new PairingConfig(server.getText().toString(), token.getText().toString().trim(), pin.getText().toString());
            host.saveManual(config);
            dialog.dismiss();
        } catch (Exception e) { note(problem(e), true); }
    }

    private static String problem(Exception e) {
        // Parser errors can quote the input, which may contain the pairing token.
        if (e instanceof JSONException) return "配对配置格式无效，请选择手机用的 pairing.json。";
        if (e instanceof MalformedURLException) return "请输入完整的局域网 HTTPS 地址。";
        return e.getMessage() == null ? "无法读取配置，请检查后重试。" : e.getMessage();
    }
    private void note(String text, boolean error) {
        feedback.setTextColor(host.getColor(error ? R.color.error : R.color.green_dark));
        feedback.setText(text);
        feedback.post(() -> feedback.requestRectangleOnScreen(new android.graphics.Rect(0, 0, feedback.getWidth(), feedback.getHeight()), false));
    }
}
