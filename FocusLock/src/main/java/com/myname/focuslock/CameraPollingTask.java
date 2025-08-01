package com.myname.focuslock;

import org.micromanager.Studio;
import mmcorej.CMMCore;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A repeating task that grabs a snapshot from a custom camera every 10 seconds.
 */
public class CameraPollingTask {
    private final Studio studio;
    private final CMMCore privateCore ;
    private final String cameraName = "gFocus Light Sensor";
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();    
    public volatile short[] pixelData;
    private Consumer<short[]> onImageUpdate;
    private final Object coreLock = new Object();
    private final Object schedulerLock = new Object();

//    private boolean isCameraAttached = false;
    private int average;
    private double exposure;
    
    public CameraPollingTask(Studio studio, CMMCore privateCore) {
        this.studio = studio;
        this.privateCore  = privateCore;

        try {
        	privateCore.loadSystemConfiguration("C:/Program Files/Micro-Manager-2.0/gFocus/gFocus.cfg");
        	privateCore.setCameraDevice(cameraName);
        	this.setAverage(1);
        	this.setExposure(1.0);
            studio.logs().logMessage("Private core for light sensor initialized");
        } catch (Exception e) {
            studio.logs().showError("Failed to initialize private core for light sensor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
//    private void attachCamera() {
//        if (!isCameraAttached) {
//            try {
//                privateCore.setCameraDevice(cameraName);
//                isCameraAttached = true;
//            } catch (Exception e) {
//            	studio.logs().logMessage("Failed to attach camera: " + e.getMessage());
//            }
//        }
//    }
//
//    private void detachCamera() {
//        if (isCameraAttached) {
//            try {
//                privateCore.setCameraDevice("");  // Detach by setting to empty string
//                isCameraAttached = false;
//            } catch (Exception e) {
//            	studio.logs().logMessage("Failed to detach camera: " + e.getMessage());
//            }
//        }
//    }
    
    public void setExposure(double expo) {
        try {
        	synchronized (coreLock) {
	            privateCore.setProperty(cameraName, "Time [ms]", expo);
	            studio.logs().logMessage("Set exposure to: " + expo);
	            exposure = expo;
        	}
        } catch (Exception e) {
            studio.logs().showError("Failed to set exposure: " + e.getMessage());
        }
    }

    public void setAverage(int avg) {
        try {
        	synchronized (coreLock) {
	            privateCore.setProperty(cameraName, "Average #", avg);        	
	            studio.logs().logMessage("Set averaging to: " + avg);
	            average = avg;
        	}
        } catch (Exception e) {
            studio.logs().showError("Failed to set averaging: " + e.getMessage());
        }
    }
    
    public void setOnImageUpdate(Consumer<short[]> callback) {
        this.onImageUpdate = callback;
    }

    public void start() {
        synchronized (schedulerLock) {
            if (scheduler.isShutdown() || scheduler.isTerminated()) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }

            scheduler.scheduleWithFixedDelay(() -> {
                final int maxRetries = 10;

                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        synchronized (coreLock) {
                            privateCore.snapImage();
                            Object img = privateCore.getImage();

                            if (img instanceof byte[]) {
                                byte[] raw = (byte[]) img;
                                int numPixels = raw.length / 2;
                                pixelData = new short[numPixels];
                                for (int i = 0; i < numPixels; i++) {
                                    int low = raw[2 * i] & 0xFF;
                                    int high = raw[2 * i + 1] & 0xFF;
                                    pixelData[i] = (short) ((high << 8) | low);
                                }
                            } else if (img instanceof short[]) {
                            	pixelData = (short[]) img;
                            } else {
                                studio.logs().showError("Unsupported image type: " + img.getClass().getSimpleName());
                                return;
                            }

                            if (onImageUpdate != null) {
                                onImageUpdate.accept(pixelData);
                            }

                            return;  // Success
                        }
                    } catch (Exception e) {
                        if (attempt == maxRetries) {
                            try {
                                synchronized (coreLock) {
                                    privateCore.reset();
                                    privateCore.loadSystemConfiguration("C:/Program Files/Micro-Manager-2.0/gFocus/gFocus.cfg");
                                    setAverage(average);
                                    setExposure(exposure);
                                }
                                studio.logs().logMessage("Max retries reached. Core reset attempted.");
                            } catch (Exception e1) {
                                studio.logs().logMessage("Reset failed: " + e1.toString());
                            }
                        }
                        // No delay between retries
                    }
                }
            }, 0, 100, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }
    
    public short[] snapOnce() {
        final int maxRetries = 10;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            synchronized (coreLock) {
                try {
                    privateCore.snapImage();
                    Object img = privateCore.getImage();
                    
                    if (img instanceof byte[]) {
                        byte[] raw = (byte[]) img;
                        int numPixels = raw.length / 2;
                        short[] result = new short[numPixels];
                        for (int i = 0; i < numPixels; i++) {
                            int low = raw[2 * i] & 0xFF;
                            int high = raw[2 * i + 1] & 0xFF;
                            result[i] = (short) ((high << 8) | low);
                        }
                        return result;
                    } else if (img instanceof short[]) {
                        return (short[]) img;
                    } else {
                        studio.logs().showError("Unsupported image type: " + img.getClass().getSimpleName());
                        return null;
                    }

                } catch (Exception e) {
                    if (attempt == maxRetries) {
                        studio.logs().showError("snapOnce failed after " + maxRetries + " attempts: " + e.getMessage());
                    }
                    // retry
                }
            }
        }

        return null;
    }
}

