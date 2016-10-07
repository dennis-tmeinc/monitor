package ca.cuni.monitor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;

public class BatteryStatus extends Activity {

    private boolean m_paused = true ;
    private Handler m_handler ;
    private Runnable m_proc_runable ;
    private int m_interval = 6000 ;

    private PowerConnectionReceiver mReceiver ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battery_status);

        m_paused = true ;
        m_handler = new Handler();
        m_proc_runable = new Runnable() {
            @Override
            public void run() {
                if( !m_paused ) {
                    updateStatus();
                }
            }
        } ;

        mReceiver = new PowerConnectionReceiver() ;
        Intent intent = registerReceiver (mReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ;
        updateStatus(intent);

    }

    class PowerConnectionReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus(intent) ;
        }
    }

    void updateStatus(Intent batteryStatus) {
        if( batteryStatus == null ) {
            batteryStatus = this.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }

        TextView v = (TextView)findViewById(R.id.charge_status) ;
        // Are we charging / charged?
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if( status == BatteryManager.BATTERY_STATUS_FULL ) {
            v.setText("Full");
        }
        else if( status == BatteryManager.BATTERY_STATUS_CHARGING ) {
            int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if( chargePlug == BatteryManager.BATTERY_PLUGGED_USB ) {
                v.setText("USB");
            }
            else if( chargePlug == BatteryManager.BATTERY_PLUGGED_AC ) {
                v.setText("AC");
            }
            else if( chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS ) {
                v.setText("Wireless");
            }
            else {
                v.setText("Charging");
            }
        }
        else {
            v.setText("Discharging");
        }


        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        ((TextView)findViewById(R.id.capacity)).setText(String.valueOf(level*100/scale) + "%");

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ) {
            BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE) ;
            int iv ;
            // iv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ;
            // ((TextView)findViewById(R.id.capacity)).setText(String.valueOf(iv) + "%");
            iv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ;
            ((TextView)findViewById(R.id.chargecounter)).setText(String.valueOf(iv));
            iv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ;
            ((TextView)findViewById(R.id.current_avg)).setText(String.valueOf(iv/1000) + " ma");
            iv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            ((TextView)findViewById(R.id.current_now)).setText(String.valueOf(iv/1000) + " ma");
        }

        // update periodically
        m_handler.removeCallbacks(m_proc_runable);
        m_handler.postDelayed(m_proc_runable, m_interval);

    }

    void updateStatus() {
        updateStatus(null);
    }

        @Override
    protected void onResume() {
        super.onResume();
        m_paused = false ;
        // m_proc_runable.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        m_paused = true ;
    }
}
