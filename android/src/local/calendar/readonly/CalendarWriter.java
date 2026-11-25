package local.calendar.readonly;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.os.RemoteException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

final class CalendarWriter {
    static final String[] EVENT_FIELDS={"calendar_id","title","dtstart","dtend","duration","eventTimezone","allDay","eventLocation","description","rrule","rdate","exrule","exdate","original_id","originalInstanceTime","originalAllDay","eventStatus","deleted"};
    static final Set<String> EDIT_FIELDS=new HashSet<>(Arrays.asList("title","dtstart","dtend","duration","eventTimezone","allDay","eventLocation","description"));
    static boolean enabled(Context context){return CalendarSync.permitted(context)&&context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)==PackageManager.PERMISSION_GRANTED&&CalendarSync.prefs(context).getBoolean("allowWrite",false);}
    static class Journal extends SQLiteOpenHelper {
        Journal(Context c){super(c,"write-journal.db",null,1);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE writes (id TEXT PRIMARY KEY,result TEXT)");}
        public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
    }
    static JSONObject perform(Context context,JSONObject command)throws Exception {
        String id=command.getString("id");
        if(!id.matches("[0-9a-fA-F-]{36}"))throw new Exception("操作编号无效");
        try(Journal journal=new Journal(context)){
            SQLiteDatabase db=journal.getWritableDatabase();
            try(Cursor cursor=db.rawQuery("SELECT result FROM writes WHERE id=?",new String[]{id})){
                if(cursor.moveToFirst()){
                    if(!cursor.isNull(0))return new JSONObject(cursor.getString(0));
                    JSONObject uncertain=new JSONObject().put("id",id).put("status","uncertain").put("message","上次执行被中断，请核对手机日历；此操作不会自动重试。");
                    remember(db,id,uncertain);return uncertain;
                }
            }
            ContentValues started=new ContentValues();started.put("id",id);started.putNull("result");db.insertOrThrow("writes",null,started);
            JSONObject result=new JSONObject().put("id",id);
            try {
                if(!enabled(context))throw new SecurityException("尚未允许电脑编辑日历");
                long eventId=apply(context,command);
                result.put("status","succeeded").put("eventId",eventId).put("message",command.getString("action").equals("create")?"日程已新增到手机":"手机日程已更新");
            } catch(RemoteException error){result.put("status","uncertain").put("message","日历服务连接中断，请核对手机后再操作。");}
            catch(Exception error){result.put("status","failed").put("message",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
            remember(db,id,result);return result;
        }
    }
    static void remember(SQLiteDatabase db,String id,JSONObject result){ContentValues values=new ContentValues();values.put("result",result.toString());db.update("writes",values,"id=?",new String[]{id});}
    static ContentValues values(JSONObject object,Set<String> allowed)throws Exception {
        ContentValues values=new ContentValues();
        for(Iterator<String> keys=object.keys();keys.hasNext();){
            String key=keys.next();if(allowed!=null&&!allowed.contains(key))throw new Exception("不支持的编辑字段："+key);
            Object value=object.get(key);
            if(value==JSONObject.NULL)values.putNull(key);
            else if(value instanceof Number)values.put(key,((Number)value).longValue());
            else if(value instanceof String)values.put(key,(String)value);
            else throw new Exception("字段格式无效："+key);
        }
        return values;
    }
    static long apply(Context context,JSONObject command)throws Exception {
        String action=command.getString("action");boolean creating=action.equals("create");
        if(!creating&&!action.equals("update"))throw new Exception("仅支持新增和修改日程");
        if(!command.getString("clientId").equals(CalendarSync.clientId(context)))throw new Exception("此操作不属于这台手机");
        JSONObject changes=command.getJSONObject("changes");ContentValues eventValues=values(changes,EDIT_FIELDS);
        long eventId=creating?0:command.getLong("eventId");JSONObject expected=creating?null:command.getJSONObject("expected");
        long calendarId=creating?command.getLong("calendarId"):expected.getLong("calendar_id");
        JSONArray calendars=CalendarSync.query(context,ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,calendarId),new String[]{"_id","calendar_access_level","maxReminders"},null,null);
        if(calendars.length()!=1||calendars.getJSONObject(0).optInt("calendar_access_level",0)<500)throw new Exception("此手机日历不允许写入");
        JSONArray reminders=command.optJSONArray("reminders");
        if(reminders!=null){
            int max=calendars.getJSONObject(0).optInt("maxReminders",5);
            if(reminders.length()>5||(max>=0&&reminders.length()>max))throw new Exception("提醒数量超过日历限制");
            for(int i=0;i<reminders.length();i++){int minutes=reminders.getInt(i);if(minutes<0||minutes>40320)throw new Exception("提醒时间无效");}
            eventValues.put("hasAlarm",reminders.length()>0?1:0);
        }
        ArrayList<ContentProviderOperation> operations=new ArrayList<>();int eventOperation;
        Uri eventUri=creating?CalendarContract.Events.CONTENT_URI:ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,eventId);
        if(creating){
            if(changes.optString("title").trim().isEmpty()||!changes.has("dtstart")||!changes.has("dtend")||!changes.has("eventTimezone"))throw new Exception("新日程缺少必要字段");
            if(changes.getLong("dtend")<=changes.getLong("dtstart"))throw new Exception("结束时间必须晚于开始时间");
            eventValues.put("calendar_id",calendarId);
            eventOperation=operations.size();operations.add(ContentProviderOperation.newInsert(eventUri).withValues(eventValues).build());
        }else{
            JSONArray current=CalendarSync.query(context,eventUri,EVENT_FIELDS,"deleted=0",null);
            if(current.length()!=1)throw new Exception("手机上的日程已删除或不存在");
            JSONObject row=current.getJSONObject(0);
            for(String key:EVENT_FIELDS){
                Object before=expected.opt(key),now=row.opt(key);
                String a=before==null||before==JSONObject.NULL?null:String.valueOf(before),b=now==null||now==JSONObject.NULL?null:String.valueOf(now);
                if(a==null?b!=null:!a.equals(b))throw new Exception("手机日程已发生变化，请在电脑重新载入后编辑");
            }
            operations.add(ContentProviderOperation.newAssertQuery(eventUri).withValues(values(expected,new HashSet<>(Arrays.asList(EVENT_FIELDS)))).withExpectedCount(1).build());
            eventOperation=operations.size();operations.add(ContentProviderOperation.newUpdate(eventUri).withValues(eventValues).withExpectedCount(1).build());
        }
        if(reminders!=null){
            if(!creating){
                JSONArray old=command.getJSONArray("expectedReminders");
                operations.add(ContentProviderOperation.newAssertQuery(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id=?",new String[]{String.valueOf(eventId)}).withExpectedCount(old.length()).build());
                for(int i=0;i<old.length();i++){
                    JSONObject reminder=old.getJSONObject(i);ContentValues before=new ContentValues();before.put("event_id",eventId);before.put("minutes",reminder.getInt("minutes"));before.put("method",reminder.getInt("method"));
                    operations.add(ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI,reminder.getLong("_id"))).withValues(before).withExpectedCount(1).build());
                }
                operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id=?",new String[]{String.valueOf(eventId)}).build());
            }
            for(int i=0;i<reminders.length();i++){
                ContentProviderOperation.Builder reminder=ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("minutes",reminders.getInt(i)).withValue("method",1);
                if(creating)reminder.withValueBackReference("event_id",eventOperation);else reminder.withValue("event_id",eventId);
                operations.add(reminder.build());
            }
        }
        ContentProviderResult[] results=context.getContentResolver().applyBatch(CalendarContract.AUTHORITY,operations);
        return creating?ContentUris.parseId(results[eventOperation].uri):eventId;
    }
}
