package uk.ac.diamond.daq.server;

import gda.jython.GDAJythonClassLoader;
import gda.util.ObjectServer;
import gda.util.SpringObjectServer;
import gda.util.Version;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import uk.ac.diamond.daq.server.configuration.IGDAConfigurationService;
import uk.ac.diamond.daq.server.configuration.IGDAConfigurationService.ServerType;
import uk.ac.diamond.daq.server.configuration.commands.ObjectServerCommand;
import static uk.ac.diamond.daq.server.configuration.IGDAConfigurationService.ServerType.*;

/**
 * This class controls all aspects of the application's execution
 */
public class GDAServerApplication implements IApplication {

	private static final Logger logger = LoggerFactory.getLogger(GDAServerApplication.class);

	private static IGDAConfigurationService configurationService;
	protected static int SERVER_WAIT_MILLIS = 4000;

	private final Map<ServerType, Process> processes = new HashMap<>();
	private final CountDownLatch shutdownLatch = new CountDownLatch(1);
	private final Map<String, ObjectServer> objectServers = new HashMap<String, ObjectServer>();

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
			try {
				processes.put(LOG, configurationService.getLogServerCommand().execute());
				logger.info("Log server starting");
				processes.put(NAME, configurationService.getNameServerCommand().execute());
				logger.info("Name server starting");
				processes.put(EVENT, configurationService.getEventServerCommand().execute());
				logger.info("Channel/Event server starting");
				// TODO: find some kind of interactive "channel server is ready" check otherwise you get a corba exception
				Thread.sleep(SERVER_WAIT_MILLIS);
			}
			catch (Exception subEx) {
				String[] failedServer = {"Log", "Name", "Event"};
				logger.error("Unable to start {} server, GDA server shutting down", failedServer[processes.size()]);
				throw subEx;
			}

			for (ObjectServerCommand command : configurationService.getObjectServerCommands()) {
				ObjectServer server = command.execute();
				if (server == null) {
					logger.error("Unable to start {} Object server, GDA server shutting down", command.getProfile());
					stop();
					break;
				}
				objectServers.put(command.getProfile(), server);
				logger.info("{} object server started", command.getProfile());
				// Also make it obvious in the IDE Console.
				final String hline_80char = "================================================================================";
				System.out.println(hline_80char + "\n" + command.getProfile() + " object server started\n" + hline_80char);
			}
		}
		catch (Exception ex) {
			logger.error("GDA server startup failure", ex);
			ex.printStackTrace();
			stop();
		}
		if (!objectServers.isEmpty()) {
			awaitShutdown();
			logger.info("GDA server application ended");
		}
		return IApplication.EXIT_OK;
	}

	/**
	 * Clears up all the resources created by start and then clears the {@link #shutdownLatch}
	 * allowing the {@link #start(IApplicationContext)} to complete.
	 */
	public void stop() {
		logger.info("GDA application stopping");
		if (objectServers.size() > 0) {

			// Shutdown using the SpringObjectServer shutdown which waits for Corba unbind
			// TODO: Refactor command class so we can lose the cast
			for (Map.Entry<String, ObjectServer> entry : objectServers.entrySet()) {
				((SpringObjectServer)entry.getValue()).shutdown();
			}
			objectServers.clear();
		}

		for (Map.Entry<ServerType, Process> process : processes.entrySet()) {
			logger.info("{} Server shutting down", process.getKey());
			try {
				if (process.getKey() == ServerType.LOG) {
					((LoggerContext)LoggerFactory.getILoggerFactory()).stop();
				}
				if (!process.getValue().destroyForcibly().waitFor(1000, TimeUnit.MILLISECONDS)) {
					logger.error("Shutdown of {} server timed out, check for orphaned process", process.getKey());
				}
			} catch (InterruptedException e) {
				logger.error("Shutdown of {} server interrupted, check for orphaned process", process.getKey(), e);
				e.printStackTrace();
				Thread.currentThread().interrupt();
			}
		}
		processes.clear();
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
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			@Override
			public void run() {
				stop();
			}
		}));
		shutdownLatch.await();
	}

	public static void setConfigurationService(IGDAConfigurationService service) {
		configurationService = service;
	}
}
