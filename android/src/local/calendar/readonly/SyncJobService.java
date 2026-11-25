package local.calendar.readonly;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncJobService extends JobService {
    private AtomicBoolean stopped;
    @Override public boolean onStartJob(JobParameters parameters){
        AtomicBoolean cancellation=new AtomicBoolean(false);stopped=cancellation;
        new Thread(()->{
            boolean retry=false;
            try{if(PairingConfig.configured(this)&&CalendarSync.permitted(this)&&CalendarSync.prefs(this).getBoolean("automatic",false))CalendarSync.sync(this);}
            catch(Exception e){retry=true;CalendarSync.prefs(this).edit().putString("lastResult","自动同步暂未完成："+e.getMessage()).apply();}
            if(!cancellation.get())jobFinished(parameters,retry);
        },"calendar-job").start();return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){if(stopped!=null)stopped.set(true);return true;}
}
