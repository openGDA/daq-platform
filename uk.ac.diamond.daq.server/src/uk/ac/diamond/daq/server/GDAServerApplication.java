/*-
 * Copyright © 2016 Diamond Light Source Ltd.
 *
 * This file is part of GDA.
 *
 * GDA is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 3 as published by the Free
 * Software Foundation.
 *
 * GDA is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along
 * with GDA. If not, see <http://www.gnu.org/licenses/>.
 */

package uk.ac.diamond.daq.server;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import gda.beamline.health.BeamlineHealthMonitor;
import gda.beamline.health.BeamlineHealthResult;
import gda.beamline.health.BeamlineHealthState;
import gda.factory.Finder;
import gda.jython.ITerminalPrinter;
import gda.jython.InterfaceProvider;
import gda.util.Version;
import gda.util.logging.LogbackUtils;
import uk.ac.diamond.daq.api.messaging.MessagingService;
import uk.ac.diamond.daq.concurrent.Async;
import uk.ac.diamond.daq.server.configuration.IGDAConfigurationService;
import uk.ac.diamond.daq.server.configuration.commands.ServerCommand;
import uk.ac.diamond.daq.services.PropertyService;
import uk.ac.gda.core.GDACoreActivator;

/**
 * This class controls all aspects of the application's execution
 */
public class GDAServerApplication implements IApplication {

	private static final Logger logger = LoggerFactory.getLogger(GDAServerApplication.class);

	private static IGDAConfigurationService configurationService;

	private final CountDownLatch shutdownLatch = new CountDownLatch(1);

	private ServerSocket statusPort;
	private BeamlineHealthMonitor beamlineHealthMonitor;

