package ca.cuni.monitor;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native ;
import com.sun.jna.Library ;
import com.sun.jna.Platform;
import com.sun.jna.Structure;

public class MonActivity extends Activity {

    private boolean m_paused = true ;
    private Handler m_handler ;
    private Runnable m_proc_runable ;
    private int m_sort ;        // 0: by adj, 1: by cpu, 2: by name
    private int m_refreshtime = 5 ;

    private class ProcStat {
        public int   uid ;
        public long  cpuTime ;
        public int   oom_score ;
    }

    private class AppInfo {
        public int uid ;
        public int priority = 0 ;
        public String label ;
        public Drawable icon ;
        public boolean  enabled ;
        public Set <String> packages = new HashSet<String>();;
    }

    static final String [] adj_levels = {
            "System",
            "Foreground",
            "Home",
            "Services",
            "Background",
            "Cache",
            "CacheEmpty"};

    static final int [] adj_scores = {
            0,
            60,
            120,
            180,
            512,
            1000,
            50000 };

    private int zygotePid = -1 ;   // zygote pid

    private ListView listView ;
    Map <Integer, ProcStat> plist_x = new HashMap();
    Map <Integer, ProcStat> plist = new HashMap(); ;
    Set <String>  hiSet ;

    private class PackageArrayAdapter extends ArrayAdapter<AppInfo> implements Comparator <AppInfo> {

        public PackageArrayAdapter(Context context ) {
            super(context, R.layout.packageslist);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View rowView ;
            AppInfo ai ;

            int w = parent.getWidth();
            if(convertView==null) {
                LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                rowView = inflater.inflate(R.layout.packageslist, parent, false);
            }
            else {
                rowView = convertView ;
            }

            ai = this.getItem(position);
            rowView.setId( 1000+position ) ;
            rowView.setTag(ai);

            // Change icon based on name
            try {
                int backgrondcolor[] = { 0,android.R.color.holo_green_light,android.R.color.holo_blue_light };
                rowView.setBackgroundResource(backgrondcolor[ai.priority]);

                if( !ai.enabled )
                    rowView.setBackgroundResource(android.R.color.darker_gray);
                else if( plist.containsKey(ai.uid) ) {
                    rowView.setBackgroundResource( android.R.color.holo_green_light );
                }

                String packagename = "" ;
                for ( String s : ai.packages ) {
                    packagename = s ;
                    break;
                }
                ((TextView) rowView.findViewById(R.id.pname)).setText(packagename);
                ((TextView) rowView.findViewById(R.id.plabel)).setText(ai.label);
                ((ImageView) rowView.findViewById(R.id.picon)).setImageDrawable(ai.icon);
            } catch ( Exception e) {
                e.printStackTrace();
            }

            TextView tv = (TextView) rowView.findViewById(R.id.cpu) ;
            TextView oomv = (TextView) rowView.findViewById(R.id.oom) ;

            float cpudiff = 100.0f / (float) (m_cputime-m_pre_cputime) ;
            if( plist_x.containsKey(ai.uid) && plist.containsKey(ai.uid)) {
                ProcStat ps = plist.get(ai.uid) ;
                ProcStat xps = plist_x.get(ai.uid) ;

                // valid value
                tv.setText(String.format("%.2f%%", cpudiff * ((float) (ps.cpuTime - xps.cpuTime))));
                int score = ps.oom_score ;
                String tx="CacheEmpty";
                for( int s=0 ; s<adj_scores.length; s++ ) {
                    if( score<=adj_scores[s] ) {
                        tx = adj_levels[s] ;
                        break;
                    }
                }
                oomv.setText( tx+" ("+score+")" );

            }
            else if( ai.enabled ) {
                tv.setText("");
                oomv.setText("");
            }
            else {
                tv.setText("Disabled");
                oomv.setText("");
            }

            return rowView;
        }

