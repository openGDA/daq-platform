package uk.ac.diamond.daq.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import gda.beamline.health.BeamlineHealthMonitor;
import gda.beamline.health.BeamlineHealthResult;
import gda.factory.Finder;
import gda.jython.GDAJythonClassLoader;
import gda.jython.ITerminalPrinter;
import gda.jython.InterfaceProvider;
import gda.util.ObjectServer;
import gda.util.SpringObjectServer;
import gda.util.Version;
import uk.ac.diamond.daq.concurrent.Async;
import uk.ac.diamond.daq.server.configuration.IGDAConfigurationService;
import uk.ac.diamond.daq.server.configuration.commands.ObjectServerCommand;
import uk.ac.diamond.daq.services.PropertyService;

/**
 * This class controls all aspects of the application's execution
 */
public class GDAServerApplication implements IApplication {

	private static final Logger logger = LoggerFactory.getLogger(GDAServerApplication.class);

	private static IGDAConfigurationService configurationService;

	private final CountDownLatch shutdownLatch = new CountDownLatch(1);
	private final Map<String, ObjectServer> objectServers = new HashMap<>();

	private ServerSocket statusPort;
	private BeamlineHealthMonitor beamlineHealthMonitor;

	/**
	 * Application start method invoked when it is launched. Loads the required configuration via  the external OSGI configuration service.
	 * Starts the 4 (or more) servers and then execution waits for the shutdown hook trigger, multiple object Servers may be started.
	 * If the application is run from the command line or eclipse it only requires the config file (-f) and profile (-p) args
	 * to be supplied; in this case it will default to the example-config values for all other things that the scripts pass in.
	 */
	public Object start(IApplicationContext context) throws Exception
	{
		// Log some info for debugging - this only goes to stdout as logging is not setup yet
		logger.info("Starting GDA server application {}", Version.getRelease());
		logger.info("Java version: {}", System.getProperty("java.version"));
		logger.info("JVM arguments: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());

		ApplicationEnvironment.initialize();
		configurationService.loadConfiguration();

		try {

			for (ObjectServerCommand command : configurationService.getObjectServerCommands()) {
				ObjectServer server = command.execute();
				objectServers.put(command.getProfile(), server);
				logger.info("{} object server started", command.getProfile());
				// Also make it obvious in the IDE Console.
				final String hline_80char = "================================================================================";
				System.out.println(hline_80char + "\n" + command.getProfile() + " object server started\n" + hline_80char);
			}
		} catch (Exception ex) {
			logger.error("GDA server startup failure", ex);
			ex.printStackTrace();
			clearUp();
		}

		beamlineHealthMonitor = Finder.findOptionalSingleton(BeamlineHealthMonitor.class).orElse(null);

		if (!objectServers.isEmpty()) {
			// Once we are here all the servers have started
			openStatusPort();
			awaitShutdown();
			logger.info("GDA server application ended");
		}
		return IApplication.EXIT_OK;
	}

	/**
	 * This opens a port on the server. The presence of this open port can be used
	 * by the client to ensure the server is running. It would be possible to extend
	 * this to offer information such as server uptime, connected clients etc.
	 *
	 * @Since GDA 9.7
	 */
	private void openStatusPort() {
		// TODO Here use the PropertyService for now but once backed by sys properties will not be needed.
		final int serverPort = getPropertyService().getAsInt("gda.server.statusPort", 19999);
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

		if (objectServers.size() > 0) {

			// Shutdown using the SpringObjectServer shutdown
			// TODO: Refactor command class so we can lose the cast
			for (Map.Entry<String, ObjectServer> entry : objectServers.entrySet()) {
				((SpringObjectServer)entry.getValue()).shutdown();
			}
			objectServers.clear();
		}

		GDAJythonClassLoader.closeJarClassLoaders();
		ApplicationEnvironment.release();
		shutdownLatch.countDown();
	}

	/**
	 * Make provision for graceful shutdown by adding a shutdown listener and then waiting on the
	 * {@link #shutdownLatch}. When shutdown is triggered {@link #stop()} is called which clears
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
						if (inputLine.equalsIgnoreCase(BeamlineHealthResult.COMMAND) && beamlineHealthMonitor != null) {
							out.println(new ObjectMapper().writeValueAsString(getBeamlineState()));
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