	/**
	 * Application start method invoked when it is launched. Loads the required configuration via  the external OSGI configuration service.
	 * Starts the 4 (or more) servers and then execution waits for the shutdown hook trigger, multiple object Servers may be started.
	 * If the application is run from the command line or eclipse it only requires the config file (-f) and profile (-p) args
	 * to be supplied; in this case it will default to the example-config values for all other things that the scripts pass in.
	 */
	@Override
	public Object start(IApplicationContext context) throws Exception {
		LogbackUtils.configureLoggingForServerProcess("server", getPropertyService().get(LogbackUtils.GDA_SERVER_LOGGING_XML));
		// DAQ-2994 Ensure that the server's Logback executor is operating sufficiently
		Async.scheduleAtFixedRate(LogbackUtils::monitorAndAdjustLogbackExecutor, 1, 10, SECONDS, "monitor-logback");

		logger.info("Starting GDA server application {}", Version.getRelease());
		logger.info("Java version: {}", System.getProperty("java.version"));
		logger.info("JVM arguments: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());

		ApplicationEnvironment.initialize();
		configurationService.loadConfiguration();

		try {
			checkActiveMq();
			for (ServerCommand command : configurationService.getObjectServerCommands()) {
				command.execute();
				logger.info("Server started");
			}
			// Also make it obvious in the IDE Console.
			System.out.println("================================================================================");
			System.out.println("Server started");
			System.out.println("================================================================================");
			openStatusPort();
			awaitShutdown();
			logger.info("GDA server application ended");
		} catch (Exception ex) {
			logger.error("GDA server startup failure", ex);
			ex.printStackTrace();
			clearUp();
			writeStartupErrorFile(ex);
		}

		return IApplication.EXIT_OK;
	}

	private void checkActiveMq() {
		if (GDACoreActivator.getService(MessagingService.class).isEmpty()) {
			throw new IllegalStateException("No MessagingService is available - is ActiveMQ running?");
		}
	}

	private void writeStartupErrorFile(Exception ex) {
		String startupFile = System.getenv().getOrDefault("OBJECT_SERVER_STARTUP_FILE", "/tmp/object_server_startup_server_main");
		try {
			Files.write(Paths.get(startupFile), ex.getMessage().getBytes());
			logger.info("Wrote error file to {}", startupFile);
		} catch (IOException e) {
			logger.error("Failed to write startup file to {}", startupFile, e);
		}
	}

	/**
	 * This opens a port on the server. The presence of this open port can be used
	 * by the client to ensure the server is running. It would be possible to extend
	 * this to offer information such as server uptime, connected clients etc.
	 *
	 * @Since GDA 9.7
	 */
	private void openStatusPort() {
		beamlineHealthMonitor = Finder.findOptionalSingleton(BeamlineHealthMonitor.class).orElse(null);
		// TODO Here use the PropertyService for now but once backed by sys properties will not be needed.
		var serverPort = getPropertyService().getAsInt("gda.server.statusPort", 19999);
		try {
			statusPort = new ServerSocket(serverPort);
			Executors.newSingleThreadExecutor().execute(this::acceptStatusPortConnections);
			logger.debug("Opened status port on: {}", serverPort);
		} catch (IOException e) {
			logger.error("Opening status port on {} failed", serverPort, e);
		}
	}

	private void acceptStatusPortConnections() {
		while (!statusPort.isClosed()) {
			try {
				final Socket clientSocket = statusPort.accept();
				Async.execute(new ClientConnectionRunnable(clientSocket));
			} catch (IOException e) {
				handleIOException(e);
			}
		}
	}

	private void handleIOException(IOException e) {
		if (statusPort.isClosed()) {
			// Normal shutdown case. The port is closed while waiting to accept
			logger.debug("Stopping accepting status port connections");
		} else {
			logger.error("Exception occurred while accepting status port connection", e);
		}
	}

	private synchronized BeamlineHealthResult getBeamlineState() {
		return beamlineHealthMonitor.getState();
	}

	/**
	 * This closes the port on the server opened by the {@link #openStatusPort()}
	 * method
	 *
	 * @Since GDA 9.7
	 */
	private void closeStatusPort() {
		if (statusPort != null) { // Will be null if server fails to start fully
			try {
				statusPort.close();
				logger.debug("Closed status port");
			} catch (IOException e) {
				logger.error("Error closing status port", e);
			}
		}
	}

	/** Display message to any clients, then clear up resources */
	@Override
	public void stop() {
		logger.info("GDA application stopping");
		ITerminalPrinter printer = InterfaceProvider.getTerminalPrinter();
		// If server startup failed, this may not have been created
		if (printer != null) {
			// Notify via Jython console this is useful as dead clients will display the message
			printer.print("GDA server is shutting down");
		}
		clearUp();
	}

	/**
	 * Clears up all the resources created by start and then clears the {@link #shutdownLatch}
	 * allowing the {@link #start(IApplicationContext)} to complete.
	 */
	private void clearUp() {
		closeStatusPort();
		shutdownLatch.countDown();
	}

	/**
	 * Make provision for graceful shutdown by adding a shutdown listener and then waiting on a
	 * shutdown latch. When shutdown is triggered {@link #stop()} is called which clears
	 * all created objects/processes and then clears the latch.
	 *
	 * @throws InterruptedException if the shutdwonHook thread is interrupted
	 */
	protected void awaitShutdown() throws InterruptedException {
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
		shutdownLatch.await();
	}

	public static void setConfigurationService(IGDAConfigurationService service) {
		configurationService = service;
	}

	// TODO Once LocalProperties is backed by System properties remove this
	private PropertyService getPropertyService() {
		return GDAServerActivator.getService(PropertyService.class)
				.orElseThrow(() -> new IllegalStateException("No PropertyService is available"));
	}

	/**
	 * Runnable to handle a single client connection
	 */
	private class ClientConnectionRunnable implements Runnable {
		private final Socket clientSocket;

		public ClientConnectionRunnable(Socket clientSocket) {
			this.clientSocket = clientSocket;
		}

		@Override
		public void run() {
			try {
				try (final PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
						final BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
					String inputLine;
					while ((inputLine = in.readLine()) != null) {
						if (inputLine.equalsIgnoreCase(BeamlineHealthResult.COMMAND)) {
							// Beamline health status requested
							final BeamlineHealthResult beamlineHealthResult;
							if (beamlineHealthMonitor == null) {
								final String message = "No beamlineHealthMonitor found - server state cannot be determined";
								logger.warn(message);
								beamlineHealthResult = new BeamlineHealthResult(BeamlineHealthState.WARNING, message, Collections.emptyList());
							} else {
								beamlineHealthResult = getBeamlineState();
							}
							out.println(new ObjectMapper().writeValueAsString(beamlineHealthResult));
						} else {
							out.println(String.format("You sent: %s", inputLine));
						}
					}
					clientSocket.close();
				}
			} catch (IOException e) {
				handleIOException(e);
			}
		}
	}
}
