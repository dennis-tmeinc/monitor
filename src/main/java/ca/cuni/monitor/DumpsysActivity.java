package ca.cuni.monitor;

import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class DumpsysActivity extends Activity {

    private class suExecTask extends AsyncTask<String, Void, String> {

        protected String doInBackground(String... cmd) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            Process process = null;
            try {
                process = new ProcessBuilder("su", "-c", cmd[0])
                        .redirectErrorStream(true)
                        .start();
                InputStream in = process.getInputStream();
                OutputStream out = process.getOutputStream();

                byte [] buffer = new byte [1024] ;
                int r ;
                while( (r=in.read( buffer ))>0 ) {
                    bout.write(buffer,0,r);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            finally {
                if( process != null )
                    process.destroy();
            }

            return bout.toString();
        }

        protected void onPostExecute(String text) {
            ((TextView)findViewById(R.id.dumpsystext)).setText(text);
        }
    }


    private class CmdExecTask extends AsyncTask<String, Void, String> {

        protected String doInBackground(String... cmd) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            Process process = null;
            try {
                process = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
                InputStream in = process.getInputStream();
                OutputStream out = process.getOutputStream();

                byte [] buffer = new byte [1024] ;
                int r ;
                while( (r=in.read( buffer ))>0 ) {
                    bout.write(buffer,0,r);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            finally {
                if( process != null )
                    process.destroy();
            }

            return bout.toString();
        }

        protected void onPostExecute(String text) {
            ((TextView)findViewById(R.id.dumpsystext)).setText(text);
        }
    }

    private class readFileTask extends AsyncTask<String, Void, String> {

        protected String doInBackground(String... file) {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try {
                FileInputStream fi = new FileInputStream(file[0]);
                byte [] buffer = new byte [1024] ;
                int r ;
                while( (r=fi.read( buffer ))>0 ) {
                    bout.write(buffer,0,r);
                }
                fi.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return bout.toString();
        }

        protected void onPostExecute(String text) {
            ((TextView)findViewById(R.id.dumpsystext)).setText(text);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dumpsys);

        // new CmdExecTask().execute("cat", "/proc/meminfo");
        new CmdExecTask().execute("dumpsys");
        // new readFileTask().execute("/proc/meminfo");
        // new suExecTask().execute("dumpsys");

    }


}
