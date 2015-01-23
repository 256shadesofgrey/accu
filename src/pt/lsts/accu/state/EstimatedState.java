package pt.lsts.accu.state;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import pt.lsts.accu.msg.IMCManager;
import pt.lsts.accu.types.Sys;
import pt.lsts.accu.util.AccuTimer;
import pt.lsts.accu.util.MUtil;
import pt.lsts.imc.IMCDefinition;
import pt.lsts.imc.IMCMessage;

import java.util.List;

/**
 * Created by miguel on 25-06-2014.
 */
public class EstimatedState {
    public static final String TAG = "EstimatedState";
    public static final int PORT = 30100;
    public static final long DELAY = 100; // 0.1 seconds
    public static final boolean DEBUG = false;
    public boolean sensorsAvailable;// Accelerometer AND Magnetometer Availible in device

    private IMCMessage imcMessage;//message to be sent
    private IMCManager imm;
    private AccuTimer timer;

    private int gpsAddListenerCounter = 0;
    private GPSManager gpsManager; // = Accu.getInstance().getGpsManager();
    private Location currentLocation;
    private double gpsBearingValue = 0.0;

    private SensorManager sensorManager;
    private Sensor sensorMagnetic;
    private Sensor sensorAccelerometer;

    //values from Ace/Magn
    private float azimuth = -1;
    private float pitch = -1;
    private float roll = -1;

    private final int N = 15;//number of values to make statistic average
    private boolean initBoolean = false;//arrays initilizalized
    private boolean outlier = false;//outlier last reading
    private int index = 0;
    private float[] azimuthArray = new float [N];
    private float[] pitchArray = new float [N];
    private float[] rollArray = new float [N];


    public EstimatedState(IMCManager imm) {
        this.imm = imm;

        timer = new AccuTimer(task, DELAY);
    }

    public List<Sys> getSystemList() {
        return Accu.getInstance().getSystemList().getList();
    }

    private void initializeSensors() {
        gpsManager = Accu.getInstance().getGpsManager();
        sensorManager = Accu.getInstance().getSensorManager();

        sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        //check if there is magnetometer/accelerometer
        if (sensorMagnetic == null || sensorAccelerometer == null) {
            sensorsAvailable = false;//GPS bearing instead
        } else {
            sensorsAvailable = true;//Use Ace/Magn
        }
    }

    private Runnable task = new Runnable() {
        @Override
        public void run() {


            if (DEBUG) Log.i(TAG, imcMessage.toString());

            if (imm.getListener() == null) {
                removeListeners();
                gpsAddListenerCounter = 0;
                return;
            }

            if (gpsAddListenerCounter == 0 && announcePosition() == true && announceHeading() == true)
                addListerners();
            else if (gpsAddListenerCounter == 2)
                removeListeners();

            gpsAddListenerCounter = (gpsAddListenerCounter + 1) % 4;

            updateEstimatedState();

            sendEstimatedStateToCCUs(PORT, imcMessage);

        }
    };

    public void removeListeners() {
        gpsManager.removeListener(locListener);
        if (sensorsAvailable == true) {
            sensorManager.unregisterListener(aceMagnListener, sensorMagnetic);
            sensorManager.unregisterListener(aceMagnListener, sensorAccelerometer);
        }
    }

    public void addListerners() {

        if (announcePosition() == true) {
            gpsManager.addListener(locListener);
        }

        if (announceHeading() == true) {
            if (sensorsAvailable == true) {
                sensorManager.registerListener(aceMagnListener, sensorMagnetic, SensorManager.SENSOR_DELAY_GAME);
                sensorManager.registerListener(aceMagnListener, sensorAccelerometer, SensorManager.SENSOR_DELAY_GAME);
            }

        }
    }

    public void sendEstimatedStateToCCUs(int port, IMCMessage imcMessage) {

        //check what addresses to send to
        List<Sys> sysList = getSystemList();

        for (Sys sys : sysList) {
            if (sys.getType().equals("CCU"))
                imm.send(sys.getAddress(), port, imcMessage);//check which port to send to
        }
    }

    public void start() {
        initializeSensors();
        generateIMCMessage();// Generate message only on start
        timer.start();

        addListerners();
    }

    public void stop() {
        timer.stop();

        removeListeners();
    }

    private LocationChangeListener locListener = new LocationChangeListener() {
        @Override
        public void onLocationChange(Location location) {
            updateLocation();
        }
    };

    //Listener for Accelerometer/Magnetometer
    private SensorEventListener aceMagnListener = new SensorEventListener() {
        float[] mGravity;
        float[] mGeomagnetic;

        @Override
        public void onSensorChanged(SensorEvent event) {

            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mGravity = event.values;
            }
            if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                mGeomagnetic = event.values;
            }

