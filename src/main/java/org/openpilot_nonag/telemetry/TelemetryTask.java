/**
 ******************************************************************************
 * @file       TelemetryTask.java
 * @author     The OpenPilot Team, http://www.openpilot_nonag.org Copyright (C) 2012.
 * @brief      UAVTalk over TCP.
 * @see        The GNU Public License (GPL) Version 3
 *
 *****************************************************************************/
/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package org.openpilot_nonag.telemetry;



import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.openpilot_nonag.uavtalk.Telemetry;
import org.openpilot_nonag.uavtalk.TelemetryMonitor;
import org.openpilot_nonag.uavtalk.UAVObjectManager;
import org.openpilot_nonag.uavtalk.UAVTalk;
import org.openpilot_nonag.uavtalk.uavobjects.TelemObjectsInitialize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Observable;
import java.util.Observer;

public abstract class TelemetryTask implements Runnable {

    static final Logger logger = LogManager.getLogger(TelemetryTask.class.getName());
    
	// Logging settings
	private final String TAG = TelemetryTask.class.getSimpleName();
	public static final int LOGLEVEL = 2;
	public static final boolean WARN = LOGLEVEL > 1;
	public static final boolean DEBUG = LOGLEVEL > 0;

	/*
	 * This is a self contained runnable that will establish (if possible)
	 * a telemetry connection and provides a listener interface to be
	 * notified of a set of events
	 *
	 * 1. attempt to establish connection
	 * 2. callback when it succeeds (or fails) which notifies listener
	 * 3. once physical connection is established instantiate uavtalk / objectmanager
	 * 4. notify listener they are connected
	 * 5. detect physical link failure and notify listener about that
	 * 6. provide "close link" method
	 *
	 * There are essentially four tasks that need to occur here
	 * 1. Transfer data from the outputStream to the physical link (some protocols do this automatically)
	 * 2. Transfer data from the physical link to the inputStream (again some protocols do this automatically)
	 * 3. Transfer data from the inputStream to UAVTalk (uavTalk.processInputByte)
	 * 4. Transfer data from objects via UAVTalk to output stream (occurs from Telemetry object)
	 */

	//! Private variables
	//protected Handler handler;

	//! Handle to the parent service
	protected final OPTelemetryService telemService;

	//! The object manager that will be used for this telemetry task
	protected UAVObjectManager objMngr;

	//! The UAVTalk connected to the below streams
	private UAVTalk uavTalk;

	//! The input stream for the telemetry channel
	protected InputStream inStream;

	//! The output stream for the telemetry channel
	protected OutputStream outStream;

	//! The telemetry object which takes care of higher level transactions
	private Telemetry tel;

	//! The telemetry monitor which takes care of high level connects / disconnects
	private TelemetryMonitor mon;

	//! Thread to process the input stream
	Thread inputProcessThread;

	//! Flag to indicate a shut down was requested.  Derived classes should take care to respect this.
	boolean shutdown;

	//! Indicate a physical connection is established
	private boolean connected;

	TelemetryTask(OPTelemetryService s) {
		telemService = s;
		shutdown = false;
		connected = false;
	}

	/**
	 * Attempt a connection.  This method may return before the results are
	 * known.
	 * @return False if the attempt failed and no connection will be established
	 * @return True if the attempt succeeded but does not guarantee success
	 */
	abstract boolean attemptConnection();

	/**
	 * Called when a physical channel is opened
	 *
	 * When this method is called the derived class must have
	 * created a valid inStream and outStream
	 */
	boolean attemptSucceeded() {
		// Create a new object manager and register all objects
		// in the future the particular register method should
		// be dependent on what is connected (e.g. board and
		// version number).
		objMngr = new UAVObjectManager();
		TelemObjectsInitialize.register(objMngr);

		// Create the required telemetry objects attached to this
		// data stream
		uavTalk = new UAVTalk(inStream, outStream, objMngr);
		tel = new Telemetry(uavTalk, objMngr);
		mon = new TelemetryMonitor(objMngr, tel, telemService);

		// Create an observer to notify system of connection
		mon.addObserver(connectionObserver);

		// Create a new thread that processes the input bytes
		startInputProcessing();

		connected = true;
		return connected;
	}

	boolean attemptedFailed() {
		connected = false;
		return connected;
	}

	void disconnect() {
		// Make the default input procesing loop stop
		shutdown = true;

		// Shut down all the attached
		if (mon != null) {
			mon.stopMonitor();
			mon.deleteObserver(connectionObserver);
			mon = null;
		}
		if (tel != null) {
			tel.stopTelemetry();
			tel = null;
		}

		// Stop the master telemetry thread
		// Check handler is not null: if we attempt to disconnect before
		// the connect process has completed, handler may be null.
//		if(handler != null){
//			handler.post(new Runnable() {
//				@Override
//				public void run() {
//					Looper.myLooper().quit();
//				}
//			});
//		}

		if (inputProcessThread != null) {
			inputProcessThread.interrupt();
			try {
				inputProcessThread.join();
			} catch (InterruptedException e) {
			}
		}

		// TODO: Make sure the input and output stream is closed

		// TODO: Make sure any threads for input and output are closed
	}

	/**
	 * Default implementation for processing input stream
	 * which creates a new thread that keeps attempting
	 * to read from the input stream.
	 */
	private void startInputProcessing() {
		inputProcessThread = new Thread(new processUavTalk(), "Process UAV talk");
		inputProcessThread.start();
	}

	//! Runnable to process input stream
	class processUavTalk implements Runnable {
		@Override
		public void run() {
			logger.debug("Entering UAVTalk processing loop");
			while (!shutdown) {
				try {
					if( !uavTalk.processInputStream() ) {
                        System.exit(0);
                        break;
                    }
				} catch (IOException e) {
					e.printStackTrace();
					//telemService.toastMessage("Telemetry input stream interrupted");
					break;
				}
			}
			logger.debug("UAVTalk processing loop finished");

		}
	};

	@Override
	public void run() {
		try {

			//Looper.prepare();
			//handler = new Handler();

			logger.debug("Attempting connection");
			if( attemptConnection() == false )
				return; // Attempt failed

			//Looper.loop();

			logger.debug("TelemetryTask runnable finished");

		} catch (Throwable t) {
			logger.error("halted due to an error", t);
		}

		//telemService.toastMessage("Telemetry Thread finished");
        logger.info("Telemetry Thread finished");
	}

	private final Observer connectionObserver = new Observer() {
		@Override
		public void update(Observable arg0, Object arg1) {
			logger.debug("Mon updated. Connected: " + mon.getConnected() + " objects updated: " + mon.getObjectsUpdated());
			if(mon.getConnected()) {
				//Intent intent = new Intent();
				//intent.setAction(OPTelemetryService.INTENT_ACTION_CONNECTED);
				//telemService.sendBroadcast(intent,null);
                logger.info("OPTelemetryService.INTENT_ACTION_CONNECTED");
			}
		}
	};

	/**** General accessors ****/

	public boolean getConnected() {
		return connected;
	}

	public UAVTalk getUavtalk() {
		return uavTalk;
	}

//	public OPTelemetryService.TelemTask getTelemTaskIface() {
//		return new OPTelemetryService.TelemTask() {
//			@Override
//			public UAVObjectManager getObjectManager() {
//				return objMngr;
//			}
//		};
//	}

}
