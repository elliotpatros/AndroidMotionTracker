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
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    //----------------------------------------------------------------------------------------------
    // class members
    //----------------------------------------------------------------------------------------------
    // widgets
    private LinearLayout _layout;
    private Button _button_toggleStreaming;
    private EditText _editText_ipAddress;
    private EditText _editText_portNumber;
    private EditText _editText_streamInterval;

    // sensors
    private SensorManager _sensorManager;
    private Sensor _accelerometer;
    private AccelerometerData _accelData;

    // streaming
    private boolean _streamIsConnected;
    private StreamingTask _streamingThread;
    private int _streamInterval;

    // gui state
    static final private String KEY_IP_ADDRESS = "KEY_IP_ADDRESS";
    static final private String KEY_PORT_NUMBER = "KEY_PORT_NUMBER";
    static final private String KEY_STREAM_INT = "KEY_STREAM_INT";

    //----------------------------------------------------------------------------------------------
    // activity stuff
    //----------------------------------------------------------------------------------------------
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // find widgets
        _layout = (LinearLayout)findViewById(R.id.activity_main);
        _button_toggleStreaming = (Button)findViewById(R.id.button_toggleStreaming);
        _editText_ipAddress = (EditText)findViewById(R.id.editText_ipAddress);
        _editText_portNumber = (EditText)findViewById(R.id.editText_portNumber);
        _editText_streamInterval = (EditText)findViewById(R.id.editText_streamInterval);

        // setup sensors
        _sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        _accelerometer = _sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        _accelData = new AccelerometerData();

        // setup widgets
        if (savedInstanceState != null) {
            _editText_ipAddress.setText(savedInstanceState.getString(KEY_IP_ADDRESS));
            _editText_portNumber.setText(String.valueOf(savedInstanceState.getInt(KEY_PORT_NUMBER)));
            _editText_streamInterval.setText(String.valueOf(savedInstanceState.getInt(KEY_STREAM_INT)));
        } else {
            _editText_ipAddress.setText(R.string.default_ipAddress);
            _editText_portNumber.setText(R.string.default_portNumber);
            _editText_streamInterval.setText(R.string.default_streamInt);
        }

        _layout.setOnTouchListener(_touchListener);
        _button_toggleStreaming.setOnClickListener(_buttonListener);
        _editText_streamInterval.setOnFocusChangeListener(_focusChangeListener);
        _streamInterval = getNumberFromEditText(_editText_streamInterval);
    }

    @Override
    protected void onResume() {
        super.onResume();
        _sensorManager.registerListener(_sensorListener, _accelerometer, SensorManager.SENSOR_DELAY_GAME);
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
        state.putInt(KEY_PORT_NUMBER, getNumberFromEditText(_editText_portNumber));
        state.putInt(KEY_STREAM_INT, getNumberFromEditText(_editText_streamInterval));
    }

    //----------------------------------------------------------------------------------------------
    // button handling
    //----------------------------------------------------------------------------------------------
    final private Button.OnClickListener _buttonListener = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (v.getId() == R.id.button_toggleStreaming) {
                toggleStreaming();
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

    //----------------------------------------------------------------------------------------------
    // edit text handling
    //----------------------------------------------------------------------------------------------
    final private View.OnFocusChangeListener _focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (v == _editText_streamInterval && !hasFocus) {
                _streamInterval = getNumberFromEditText((EditText)v);
            }
        }
    };

    final private View.OnTouchListener _touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (v == _layout) {
                // request focus
                _layout.requestFocus();
                // disable the keyboard
                final InputMethodManager imm = (InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                // consume touch event
                return true;
            }

            return false;
        }
    };

    //----------------------------------------------------------------------------------------------
    // sensor handling
    //----------------------------------------------------------------------------------------------
    final private SensorEventListener _sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                _accelData.handleEvent(event.values);
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

    //----------------------------------------------------------------------------------------------
    // streaming
    //----------------------------------------------------------------------------------------------
    private class StreamingTask extends AsyncTask<Void, Void, Void> {
        DatagramSocket socket = null;
        String errorMessage = "";

        @Override
        protected Void doInBackground(Void... params) {
            try {
                // connect to server
                final InetAddress host = InetAddress.getByName(getIpAddress());
                final int port = getNumberFromEditText(_editText_portNumber);

                // make the socket
                socket = new DatagramSocket();

                // stream data
                while (_streamIsConnected) {
                    // make the datagram packet
                    final byte[] data = String.format(Locale.US, "%f %f %f", _accelData.x, _accelData.y, _accelData.z).getBytes();
                    socket.send(new DatagramPacket(data, data.length, host, port));
                    Thread.sleep(_streamInterval);
                }
            } catch (Exception e) {
                errorMessage = e.getMessage();
            } finally {
                // close connection
                if (socket != null) {
                    socket.close();
                }
            }

            return null;
        }

        @Override
        protected void onPreExecute() {
            // tell gui we're streaming
            super.onPreExecute();
            if (_button_toggleStreaming != null) {
                _button_toggleStreaming.setText(R.string.buttonText_StopStreaming);
                _button_toggleStreaming.setEnabled(true);
            }
            _streamIsConnected = true;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            // tell gui we're done streaming
            super.onPostExecute(aVoid);
            if (_button_toggleStreaming != null) {
                _button_toggleStreaming.setText(R.string.buttonText_StartStreaming);
                _button_toggleStreaming.setEnabled(true);
            }
            _streamIsConnected = false;

            // set error message
            if (!errorMessage.isEmpty()) {
                Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // private functions
    //----------------------------------------------------------------------------------------------
    private int getNumberFromEditText(EditText editText) {
        int number = 0;

        if (editText != null) {
            try {
                number = Integer.parseInt(editText.getText().toString());
            } catch (NumberFormatException e){
                // nothing to do...
            }
        }

        return number;
    }

    private String getIpAddress() {
        String ipAddress = "";

        if (_editText_ipAddress != null) {
            ipAddress = _editText_ipAddress.getText().toString();
        }

        return ipAddress;
    }
}
