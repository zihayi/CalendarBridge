package local.calendar.readonly;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.CalendarContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class CalendarSync {
    static final ReentrantLock LOCK = new ReentrantLock();
    static final int JOB = 73101;
    private static long lastDiscovery=-30000;
    private CalendarSync() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sync", Context.MODE_PRIVATE); }
    static boolean permitted(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED; }
    static String clientId(Context c) { String id=prefs(c).getString("clientId",null); if(id==null) { id=UUID.randomUUID().toString(); prefs(c).edit().putString("clientId",id).commit(); } return id; }

    static JSONArray query(Context context, Uri uri, String[] columns, String selection, String order) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor cursor=context.getContentResolver().query(uri,columns,selection,null,order)) {
            if(cursor==null) throw new Exception("手机未返回日历数据，请检查权限。");
            while(cursor.moveToNext()) {
                JSONObject row=new JSONObject();
                for(int i=0;i<columns.length;i++) {
                    if(cursor.isNull(i)) row.put(columns[i],JSONObject.NULL);
                    else if(cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER) row.put(columns[i],cursor.getLong(i));
                    else row.put(columns[i],cursor.getString(i));
                }
                rows.put(row);
            }
        }
        return rows;
    }
    static JSONObject collect(Context context) throws Exception {
        if(!permitted(context)) throw new Exception("请先允许读取日历。");
        long now=System.currentTimeMillis(),day=86400000L;
        JSONObject data=new JSONObject();
        data.put("schemaVersion",1).put("exportedAtMs",now).put("device",Build.MANUFACTURER+" "+Build.MODEL);
        data.put("appVersion",BuildConfig.VERSION_CODE).put("clientId",clientId(context)).put("canWrite",CalendarWriter.enabled(context));
        data.put("rangeStartMs",now-30*day).put("rangeEndMs",now+180*day);
        data.put("calendars",query(context,CalendarContract.Calendars.CONTENT_URI,new String[]{"_id","calendar_displayName","account_type","calendar_access_level","visible","maxReminders"},null,null));
        data.put("events",query(context,CalendarContract.Events.CONTENT_URI,new String[]{"_id","calendar_id","title","dtstart","dtend","duration","eventTimezone","allDay","eventLocation","description","rrule","rdate","exrule","exdate","original_id","originalInstanceTime","originalAllDay","eventStatus","deleted"},"deleted=0","dtstart ASC"));
        data.put("reminders",query(context,CalendarContract.Reminders.CONTENT_URI,new String[]{"_id","event_id","minutes","method"},null,null));
        Uri instances=CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(String.valueOf(now-30*day)).appendPath(String.valueOf(now+180*day)).build();
        data.put("instances",query(context,instances,new String[]{"event_id","calendar_id","title","begin","end","allDay","eventLocation","eventTimezone"},null,"begin ASC"));
        return data;
    }
    static SSLContext pinnedTls(final String pin) throws Exception {
        X509TrustManager trust=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Not a client verifier");}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException {
                if(chain==null||chain.length==0) throw new CertificateException("Missing certificate");
                try {
                    chain[0].checkValidity();
                    byte[] digest=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder hex=new StringBuilder(); for(byte b:digest) hex.append(String.format("%02x",b&255));
                    if(!MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII),pin.getBytes(StandardCharsets.US_ASCII))) throw new CertificateException("电脑证书不匹配，请核对配对文件。");
                } catch(CertificateException e){throw e;} catch(Exception e){throw new CertificateException(e);}
            }
        };
        SSLContext ssl=SSLContext.getInstance("TLS"); ssl.init(null,new TrustManager[]{trust},new SecureRandom()); return ssl;
    }
    static JSONObject request(Context context,String route,JSONObject data)throws Exception {
        PairingConfig pairing=PairingConfig.load(context);
        try{return exchange(context,pairing.server,pairing.fingerprint,pairing.token,route,data);}
        catch(IOException error){
            if(SystemClock.elapsedRealtime()-lastDiscovery<15000)throw error;
            lastDiscovery=SystemClock.elapsedRealtime();
            pairing=WifiDiscovery.reconnect(context,pairing);
            return exchange(context,pairing.server,pairing.fingerprint,pairing.token,route,data);
        }
    }
    static JSONObject exchange(Context context,String server,String fingerprint,String token,String route,JSONObject data)throws Exception {
        HttpsURLConnection conn=(HttpsURLConnection)WifiDiscovery.connection(context,new URL(server+route));
        conn.setSSLSocketFactory(pinnedTls(fingerprint).getSocketFactory());
        conn.setHostnameVerifier((host,session)->PairingConfig.privateAddress(host));
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(8000); conn.setReadTimeout(20000);
        if(token!=null)conn.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(data!=null) {
                byte[] body=data.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
                try(OutputStream out=conn.getOutputStream()){out.write(body);}
            }
            int code=conn.getResponseCode(); InputStream stream=code>=200&&code<300?conn.getInputStream():conn.getErrorStream();
            if(stream==null) throw new Exception("电脑返回 HTTP "+code);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream in=stream){byte[] bytes=new byte[2048];int n;while((n=in.read(bytes))!=-1){buffer.write(bytes,0,n);if(buffer.size()>524288)throw new Exception("响应过大");}}
            JSONObject result=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            if(code!=200||!result.optBoolean("ok")) throw new Exception(code==401?"配对密钥不匹配。":result.optString("error","电脑返回 HTTP "+code));
            return result;
        } finally {conn.disconnect();}
    }
    static String upload(Context context)throws Exception {
        JSONObject result=request(context,"/api/sync",collect(context)); JSONObject count=result.getJSONObject("counts");
        String message="已同步 "+count.getInt("events")+" 条日程、"+count.getInt("reminders")+" 条提醒。\n"+result.getString("receivedAt");
        prefs(context).edit().putString("lastResult",message).putLong("lastUploadMs",System.currentTimeMillis()).apply(); return message;
    }
    static String sync(Context context)throws Exception {
        if(!LOCK.tryLock()) return "正在同步，请稍候。";
        try { PairingConfig.load(context); upload(context); return pollLocked(context); } finally { LOCK.unlock(); }
    }
    static String poll(Context context)throws Exception {
        if(!LOCK.tryLock()) return prefs(context).getString("lastResult","正在同步…");
        try { PairingConfig.load(context); return pollLocked(context); } finally { LOCK.unlock(); }
    }
    static String test(Context context)throws Exception {
        if(!LOCK.tryLock()) return "正在同步，请稍候。";
        try { request(context,"/api/health",null); return "电脑连接成功。"; } finally { LOCK.unlock(); }
    }
    private static String pollLocked(Context context)throws Exception {
        for(int i=0;i<5;i++) {
            JSONObject info=new JSONObject().put("clientId",clientId(context)).put("appVersion",BuildConfig.VERSION_CODE).put("device",Build.MANUFACTURER+" "+Build.MODEL).put("canWrite",CalendarWriter.enabled(context));
            JSONObject command=request(context,"/api/commands/next",info).optJSONObject("command");
            if(command==null) break;
            JSONObject result=CalendarWriter.perform(context,command);
            upload(context);
            request(context,"/api/commands/result",result.put("clientId",clientId(context)));
            prefs(context).edit().putString("lastWrite",result.optString("message")).apply();
        }
        if(permitted(context)&&System.currentTimeMillis()-prefs(context).getLong("lastUploadMs",0)>60000) upload(context);
        String result=prefs(context).getString("lastResult","电脑连接成功"),write=prefs(context).getString("lastWrite","");
        return result+(write.isEmpty()?"":"\n上次编辑："+write);
    }
}