        @Override
        public int compare(AppInfo lhs, AppInfo rhs) {
            int diff ;
            int loo ;
            int roo ;
            diff = rhs.priority - lhs.priority ;
            if( diff == 0 ) {
                loo = 0;
                roo = 0;
                if( m_sort == 0 ) {     // by adj
                    if (plist.containsKey(lhs.uid) && plist_x.containsKey(lhs.uid)) {
                        loo = plist.get(lhs.uid).oom_score;
                    }
                    if (plist.containsKey(rhs.uid) && plist_x.containsKey(rhs.uid)) {
                        roo = plist.get(rhs.uid).oom_score;
                    }
                }
                else if( m_sort == 1 ) {    // by cpu
                    if (plist.containsKey(lhs.uid) && plist_x.containsKey(lhs.uid)) {
                        loo = (int)(plist.get(lhs.uid).cpuTime - plist_x.get(lhs.uid).cpuTime) + 100;
                    }
                    if (plist.containsKey(rhs.uid) && plist_x.containsKey(rhs.uid)) {
                        roo = (int)(plist.get(rhs.uid).cpuTime - plist_x.get(rhs.uid).cpuTime) + 100;
                    }
                }
                diff = roo - loo ;
            }
            if( diff == 0 ) {
                loo = lhs.enabled?1:0 ;
                roo = rhs.enabled?1:0 ;
                diff = roo - loo ;
            }
            if( diff == 0 ) {
                diff = lhs.label.compareTo(rhs.label);
            }
            return diff ;
        }

        public void sort()
        {
            sort(this);
            notifyDataSetChanged();
        }

        // refresh app list
        public void ReLoad() {
            Map <Integer, AppInfo > packageList = new TreeMap<>();

            PackageManager pm = getPackageManager();

            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                int uid = ai.uid ;
                if( uid<1000 )
                    continue ;

                AppInfo ps ;

                if (packageList.containsKey( uid )) {
                    ps = packageList.get(uid);
                    if (ps.icon == null) {
                        ps.icon = pm.getApplicationIcon(ai);
                        if( ps.icon!=null ) {
                            ps.label = pm.getApplicationLabel(ai).toString();
                        }
                    }

                    ps.packages.add(ai.packageName);

                }
                else {
                    ps = new AppInfo();
                    ps.uid = uid ;
                    ps.priority = 0;

                    ps.enabled = ai.enabled ;

                    // label
                    ps.label = pm.getApplicationLabel(ai).toString();

                    // icon
                    ps.icon = pm.getApplicationIcon(ai);
                    ps.packages.add(ai.packageName);

                    packageList.put(uid, ps);

                }

            }

            this.clear();
            for(  Integer key : packageList.keySet() ) {
                this.add(packageList.get(key));
            }
            Refresh();
        }

        // refresh app list
        public void Refresh() {
            sort();
        }

        public void setHiSet( Set<String> hiSet ) {
            for (int pos = 0; pos < this.getCount(); pos++) {
                AppInfo ps = getItem(pos);
                if (hiSet.contains(ps.label)) {
                    ps.priority = 2;
                }
            }
        }

