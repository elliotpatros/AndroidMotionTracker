package com.empco.motiontracker;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;

public class MainActivity extends AppCompatActivity {

    //==============================================================================================
    // class members
    //==============================================================================================
    // widgets
    private Button _button_toggleStreaming;
    private EditText _editText_ipAddress;
    private EditText _editText_portNumber;

    // sensors
    private SensorManager _sensorManager;
    private Sensor _accelerometer;
    private AccelerometerData _accelerometerData;

    // streaming
    private boolean _streamIsConnected;
    private StreamingTask _streamingThread;

    // gui state
    static final private String KEY_IP_ADDRESS = "KEY_IP_ADDRESS";
    static final private String KEY_PORT_NUMBER = "KEY_PORT_NUMBER";

    //==============================================================================================
    // activity stuff
    //==============================================================================================
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // find widgets
        _button_toggleStreaming = (Button)findViewById(R.id.button_toggleStreaming);
        _editText_ipAddress = (EditText)findViewById(R.id.editText_ipAddress);
        _editText_portNumber = (EditText)findViewById(R.id.editText_portNumber);

        // setup sensors
        _sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        _accelerometer = _sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        _accelerometerData = new AccelerometerData();

        // setup widgets
        _button_toggleStreaming.setOnClickListener(_buttonListener);
        if (savedInstanceState != null) {
            _editText_ipAddress.setText(savedInstanceState.getString(KEY_IP_ADDRESS));
            _editText_portNumber.setText(String.valueOf(savedInstanceState.getInt(KEY_PORT_NUMBER)));
        } else {
            _editText_ipAddress.setText(R.string.default_ipAddress);
            _editText_portNumber.setText(R.string.default_portNumber);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        _sensorManager.registerListener(_sensorListener, _accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        _streamIsConnected = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        _sensorManager.unregisterListener(_sensorListener);
        _streamIsConnected = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(KEY_IP_ADDRESS, getIpAddress());
        state.putInt(KEY_PORT_NUMBER, getPortNumber());
    }

    //==============================================================================================
    // button handling
    //==============================================================================================
    final private Button.OnClickListener _buttonListener = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.button_toggleStreaming:
                    toggleStreaming();
                    break;
                default:
                    break;
            }
        }
    };

    private void toggleStreaming() {
        if (_streamingThread != null && _streamingThread.getStatus() == AsyncTask.Status.RUNNING) {
            // turn off streaming
            _streamIsConnected = false;
            _button_toggleStreaming.setText(R.string.buttonText_StoppingStream);
            _button_toggleStreaming.setEnabled(false);
        } else {
            // turn on streaming
            _button_toggleStreaming.setText(R.string.buttonText_StartingStream);
            _button_toggleStreaming.setEnabled(false);
            _streamingThread = new StreamingTask();
            _streamingThread.execute();
        }
    }

    //==============================================================================================
    // sensor handling
    //==============================================================================================
    final private SensorEventListener _sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            switch (event.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    _accelerometerData.handleEvent(event.values);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // nothing to do...
        }
    };

    private class AccelerometerData {
        private double x, y, z;

        AccelerometerData() {
            reset();
        }

        private void reset() {
            x = y = z = 0.0;
        }

        private void handleEvent(float[] values) {
            if (values.length < 3) {
                reset();
                return;
            }

            x = values[0];
            y = values[1];
            z = values[2];
        }
    }

    //==============================================================================================
    // streaming
    //==============================================================================================
    private class StreamingTask extends AsyncTask<Void, Void, Void> {
        Socket socket;
        String errorMessage = "";

        @Override
        protected Void doInBackground(Void... params) {
            try {
                // connect to server
                final int port = getPortNumber();
                final String ipAddress = getIpAddress();
                socket = new Socket(ipAddress, port);

                // stream data
                PrintWriter outStream = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                while (_streamIsConnected) {
                    outStream.printf("%f %f %f\n", _accelerometerData.x, _accelerometerData.y, _accelerometerData.z);
                    outStream.flush();
                    Thread.sleep(150);
                }
            } catch (Exception e) {
                e.printStackTrace();
                errorMessage = e.getMessage();
            } finally {
                // close connection
                try {
                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return null;
        }

        @Override
        protected void onPreExecute() {
            // tell gui we're streaming
            super.onPreExecute();
            if (_button_toggleStreaming == null) {return; }
            _button_toggleStreaming.setText(R.string.buttonText_StopStreaming);
            _button_toggleStreaming.setEnabled(true);
            _streamIsConnected = true;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            // tell gui we're done streaming
            super.onPostExecute(aVoid);
            if (_button_toggleStreaming == null) {return; }
            _button_toggleStreaming.setText(R.string.buttonText_StartStreaming);
            _button_toggleStreaming.setEnabled(true);
            _streamIsConnected = false;

            // set error message
            if (!errorMessage.isEmpty()) {
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        }
    }

    //==============================================================================================
    // private functions
    //==============================================================================================
    private int getPortNumber() {
        int port = 0;

        if (_editText_portNumber != null) {
            try {
                port = Integer.parseInt(_editText_portNumber.getText().toString());
            } catch (NumberFormatException e){
                e.printStackTrace();
            }
        }

        return port;
    }

    private String getIpAddress() {
        String ipAddress = "";

        if (_editText_ipAddress != null) {
            ipAddress = _editText_ipAddress.getText().toString();
        }

        return ipAddress;
    }
}
