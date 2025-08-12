package com.myname.focuslock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.lang.Math;

import org.micromanager.Studio;
import mmcorej.CMMCore;

public class FocusTask {
	private Studio studio;
	private CMMCore core;
	private CameraPollingTask camera;
	
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private String stage;
    private double calSlope = 0;
    private double refMean = 0;
    private double mean = 0;
    private boolean start = false;
    private Consumer<Double> onErrorUpdate;

    // PID constants
    private double Kp = 0;
    private double Ki = 0;
    private double Kd = 0;
    
    // PID state
    private double integral = 0;
    private double previousError = 0;
    private long previousTime = 0;
    
    
    public FocusTask(Studio studio, CameraPollingTask camera) {
    	this.studio = studio;
    	this.core = studio.core();
    	this.camera = camera;    	
    	
    	try {
    		this.stage = core.getFocusDevice();
    	} catch(Exception e) {
    		studio.logs().showError("Could not find focus stage: " + e.toString());
    	}
    }
    
    public void setOnErrorUpdate(Consumer<Double> callback) {
        this.onErrorUpdate = callback;
    }
    
    public void setProportionalGain(double Kp) {
    	this.Kp = Kp;
    }
    
    public void setIntegratoinGain(double Ki) {
    	this.Ki = Ki;
    }
    
    public void setDifferentialGain(double Kd) {
    	this.Kd = Kd;
    }
    
    public double[] startFocus(double slopeCal) {
    	calSlope = slopeCal;
    	double[] result = new double[3];
    	try {
    		short[] data = camera.snapOnce();
            result = new GaussianFitter(data).fit();
            refMean = result[1];
    	} catch(Exception e) {
    		studio.logs().showError("Image acquisition failed: " + e.toString());
    	}
    	start = true;
    	scheduler.execute(this::focussing);
    	return result;
    }
    
    private void focussing() {
    	double startZ;
    	double deltaZ;
    	
    	if (!start) {
    		return;
    	}
    	
    	try {
    		short[] data = camera.snapOnce();
            double[] result = new GaussianFitter(data).fit();
            mean = result[1];
            
    	} catch(Exception e) {
    		studio.logs().showError("Image acquisition failed: " + e.toString());
    		return;
    	}
    	
    	double error = mean - refMean;
    	onErrorUpdate.accept(error * calSlope);
    	long currentTime = System.currentTimeMillis();
    	double deltaTime = (previousTime == 0) ? 1.0 : (currentTime - previousTime) / 1000.0; // seconds
    	previousTime = currentTime;
    	
    	// PID calculation
    	integral += error * deltaTime;
    	double derivative = (deltaTime > 0) ? (error - previousError) / deltaTime : 0;
    	previousError = error;
    	
    	
    	deltaZ = (Kp * error) + (Ki * integral) + (Kd * derivative);
    	
		deltaZ = deltaZ * calSlope;
		
    	try {
    		startZ = core.getPosition(stage);
    	} catch (Exception e) {
    		studio.logs().showError("Failed to get stage position: " + e.getMessage());
    		return;
    	}
    	
    	double newZ = startZ + deltaZ;
    	
    	try {
    		core.setPosition(stage, newZ);
    		studio.logs().logMessage(
			    String.format(
			        "PID Debug | error=%.6f, deltaTime=%.4fs, integral=%.6f, derivative=%.6f, " +
			        "Kp=%.4f, Ki=%.4f, Kd=%.4f, " +
			        "OldZ=%.6f, deltaZ=%.6f, NewZ=%.6f",
			        error, deltaTime, integral, derivative,
			        Kp, Ki, Kd,
			        startZ, deltaZ, newZ
			    )
			);

    		Thread.sleep(1000); // Give hardware a moment to settle
    	} catch (Exception e) {
    		studio.logs().showError("Stage movement failed: " + e.getMessage());
    		return;
    	}
	    scheduler.schedule(this::focussing, 1000, java.util.concurrent.TimeUnit.MILLISECONDS); // 1.0s between steps
    }
    
    public void stopFocus() {
    	start = false;
    }

}