        public Set <String> getHiSet() {
            HashSet <String> hiSet = new HashSet<>();
            for( int pos=0; pos<this.getCount(); pos++ ) {
                AppInfo ps = getItem(pos);
                if(  ps.priority == 2 ) {
                    hiSet.add(ps.label);
                }
            }
            return hiSet ;
        }
    }

    // scan and read all app stat s
    private int getZygotePid() {
        try {
			int pid = android.os.Process.myPid() ;
            BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/stat"));
            String line = reader.readLine();
            String []aa =  line.split("\\s+", 5);
            return Integer.parseInt(aa[3]);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return -1 ;
    }

    private ProcStat getPidStat(String pid) {
        BufferedReader reader ;
        String line ;
        String [] aa ;

        if( Integer.parseInt(pid) < 300 ) return  null;

        try {

            ProcStat ps = new ProcStat();

            File stat = new File("/proc/" + pid + "/status");
            if (!stat.canRead()) {
                return null;
            }
            reader = new BufferedReader(new FileReader( stat ));
            if (reader == null) return null;
            while( (line = reader.readLine())!= null ) {
                aa = line.split("\\s+", 3 );
                if( aa.length>=2 ) {
                    if( aa[0].equals("Uid:") ) {
                        ps.uid = Integer.parseInt(aa[1]) ;
                        break;
                    }
                    else if( aa[0].equals("PPid:") ) {
                        if( Integer.parseInt(aa[1]) != zygotePid ) {
                            return null ;
                        }
                    }
                }
            }

            if( ps.uid < 1000 ) {
                return null ;
            }


            stat = new File("/proc/" + pid + "/stat");
            if (!stat.canRead()) {
                return null;
            }

            reader = new BufferedReader(new FileReader("/proc/" + pid + "/stat"));
            if (reader == null) return null;
            line = reader.readLine();
            reader.close();
            if (line == null) return null;
            int p = line.indexOf(')') ;
            if( p<3 ) return null ;
            aa = line.substring(p+2).split("\\s+", 20);
            if (aa.length > 18) {
                int ppid = Integer.parseInt(aa[1]);
                if (ppid != zygotePid) {
                    return null;
                }

                ps.cpuTime = Long.parseLong(aa[11]) +
                        Long.parseLong(aa[12]) +
                        Long.parseLong(aa[13]) +
                        Long.parseLong(aa[14]);

                reader = new BufferedReader(new FileReader("/proc/" + pid + "/oom_score"));
                if (reader == null) return null;
                line = reader.readLine();
                reader.close();
                if (line == null) {
                    ps.oom_score = 0 ;
                }
                else {
                    ps.oom_score = Integer.parseInt(line.trim());
                }

                return ps ;
            }

        }catch(FileNotFoundException e){
            e.printStackTrace();
        }catch(NumberFormatException e){
            e.printStackTrace();
        }catch(IOException e){
            e.printStackTrace();
        }
        return null ;
    }

    private long m_pre_cputime = -1 ;
    private long m_cputime = 0 ;
    private long m_pre_idletime  ;
    private long m_idletime ;
    void readCpuStat()
    {
        BufferedReader reader ;
        String line ;
        String [] aa ;

        // read /proc/stat
        long cputime = 0 ;
        try {
            reader = new BufferedReader(new FileReader("/proc/stat"));
            line = reader.readLine();
            // example: cpu  2052435 323871 1754071 8556990 101401 1129 73045 0 0 0
            // example: cpu  328945 66288 281658 1925670 11757 254 12426 0 0 0
            aa = line.split("\\s+", 20 );
            for(int i=1; i<aa.length ; i++) {
                cputime+=Long.parseLong(aa[i]);
            }

            synchronized (this) {
                m_pre_cputime = m_cputime;
                m_cputime = cputime;
                m_pre_idletime = m_idletime;
                m_idletime = Long.parseLong(aa[4]);
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private class KillBackgroundProcesses extends AsyncTask<Void, Void, Void> {
        Set<String> PackagesToBeKill ;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            PackagesToBeKill = new HashSet<>() ;
            PackageArrayAdapter pa = (PackageArrayAdapter) listView.getAdapter() ;

            for(int pos=0; pos<pa.getCount(); pos++) {
                AppInfo ai = pa.getItem(pos) ;
                for( String p : ai.packages ) {
                    PackagesToBeKill.add(p);
                }
            }
        }

        protected Void doInBackground(Void... params) {
            try {
                ActivityManager am = (ActivityManager) getApplicationContext().getSystemService(ACTIVITY_SERVICE);
                for (String proc : PackagesToBeKill ) {
                    am.killBackgroundProcesses(proc);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            return (Void) null;
        }

        protected void onPostExecute(Void result) {
        }
    }

    private class ProcessScanTask extends AsyncTask<Void, Void, Map<Integer, ProcStat> > {

        protected Map<Integer, ProcStat> doInBackground(Void... params) {

            Map <Integer, ProcStat> proclist  = new HashMap();


            File proc = new File ("/proc/8729/stat") ;
            Boolean t = proc.canRead() ;
            String [] li = new String[0];


            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(proc));
                if (reader != null) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        li[li.length] = line;
                    }
                }
            }catch(FileNotFoundException e){
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }


            proc = new File ("/proc/") ;
            li = proc.list();

            // navigate /proc trees
            for( String p : li ) {

                if( !Character.isDigit(p.charAt(0)) ) {
                    continue ;
                }

                ProcStat ps = getPidStat(p) ;
                if(ps!=null) {
                    if( proclist.containsKey(ps.uid)) {
                        ProcStat xps = proclist.get(ps.uid) ;
                        xps.cpuTime += ps.cpuTime ;
                        if( ps.oom_score < xps.oom_score ) {
                            xps.oom_score = ps.oom_score ;
                        }
                    }
                    else {
                        proclist.put(ps.uid, ps);
                    }
                }
            }

            readCpuStat();
            return proclist;
        }

        protected void onPostExecute(Map<Integer, ProcStat>  proclist) {

            long cpuusage = 1000 - 1000*(m_idletime-m_pre_idletime)/(m_cputime-m_pre_cputime);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(mi);
            String subtitle = String.format("CPU : %.1f%%  Mem : %.2fG/%.2fG",
                            (((float)cpuusage)/10.0f),
                            (((float)mi.availMem)/1.0e+9),
                            (((float)mi.totalMem)/1.0e+9) );

            ActionBar bar = getActionBar();
            bar.setSubtitle(subtitle);

            plist_x = plist ;
            plist = proclist ;

            ((PackageArrayAdapter) listView.getAdapter()).notifyDataSetChanged();

            // repeat call proc runnable
            m_handler.removeCallbacks(m_proc_runable);
            m_handler.postDelayed(m_proc_runable, m_refreshtime*1000);

        }

    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        //MenuInflater inflater = getMenuInflater();
        //inflater.inflate(R.menu.menu_context, menu);

        Intent intent ;
        MenuItem mi ;
        PackageManager pm = getPackageManager();

        AppInfo pi = (AppInfo) ((AdapterView.AdapterContextMenuInfo) menuInfo).targetView.getTag() ;
        // String pname = (String)(((ListView)v).getAdapter().getItem(pos)) ;
        //AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        //String pname = ((TextView)info.targetView.findViewById(R.id.pname)).getText().toString();
        // AppInfo pi = packageList.get(pname) ;

        menu.setHeaderIcon(pi.icon) ;
        menu.setHeaderTitle(pi.label) ;

        int id=1000 ;

        if( pi.packages.size() == 1 ) {
            for( String s : pi.packages ) {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s ));
                if( intent.resolveActivity(pm)!=null) {
                    mi = menu.add(0, id++, 0, "App Info");
                    mi.setIntent(intent);
                }
            }
        }
        else if( pi.packages.size() > 1 ) {
            for( String s : pi.packages ) {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + s ));
                if( intent.resolveActivity(pm)!=null) {
                    mi = menu.add(0, id++, 0, "Info: " + s);
                    mi.setIntent(intent);
                }
            }
        }
        else {
            return ;
        }

        id=2000 ;
        for( String s : pi.packages ) {
            intent = pm.getLaunchIntentForPackage(s);
            if (intent != null) {
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                try {
                    mi = menu.add(0, id++, 0, "Run: " + pm.getApplicationLabel(pm.getApplicationInfo(s,0)) ) ;
                    mi.setIntent( intent ) ;
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }

        id=3000 ;
        ActivityManager am = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);

        for(ActivityManager.RunningServiceInfo rsi : am.getRunningServices(200) ) {
            if( pi.uid == rsi.uid ) {
                menu.add(0, id++, 0, "Serv: " + rsi.service.flattenToShortString()) ;
            }
        }

    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        String pname = ((TextView)info.targetView.findViewById(R.id.pname)).getText().toString();

        int id = item.getItemId() ;
        if( id>=3000 && id< 3100 ) {
            ActivityManager am = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);
            String title = item.getTitle().toString() ;
            String pack = title.substring(6) ;
            try {
                PendingIntent pendingIntent = am.getRunningServiceControlPanel(ComponentName.unflattenFromString(pack)) ;
                if( pendingIntent!=null)
                    pendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                e.printStackTrace();
            }
            return true ;
        }
        else {

            // fire the intent associated with menu
            return super.onContextItemSelected(item);
        }
    }

    private void onHighlightItem(View v) {
        AppInfo ps = (AppInfo) v.getTag() ;
        if( ps!=null ) {
            if( ps.priority==2 ) {
                ps.priority = 1 ;
            }
            else {
                ps.priority = 2 ;
            }

            if(ps.priority==0) {
                v.setBackgroundResource(0);
            }
            else if( ps.priority == 1 ) {
                v.setBackgroundResource(android.R.color.holo_green_light) ;
            }
            else {
                v.setBackgroundResource(android.R.color.holo_blue_light) ;
            }
        }
    }

    private boolean m_press = false ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mon);

        if( savedInstanceState!=null ) {
            m_sort = savedInstanceState.getInt("sort", 0);
            m_refreshtime = savedInstanceState.getInt("refreshtime", 5);
        }
        else {
            m_sort = 1 ;
        }

        m_paused = false ;
        m_handler = new Handler();
        m_proc_runable = new Runnable() {
            @Override
            public void run() {
                if( !m_paused )
                    new ProcessScanTask().execute();
            }
        } ;

        System.setProperty("jna.debug_load", "true");
        System.setProperty("jna.debug_load.jna", "true");

        // get zygote pid, this is parent of all apps
        // try use native interface
        if(Platform.isAndroid() ) {  // call this before loadnativelibrary to initialize some jna codes
            zygotePid = Function.getFunction("c", "getppid").invokeInt(null);
        }
        else {
            zygotePid = getZygotePid() ;
        }

        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        hiSet = pref.getStringSet("HiSet", null);

        listView = (ListView) findViewById(R.id.packageListView);
        listView.setAdapter(new PackageArrayAdapter(this));

        registerForContextMenu(listView);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                m_press = true ;
                parent.showContextMenuForChild(view);
                //view.showContextMenu();
                //onHighlightItem(view);
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if( m_press ) {
                    m_press = false ;
                    return false;
                }
                else {
                    onHighlightItem(view);
                    return true;
                }
            }

        });

        // read lowmemorykiller parameters
        File adj = new File("/sys/module/lowmemorykiller/parameters/adj" );
        if (adj.canRead()) {

            try {
                BufferedReader reader = new BufferedReader(new FileReader( adj ) );
                String line = reader.readLine();
                reader.close();
                if (line != null ) {
                    String [] aa = line.split(",", 8);
                    for( int i=0; i<aa.length && i<adj_scores.length; i++) {
                        adj_scores[i] = Integer.parseInt(aa[i]);
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }


    }

    @Override
    protected void onResume() {
        super.onResume();

        m_paused = false ;
        m_handler.post(m_proc_runable);

        PackageArrayAdapter pa = (PackageArrayAdapter) listView.getAdapter() ;
        pa.ReLoad() ;
    }

    @Override
    protected void onPause() {
        super.onPause();

        // stop proc stats
        m_handler.removeCallbacks(m_proc_runable);
        m_paused = true ;

        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor prefEdit = pref.edit();

        prefEdit.putStringSet("hiSet", hiSet) ;
        prefEdit.commit();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt("sort", m_sort);
        outState.putInt("refreshtime", m_refreshtime);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_mon, menu);

        MenuItem mi ;
        int sort_id ;
        if( m_sort == 1 ) sort_id = R.id.sort_cpu ;
        else if( m_sort == 2 ) sort_id = R.id.sort_name;
        else  sort_id = R.id.sort_adj;
        mi = menu.findItem(sort_id) ;
        mi.setChecked(true) ;

        // Add some more Special Menu
        PackageManager pm = getPackageManager();
        Intent intent ;
        ActivityInfo ai ;

        int extra_group = 100 ;
        int extra_order = 200 ;

        intent = new Intent(this, DumpsysActivity.class);
        intent.putExtra("cmd", "dumpsys");
        menu.add(extra_group, 0, extra_order++, "Dumpsys").setIntent(intent) ;

        intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.TestingSettings") ;
        ai = intent.resolveActivityInfo (pm, 0);
        if( ai!=null) {
            menu.add(extra_group, 0, extra_order++, "Testing Menu").setIntent(intent) ;
        }

        intent = new Intent();
        intent.setClassName("com.mediatek.engineermode", "com.mediatek.engineermode.EngineerMode") ;
        ai = intent.resolveActivityInfo (pm, 0);
        if( ai!=null) {
            menu.add(extra_group, 0, extra_order++, "MediaTek EngineerMode").setIntent(intent) ;
        }

        intent = new Intent();
        intent.setClassName("com.mediatek.lbs.em", "com.mediatek.lbs.em.MyTabActivity") ;
        ai = intent.resolveActivityInfo (pm, 0);
        if( ai!=null) {
            menu.add(extra_group, 0, extra_order++, "MediaTek Location Engineer Mode").setIntent(intent) ;
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }
        else if (id == R.id.action_refresh) {
            // restart new package list
            PackageArrayAdapter pa = (PackageArrayAdapter) listView.getAdapter() ;
            pa.Refresh();
            return true;
        }
        else if (id == R.id.action_killall) {
            new KillBackgroundProcesses().execute();
            return true;
        }
        else if (id == R.id.action_batterystatus) {
            Intent intent = new Intent(this, BatteryStatus.class);
            startActivity(intent);
            return true;
        }
        else if( id== R.id.sort_adj ) {
            item.setChecked(true);
            m_sort = 0 ;
            PackageArrayAdapter pa = (PackageArrayAdapter) listView.getAdapter() ;
            pa.Refresh();
            return true ;
        }
        else if( id== R.id.sort_cpu ) {
            item.setChecked(true);
            m_sort = 1 ;
            PackageArrayAdapter pa = (PackageArrayAdapter) listView.getAdapter() ;
            pa.Refresh();
            return true ;
        }
        else if( id== R.id.sort_name ) {
            item.setChecked(true);
            m_sort = 2 ;
            PackageArrayAdapter pa = (PackageArrayAdapter) listView.getAdapter() ;
            pa.Refresh();
            return true ;
        }
        else {
            return super.onOptionsItemSelected(item);
        }
    }
}