            if (mGravity != null && mGeomagnetic != null) {
                float R[] = new float[9];
                float I[] = new float[9];
                boolean success = SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic);
                if (success) {
                    float orientation[] = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    // orientation contains: azimuth [0], pitch [1] and roll [2] range: 0 - 360 degrees

                    int screenOrient = Accu.getmContext().getResources().getConfiguration().orientation;
                    Display display = ((WindowManager) Accu.getmContext().getSystemService(Activity.WINDOW_SERVICE)).getDefaultDisplay();
                    int screenRotation = display.getRotation();
                    Log.i("screenOrient",String.valueOf(screenOrient));//1 portrait 2landscape
                    Log.i("screenRotation",String.valueOf(screenRotation));//1 left 3 right 0 portrait


                    if ( azimuth != -1 && pitch != -1 && roll != -1 && outlier == false){//not first value
                        //exclude outliers
                        outlier = true;
                        //azimuth
                        if (orientation[0] < (azimuthArray[index]-0.35))
                            return;
                        if (orientation[0] > (azimuthArray[index]+0.35))
                            return;
                        //pitch
                        if (orientation[1] > (pitchArray[index]+0.35))
                            return;
                        if (orientation[1] < (pitchArray[index]-0.35))
                            return;
                        //roll
                        if (orientation[2] < (rollArray[index]-0.35))
                            return;
                        if (orientation[2] > (rollArray[index]+0.35))
                            return;
                        outlier = false;
                    }
                    else
                        outlier = false;

                    rollArray[index] = orientation[2];
                    pitchArray[index] = orientation[1];
                    azimuthArray[index] = orientation[0];

                    index++;
                    if (index == N){
                        initBoolean = true;
                        index = 0;
                    }

                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    public boolean announcePosition(){
        return Accu.getInstance().getPrefs().getBoolean("announcePosition", true);
    }

    public boolean announceHeading(){
        return Accu.getInstance().getPrefs().getBoolean("announceHeading", true);
    }

    private void updateEstimatedState() {

        if (announcePosition() == true)
            updateLocation();

        if (announceHeading() == false)
            return;

        if (sensorsAvailable == true)
            updateHeadingAceMagn();
        else
            updateHeadingGPSbearing();
    }

    private void updateLocation() {
        currentLocation = Accu.getInstance().getGpsManager().getCurrentLocation();
        double lat = currentLocation.getLatitude();
        double lon = currentLocation.getLongitude();
        double height = currentLocation.getAltitude();

        imcMessage.setValue("lat", Math.toRadians(lat));
        imcMessage.setValue("lon", Math.toRadians(lon));
        imcMessage.setValue("height", height);
    }

    private void updateHeadingAceMagn() {
        //put values in IMCMessage, global values updated with listener
        calcAvg();
        imcMessage.setValue("phi", roll);
        imcMessage.setValue("theta", pitch);
        imcMessage.setValue("psi", azimuth);
    }

    private void calcAvg(){

        int n = N;
        if (initBoolean == false) {
			n = index;
        }
        float roll = 0;
        float pitch = 0;
        float azimuth = 0;
        
        
        for (int i = 0;i < n;i++){
            roll += rollArray[i];
            pitch += pitchArray[i];
            azimuth += azimuthArray[i];
        }

        roll=roll/n;
        pitch = pitch/n;
        azimuth = azimuth/n;

        this.roll=roll;
        this.pitch=pitch;
        this.azimuth=azimuth;
        
        imcMessage.setValue("phi", roll);
        imcMessage.setValue("theta", pitch);
        imcMessage.setValue("psi", azimuth);
    }

    private void updateHeadingGPSbearing() {

        if (gpsManager == null) {
            Log.e("GPS Heading", "gpsManager==null");
            return;
        }
        if (currentLocation != null & currentLocation.hasBearing() ){
            gpsBearingValue = Math.toRadians(MUtil.nomalizeAngleDegrees180(currentLocation.getBearing()));

            imcMessage.setValue("psi",gpsBearingValue);
        }else{
            Log.e("GPS Heading","currentLocation==null || Bearing not available");
        }

    }

    public void generateIMCMessage() {
        String ipfull = MUtil.getLocalIpAddress();
        String[] ip = new String[4];

        if (ipfull == null) // Means no connection
        {
            ip[0] = ip[1] = ip[2] = ip[3] = "0";
        } else {
            ip = ipfull.split("\\.");
        }

        String sysName = "accu-" + ip[2] + ip[3];

        try {
            imcMessage = IMCDefinition.getInstance().create("EstimatedState", "sys_name", sysName);//more than name necessary?

            updateEstimatedState();

            imcMessage.getHeader().setValue("src", imm.getLocalId());
            imcMessage.getHeader().setValue("src_ent", 255);//check this!
            imcMessage.getHeader().setValue("dst_ent", 255);

        } catch (Exception e) {
            Log.e(e.getClass().getName(), e.getMessage());
            e.printStackTrace();
        }
    }

}