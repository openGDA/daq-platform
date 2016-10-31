package uk.ac.diamond.daq.server;

import gda.util.ObjectServer;
import gda.util.SpringObjectServer;
import gda.util.Version;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
		ApplicationEnvironment.initialize();
		configurationService.loadConfiguration();

		logger.info(String.format("Starting GDA application %s", Version.getRelease()));

		try {
			processes.put(LOG, configurationService.getLogServerCommand().execute());
			logger.info("Log server starting");
			processes.put(NAME, configurationService.getNameServerCommand().execute());
			logger.info("Name server starting");
			processes.put(EVENT, configurationService.getEventServerCommand().execute());
			logger.info("Channel/Event server starting");
			// TODO: find some kind of interactive "channel server is ready" check otherwise you get a corba exception
			Thread.sleep(SERVER_WAIT_MILLIS);

			for (ObjectServerCommand command : configurationService.getObjectServerCommands()) {
				ObjectServer server = command.execute();
				if (server == null) {
					logger.info("Unable to start " + command.getProfile() + " Object server, GDA shutting down");
					stop();
					break;
				}
				objectServers.put(command.getProfile(), server);
				logger.info(command.getProfile() + " object server started");
			}
		}
		catch (IOException ioEx) {
			String[] failedServer = {"Log", "Name", "Event"};
			logger.info("Unable to start " + failedServer[processes.size()] + " server, GDA shutting down");
			ioEx.printStackTrace();
			stop();
		}
		if (!objectServers.isEmpty()) {
			awaitShutdown();
			logger.info("GDA application ended");
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
				process.getValue().destroyForcibly().waitFor(20, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				logger.error("Shutdown of {} interrupted, check for orphaned process", process.getKey());
				e.printStackTrace();
				Thread.currentThread().interrupt();
			}
		}
		processes.clear();
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
