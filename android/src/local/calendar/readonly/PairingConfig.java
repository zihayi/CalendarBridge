package local.calendar.readonly;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import java.net.URL;
import org.json.JSONObject;

final class PairingConfig {
    final String server, token, fingerprint;
    PairingConfig(String server, String token, String fingerprint) throws Exception {
        this.server = normalizedUrl(server);
        if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}")) throw new Exception("配对密钥格式无效。");
        this.token = token;
        String pin = fingerprint == null ? "" : fingerprint.replace(":", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        if (!pin.matches("[0-9a-f]{64}")) throw new Exception("请输入完整的 SHA-256 证书指纹。");
        this.fingerprint = pin;
    }
    static boolean privateAddress(String host) {
        if (host == null || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] p = host.split("\\."); int[] n = new int[4];
        try { for (int i=0; i<4; i++) { n[i]=Integer.parseInt(p[i]); if (n[i]<0 || n[i]>255) return false; } } catch (Exception e) { return false; }
        return n[0]==10 || n[0]==192 && n[1]==168 || n[0]==172 && n[1]>=16 && n[1]<=31 || n[0]==169 && n[1]==254;
    }
    static String normalizedUrl(String value) throws Exception {
        URL url = new URL(value == null ? "" : value.trim());
        if (!url.getProtocol().equals("https") || !privateAddress(url.getHost()) || url.getPort()==0 || url.getPort()>65535 || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null || (!url.getPath().isEmpty() && !url.getPath().equals("/"))) throw new Exception("请输入电脑的局域网 HTTPS 地址，例如 https://192.168.1.10:8766");
        return "https://"+url.getHost()+(url.getPort()>0 ? ":"+url.getPort() : "");
    }
    static PairingConfig parse(String text) throws Exception {
        if (text.length()>16384) throw new Exception("配对文件过大。");
        JSONObject json = new JSONObject(text);
        if (json.optInt("schemaVersion")!=1 || !json.optString("type").equals("calendarbridge-phone")) throw new Exception("这不是手机配对文件，请选择 pairing.json。");
        return new PairingConfig(json.getString("server"), json.getString("token"), json.getString("certificateSha256"));
    }
    static PairingConfig load(Context context) throws Exception {
        SharedPreferences preferences = CalendarSync.prefs(context);
        return new PairingConfig(preferences.getString("server", ""), preferences.getString("token", ""), preferences.getString("certSha256", ""));
    }
    static boolean configured(Context context) { try { load(context); return true; } catch (Exception e) { return false; } }
    static void migrate(Context context) {
        SharedPreferences preferences = CalendarSync.prefs(context);
        // Only a public certificate fingerprint is carried for existing v2 installations.
        // Fresh installs have no saved server/token and are never paired automatically.
        if (!preferences.contains("certSha256") && preferences.contains("server") && preferences.contains("token") && BuildConfig.LEGACY_CERT_SHA256.matches("[0-9a-f]{64}")) preferences.edit().putString("certSha256", BuildConfig.LEGACY_CERT_SHA256).commit();
    }
    void save(Context context) throws Exception {
        if (!CalendarSync.LOCK.tryLock()) throw new Exception("正在同步，请稍后更换配对。");
        try {
            SharedPreferences preferences = CalendarSync.prefs(context);
            boolean changed = !server.equals(preferences.getString("server", "")) || !token.equals(preferences.getString("token", "")) || !fingerprint.equals(preferences.getString("certSha256", ""));
            SharedPreferences.Editor editor = preferences.edit().putString("server", server).putString("token", token).putString("certSha256", fingerprint);
            if (changed) {
                editor.putBoolean("allowWrite", false).putBoolean("automatic", false).putLong("lastUploadMs", 0).remove("lastWrite").putString("lastResult", "配对已保存，请测试连接并同步一次。");
                ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(CalendarSync.JOB);
            }
            if (!editor.commit()) throw new Exception("配对配置保存失败。");
        } finally { CalendarSync.LOCK.unlock(); }
    }
    void moveEndpoint(Context context) throws Exception {
        if (!CalendarSync.LOCK.tryLock()) throw new Exception("正在同步，请稍后连接。");
        try {
            PairingConfig previous = load(context);
            if (!previous.fingerprint.equals(fingerprint) || !previous.token.equals(token)) throw new Exception("配对身份已变化，请重新确认。");
            // A verified DHCP/port change is not a new pairing and must retain write consent.
            if (!CalendarSync.prefs(context).edit().putString("server", server).commit()) throw new Exception("电脑地址保存失败。");
        } finally { CalendarSync.LOCK.unlock(); }
    }
}
