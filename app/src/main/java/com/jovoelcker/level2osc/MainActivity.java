package com.jovoelcker.level2osc;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity
{
    // The minimum and maximum decibels mapped on the progress bar
    final double INTENSITY_BAR_MIN = 0;
    final double INTENSITY_BAR_MAX = 120;

    // Settings needed to do a record for the calculation of the sound pressure
    final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    final int SAMPLE_RATE_IN_HZ = 44100;
    final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // The buffer size depends on the record settings
    int bufferSizeInBytes;

    // The connection state of the last check
    boolean wasConnected = false;

    // Some objects need to be accessible from all threads
    AudioRecord audioRecord;
    ConnectivityManager connectivityManager = null;
    NetworkInfo networkInfo = null;
    DatagramSocket socket = null;

    volatile int port = 0;
    volatile boolean isRunning = true;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize UI elements
        setPortChangedEvent();
        setButtonClickEvent();

        // Get the minimum buffer size for the given settings
        bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);

        // Initialize the record object
        audioRecord = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSizeInBytes);
        audioRecord.startRecording();

        // Run the level calculation as a thread
        new Thread(new Runnable() {
            public void run() {
                calculateSPL();
            }
        }).start();
    }

    @Override
    public void onPause() {
        super.onPause();

        finish();
    }

    @Override
    public void onStop() {
        super.onStop();

        finish();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        isRunning = false;

        if (audioRecord != null)
        {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (socket != null)
        {
            socket.close();
            socket = null;
        }
    }


    private void setPortChangedEvent()
    {
        EditText portInput = (EditText)findViewById(R.id.portInput);

        portInput.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                EditText portInput = (EditText) findViewById(R.id.portInput);

                                try {
                                    port = Integer.parseInt(portInput.getText().toString());
                                } catch (Exception e) {
                                    port = 0;
                                }

                                if (socket != null)
                                {
                                    socket.close();
                                    socket = null;
                                }
                            }
                        });
                    }
                }
        );
    }

    private void setButtonClickEvent()
    {
        Button sendButton = (Button)findViewById(R.id.sendButton);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run() {
                        sendOSCMessage("/Level2OSC/Button");
                    }
                }).start();
            }
        });
    }


    private void calculateSPL()
    {
        // Get the buffer entries of the recording
        short[] audioData = new short[bufferSizeInBytes];
        audioRecord.read(audioData, 0, bufferSizeInBytes);

        // The sound pressure is the RMS of all single values
        double soundPressure = rootMeanSquare(audioData) * 2;

        // Calculate the sound pressure level
        double measuredSPL = 20 * Math.log10(soundPressure);

        if (measuredSPL > 0)
            setCurrentSPL(measuredSPL);
        else
            setCurrentSPL(0);
    }

    private double rootMeanSquare(short[] input)
    {
        // Sums up all the entries in the buffer and calculates their RMS
        long squareSum = 0;
        for (int i = 0; i < input.length; i++)
        {
            int element = input[i];
            squareSum += element * element;
        }
        return Math.sqrt(squareSum / input.length);
    }

    private void setCurrentSPL(final double currentSPL)
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Display the calculated level on the progress bar
                TextView intensityBarLabel = (TextView) findViewById(R.id.levelLabel);
                ProgressBar intensityBar = (ProgressBar) findViewById(R.id.levelBar);

                intensityBarLabel.setText(getString(R.string.levelLabel) + " " + (int)currentSPL);

                if (currentSPL <= INTENSITY_BAR_MIN)
                    intensityBar.setProgress(0);
                else if (currentSPL >= INTENSITY_BAR_MAX)
                    intensityBar.setProgress(100);
                else
                    intensityBar.setProgress((int) ((currentSPL - INTENSITY_BAR_MIN) / (INTENSITY_BAR_MAX - INTENSITY_BAR_MIN) * 100));
            }
        });

        if (isRunning)
        {
            // Send the value via OSC and calculate again
            new Thread(new Runnable() {
                public void run() {
                    sendOSCMessage("/Level2OSC/Level", (int)currentSPL);
                }
            }).start();

            new Thread(new Runnable() {
                public void run() {
                    calculateSPL();
                }
            }).start();
        }
    }


    private void sendOSCMessage(byte[] byteArray)
    {
        if (byteArray != null && isCurrentlyConnected() && port > 0)
        {
            try
            {
                if (socket == null)
                {
                    socket = new DatagramSocket(port);
                    socket.setBroadcast(true);
                }

                DatagramPacket packet = new DatagramPacket(byteArray, byteArray.length, getBroadcastAddress(), port);
                socket.send(packet);

                // The package was successfully sent, so we are connected
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Button sendButton = (Button)findViewById(R.id.sendButton);
                        sendButton.setEnabled(true);

                        TextView portStatus = (TextView)findViewById(R.id.portStatus);
                        portStatus.setText(getString(R.string.portStatus_connected));
                    }
                });
            }
            catch (Exception e)
            {
                // The connection doesn't work properly
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Button sendButton = (Button)findViewById(R.id.sendButton);
                        sendButton.setEnabled(false);

                        TextView portStatus = (TextView)findViewById(R.id.portStatus);
                        portStatus.setText(getString(R.string.portStatus_notavailable));
                    }
                });
            }
        }
    }

    private void sendOSCMessage(String addressPattern)
    {
        byte[] addressPatternByteArray = getAddressPatternByteArray(addressPattern);

        byte[] bytesToAppend = new byte[4];

        // OSC needs a type tag even if there are no values
        bytesToAppend[0] = (byte)',';
        bytesToAppend[1] = 0;

        // Padding
        bytesToAppend[2] = 0;
        bytesToAppend[3] = 0;

        byte[] finalByteArray = appendBytesToByteArray(addressPatternByteArray, bytesToAppend);

        sendOSCMessage(finalByteArray);
    }

    private void sendOSCMessage(String addressPattern, int value)
    {
        byte[] addressPatternByteArray = getAddressPatternByteArray(addressPattern);

        // The array needs 8 bytes more than the given address byte array to also consist an int
        byte[] bytesToAppend = new byte[8];

        // OSC values start with a comma and the value type
        bytesToAppend[0] = (byte)',';
        bytesToAppend[1] = (byte)'i';

        // Padding
        bytesToAppend[2] = 0;
        bytesToAppend[3] = 0;

        // The int value has to be converted to bytes
        byte[] valueBytes = ByteBuffer.allocate(4).putInt(value).array();

        bytesToAppend[4] = valueBytes[0];
        bytesToAppend[5] = valueBytes[1];
        bytesToAppend[6] = valueBytes[2];
        bytesToAppend[7] = valueBytes[3];

        byte[] finalByteArray = appendBytesToByteArray(addressPatternByteArray, bytesToAppend);

        sendOSCMessage(finalByteArray);
    }

    private byte[] getAddressPatternByteArray(String addressPattern)
    {
        byte[] stringByteArray = addressPattern.getBytes();

        // After the address pattern have to be some zeros
        int byteArrayLength = stringByteArray.length;

        // The byte array has to have a number of elements dividable by 4
        if (byteArrayLength % 4 == 0)
            byteArrayLength += 4;
        else
            while (byteArrayLength % 4 != 0)
                byteArrayLength++;

        byte[] byteArray = new byte[byteArrayLength];

        // The old byte array has to be transferred
        for (int i = 0; i < stringByteArray.length; i++)
        {
            byteArray[i] = stringByteArray[i];
        }

        // Fill the empty slots with 0 bits
        for (int i = 0; i < byteArray.length - stringByteArray.length; i++)
        {
            byteArray[stringByteArray.length + i] = 0;
        }

        return byteArray;
    }

    InetAddress getBroadcastAddress() throws IOException
    {
        WifiManager wifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();

        int broadcast = (dhcpInfo.ipAddress & dhcpInfo.netmask) | ~dhcpInfo.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++)
            quads[k] = (byte)((broadcast >> k * 8) & 0xFF);

        return InetAddress.getByAddress(quads);
    }

    byte[] appendBytesToByteArray(byte[] oldArray, byte[] bytesToAppend)
    {
        byte[] newArray = new byte[oldArray.length + bytesToAppend.length];

        for (int i = 0; i < oldArray.length; i++)
        {
            newArray[i] = (byte)oldArray[i];
        }

        for (int i = 0; i < bytesToAppend.length; i++)
        {
            newArray[oldArray.length + i] = (byte)bytesToAppend[i];
        }

        return newArray;
    }


    private boolean isCurrentlyConnected()
    {
        // Get the wifi connection state from builtin features
        if (connectivityManager == null)
            connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        networkInfo = connectivityManager.getActiveNetworkInfo();

        if (networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_WIFI)
        {
            WifiManager wifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);

            if (wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED)
            {
                if (!wasConnected)
                {
                    DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
                    byte[] bytes = BigInteger.valueOf(Integer.reverseBytes(dhcpInfo.ipAddress)).toByteArray();

                    try
                    {
                        final InetAddress ipAddress = InetAddress.getByAddress(bytes);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                TextView ipStatus = (TextView)findViewById(R.id.ipStatus);
                                ipStatus.setText(ipAddress.getHostAddress());

                                TextView portStatus = (TextView)findViewById(R.id.portStatus);
                                if (port > 0)
                                    portStatus.setText(getString(R.string.portStatus_notavailable));
                                else
                                    portStatus.setText(getString(R.string.portStatus_empty));
                            }
                        });
                        wasConnected = true;
                    }
                    catch (UnknownHostException e) {}
                }
            }
            else
                wasConnected = false;
        }
        else
            wasConnected = false;

        if (wasConnected)
        {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // If the wifi is connected, the user should be able to set a port
                    EditText portInput = (EditText)findViewById(R.id.portInput);
                    portInput.setEnabled(true);
                }
            });
        }
        else
        {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Button button = (Button)findViewById(R.id.sendButton);
                    TextView ipStatus = (TextView)findViewById(R.id.ipStatus);
                    EditText portInput = (EditText)findViewById(R.id.portInput);
                    TextView portStatus = (TextView)findViewById(R.id.portStatus);

                    // If the wifi isn't connected, disable all impossible commands
                    button.setEnabled(false);
                    ipStatus.setText(getString(R.string.ipStatus_nowifi));
                    portInput.setEnabled(false);
                    portStatus.setText(getString(R.string.portStatus_nowifi));
                }
            });
        }

        return wasConnected;
    }
}
