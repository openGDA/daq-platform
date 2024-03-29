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

package uk.ac.diamond.daq.server.configuration;

import static com.google.common.collect.ObjectArrays.concat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BiFunction;

import org.apache.commons.lang.StringUtils;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CaseFormat;

import gda.configuration.properties.LocalProperties;
import gda.util.logging.LoggingUtils;

/**
 * Default settings to be used when not running the product from one of the scripts
 * i.e. inside eclipse or by directly executing gda-server from the command line.
 * If the various normally set environment variables or system properties are present they will
 * be used, otherwise the default value defined here will be substituted. All enum values starting with APP_
 * represent the final composite values resulting from this process used to start the various servers.
 * Also includes methods to build the standard form of the various server start commands.
 *
 * As an enum, the instances of this become available in the static loading phase
 * and so should be available to all server startup routines. Because of this they can make use of pre-set
 * environment variables or command line Java System Properties (-Dxxx) but not LocalProperties (i.e. contents
 * of the xxx.java.properties file as these are no loaded until the individual servers initialise their logging.
 *
 * @author fri44821
 */

public enum ConfigurationDefaults {
	// Default values not subject to overriding
	EMPTY(""),
	DEPLOY_TYPE("1"),
	JAVA_OPTS("-Dgda.deploytype=1"),
	INI_FILE_INSTALL_AREA_PROPERTY("osgi.install.area"),
	OBJECT_SERVER_CLASS("gda.util.ObjectServer"),
	WORKSPACE_LOCATION(ResourcesPlugin.getWorkspace().getRoot().getLocation().toString()),	// read in from -data option

	// Default values with their corresponding override that are use to assemble later values
	BEAMLINE("example"),
	APP_BEAMLINE(getHierarchicalValueWithDefault(BEAMLINE)),

	BEAMLINE_CONFIG("/beamlineLayouts.cfg"),
	LAYOUT_DETAILS(loadLayoutLookup()),
	LAYOUT(LAYOUT_DETAILS.value.split(",")[1].toUpperCase()),

	PROFILE("main"),
	APP_PROFILES(getFromApplicationArgsUsingKeySetWithDefault(PROFILE, "-p")),

	GDA_WORKSPACE_PARENT(Paths.get(WORKSPACE_LOCATION.value).getParent().toString()),		// Default
	APP_PATHS_ROOT(getHierarchicalValueWithDefault(GDA_WORKSPACE_PARENT)),

	GDA_WORKSPACE_NAME("workspace"),
	APP_WORKSPACE_NAME(getHierarchicalValueWithDefault(GDA_WORKSPACE_NAME)),
	GDA_WORKSPACE_GIT_NAME(APP_WORKSPACE_NAME+"_git"),
	APP_WORKSPACE_GIT_NAME(getHierarchicalValueWithDefault(GDA_WORKSPACE_GIT_NAME)),

	GDA_INSTANCE_CONFIG_rel(combine(APP_WORKSPACE_GIT_NAME, LAYOUT_DETAILS.value.split(",")[0])),
	APP_INSTANCE_CONFIG_rel(getFromConfigPathOverrideWithDefault(getHierarchicalValueWithDefault(GDA_INSTANCE_CONFIG_rel))),

	APP_GROUP_NAME(manageMTMigration(APP_INSTANCE_CONFIG_rel.value)),
	GDA_GROUP_CONFIG_rel(APP_INSTANCE_CONFIG_rel + "/../" + APP_GROUP_NAME),
	APP_GROUP_CONFIG_rel(getHierarchicalValueWithDefault(GDA_GROUP_CONFIG_rel)),

	GDA_CORE_CONFIG_rel(combine(APP_WORKSPACE_GIT_NAME, "gda-core.git/core-config")),
	APP_CORE_CONFIG_rel(getHierarchicalValueWithDefault(GDA_CORE_CONFIG_rel)),

	GDA_FACILITY_CONFIG_rel(combine(APP_WORKSPACE_GIT_NAME, "gda-diamond.git/dls-config")),
	APP_FACILITY_CONFIG_rel(getHierarchicalValueWithDefault(GDA_FACILITY_CONFIG_rel)),

	GDA_MODE("dummy"),
	APP_MODE(getHierarchicalValueWithDefault(GDA_MODE)),
	APP_PROFILES_MODE(multiCaseJoiner(APP_MODE.value, APP_PROFILES, (one, two) -> combine(two, one))),

	GDA_VAR(null),														// If not specified set by properties file
	APP_VAR(getHierarchicalValueWithDefault(GDA_VAR)),

	GDA_LOGS_DIR(null),													// If not specified set by properties file
	APP_LOGS_DIR(getHierarchicalValueWithDefault(GDA_LOGS_DIR)),

	GDA_DATA(null),														// If not specified set by properties file
	GDA_DATADIR(null),													// Need both because of inconsistent historical naming
	APP_DATA(getHierarchicalValueWithDefault(GDA_DATA, GDA_DATADIR)),

	// Derived Composite values that include any defaults
	APP_WORKSPACE_GIT(combine(APP_PATHS_ROOT, APP_WORKSPACE_GIT_NAME)),
	APP_INSTANCE_CONFIG(combine(APP_PATHS_ROOT, APP_INSTANCE_CONFIG_rel)),
	APP_CORE_CONFIG(combine(APP_PATHS_ROOT, APP_CORE_CONFIG_rel)),
	APP_FACILITY_CONFIG(combine(APP_PATHS_ROOT, APP_FACILITY_CONFIG_rel)),
	APP_GROUP_CONFIG(combine(APP_PATHS_ROOT, APP_GROUP_CONFIG_rel)),

	APP_INSTANCE_PROPERTIES(combine(APP_INSTANCE_CONFIG, "properties")),

	GDA_CONFIG(APP_INSTANCE_CONFIG.value),

	GDA_SPRING_XML_FILE_PATHS(multiCaseJoiner(
			combine(APP_INSTANCE_CONFIG, "servers"), APP_PROFILES_MODE, (one, two) -> combine(combine(one, two), "server.xml"))),
	APP_SPRING_XML_FILE_PATHS(getFromApplicationArgsUsingKeySetWithDefault(GDA_SPRING_XML_FILE_PATHS, "-f")),

	APP_PROPERTIES_FILE(combine(APP_INSTANCE_PROPERTIES, combine(APP_MODE, APP_MODE + "_instance_java.properties"))),
	APP_JCA_LIBRARY_FILE(combine(APP_INSTANCE_PROPERTIES, combine(APP_MODE, "JCALibrary.properties")));

	private static final String[] APP_JAVA_OPTS = JAVA_OPTS.value.split(" ");

	private static final String[] BASIC_VM_ARGS =  {"-Dgda.install.workspace.loc=" + combine(APP_PATHS_ROOT, GDA_WORKSPACE_NAME),
													"-Dgda.install.git.loc=" + APP_WORKSPACE_GIT,
													"-Dgda.config=" + APP_INSTANCE_CONFIG};

	// N.B. this follows what the gda script calls the 'STANDARD' layout also mode is currently opposite of script default (live)
	private static final String[] OPTIONAL_VM_ARGS = concat(initialiseOptions(),  arrayOf(	"-Dgda.mode=" + APP_MODE,
																							"-Dgda.propertiesFile=" + APP_PROPERTIES_FILE,
																							"-Dgda.core.dir=" + APP_CORE_CONFIG,
																							"-Dgda.facility.dir=" + APP_FACILITY_CONFIG,
																							"-Dgda.group.dir=" + APP_GROUP_CONFIG,
																							"-Dgda.instance.dir=" + APP_INSTANCE_CONFIG), String.class);

	private static final String[] OBJECT_SERVER_VM_ARGS =  {"-Dgov.aps.jca.JCALibrary.properties=" + APP_JCA_LIBRARY_FILE,
															"-Dderby.stream.error.field=uk.ac.diamond.daq.persistence.jythonshelf.LocalObjectShelfManager.DerbyLogStream"};


	private static final Logger logger = LoggerFactory.getLogger(ConfigurationDefaults.class);

	private static boolean initialised = false;

	private final String value;

	private ConfigurationDefaults(final String value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return value;
	}

	/**
	 * Assemble the default values for the common server startup args and system properties. These would previously be set by the startup
	 * scripts or explicitly from the command line. If not, this method will set them based on the default values above. Also force loading
	 * of Local Properties so that they are available to subsequent code including the buildXXCommand methods below.
	 */
	public static synchronized void initialise() {
		if (!initialised) {
			final String[] basicArgs = concat(standardBasicArgs(), OBJECT_SERVER_VM_ARGS, String.class);
			final String[] optionalArgs = concat(OPTIONAL_VM_ARGS, "-Djava.awt.headless=true");
			final String[] vmArgs =  concat(basicArgs, optionalArgs, String.class);
			final Properties properties = System.getProperties();
			for (String arg : vmArgs) {
				String[] bits = arg.split("=");
				// if the sys prop has not already been set from the command line, use the one generated from defaults
				if (bits[0].startsWith("-D") && StringUtils.isBlank(properties.getProperty(bits[0].substring(2)))) {
					System.setProperty(bits[0].substring(2), bits[1]);
				}
			}
			if (StringUtils.isBlank(properties.getProperty(LocalProperties.GDA_BEAMLINE_NAME))) {
				System.setProperty(LocalProperties.GDA_BEAMLINE_NAME, APP_BEAMLINE.value);
			}
			LocalProperties.load();
			logger.info("LocalProperties loaded");
			LoggingUtils.setLogDirectory();
			initialised  = true;
		}
	}

	// utility methods to handle the defaulting of value that might be set by environment variables/system properties

	private static String getEnvVarWithDefault(final ConfigurationDefaults instance) {
		return getEnvVarWithDefault(instance, null);
	}

	private static String getEnvVarWithDefault(final ConfigurationDefaults instance, final CaseFormat format) {
		final String name = format != null ? CaseFormat.UPPER_UNDERSCORE.to(format, instance.name()): instance.name();
		final String loaded = System.getenv(name);
		return loaded != null ? loaded : instance.value;
	}

	private static String getSystemPropertyWithDefault(final ConfigurationDefaults instance, final String defaultValue) {
		final String loaded = System.getProperty(instance.name().toLowerCase().replaceAll("_","."));
		return loaded != null ? loaded : defaultValue;
	}

	private static String getHierarchicalValueWithDefault(final ConfigurationDefaults instance) {
		return getSystemPropertyWithDefault(instance, getEnvVarWithDefault(instance));
	}

	private static String getHierarchicalValueWithDefault(final ConfigurationDefaults propInstance, final ConfigurationDefaults envInstance) {
		return getSystemPropertyWithDefault(propInstance, getEnvVarWithDefault(envInstance));
	}

	/**
	 * Joins path fragments where one is a single token string and the other is a comma separated list of tokens to e.g. make
	 * "dummy" and "main,cameraserver" into "main/dummy,cameraserver/dummy". Uses a BiFunction lambda to achieve this allowing
	 * the token order and exact method of joining to be specified by the caller.
	 *
	 * @param token			A String containing an element that should be the same for all results in the result list
	 * @param casesList		A String containing a comma separated list of elements that should differ in each result in the result list
	 * @param builder		A function that takes two strings combines them in a caller specific way
	 * @return				A String containing a comma separated list of the combination of the common and per result elements
	 */
	private static String multiCaseJoiner(final String token, final ConfigurationDefaults casesList, final BiFunction<String, String, String> builder) {
		final String[] cases = casesList.value.split(",");
		for (int i = 0 ; i < cases.length ; i++) {
			cases[i] = builder.apply(token, cases[i]);
		}
		return StringUtils.join(cases, ',');
	}

	/**
	 * Retrieve the value of a command line argument corresponding to a set of equivalent but alternative keys e.g. -c and --config
	 *
	 * @param defaultInstanceValue	The default value to return if none of the keys were specified as a command line arg.
	 * @param keys					The set of keys to look for
	 * @return						The value of the keyed parameter or EMPTY (i.e. "")
	 */
	private static String getFromApplicationArgsUsingKeySetWithDefault(final ConfigurationDefaults defaultInstanceValue, final String... keys) {
		final String[] applicationArgs = Platform.getApplicationArgs();
		for (String key : keys) {
			for (int i = 0; i < applicationArgs.length; i += 2) {
				if (applicationArgs[i].equals(key) &&  applicationArgs.length > i + 1) {
					return applicationArgs[i + 1];
				}
			}
		}
		return defaultInstanceValue.value;
	}

	/**
	 * Allows an external configuration directory location to be set independent of the workspace and the
	 * application root path (parent of workspace if running in Eclipse, <install_dir>/dls_root otherwise)
	 *
	 * @param defaultValue	The default config path to be used if the configuration override arg is not set
	 * @return				The specified override or the supplied default value.
	 */
	private static String getFromConfigPathOverrideWithDefault(final String defaultValue) {
		final String configOverride = getFromApplicationArgsUsingKeySetWithDefault(EMPTY, "-c", "--config");
		if(configOverride.isEmpty()) {
			return defaultValue;
		}
		final Path pathsRoot = Paths.get(APP_PATHS_ROOT.value);
		final Path override = Paths.get(configOverride);
		return pathsRoot.relativize(override).toString();
	}

	private static String[] standardBasicArgs() {
		return concat(APP_JAVA_OPTS, BASIC_VM_ARGS, String.class);
	}

	// set of methods to join two elements of a path using the appropriate character

	private static final String combine(final ConfigurationDefaults first, final ConfigurationDefaults second){
		return combine(first.value, second.value);
	}

	private static final String combine(final ConfigurationDefaults first, final String second){
		return combine(first.value, second);
	}

	private static final String combine(final String first, final String second){
		return new StringBuilder(first).append(File.separator).append(second).toString();
	}

	private static final String manageMTMigration(final String relativeConfig) {
		String[] options = {"i06-config", "i06-1-config", "i10-config"};
		for (String match : options) {
			if (relativeConfig.contains(match)) {
				return match.replaceFirst("(-\\d+)?-config", "-shared");
			}
		}
		return "nogroup";
	}

	/**
	 * Initialise the components of the optional args that truly are optional i.e. aren't
	 * currently defaulted by the gda python script if they haven't been specified
	 *
	 * @return a zero to 3 length array of those args that have been specified
	 */
	private static final String[] initialiseOptions() {
		final List<String> args = new ArrayList<>();
		if (APP_LOGS_DIR.value != null) {
			args.add("-Dgda.logs.dir=" + APP_LOGS_DIR);
		}
		if (APP_DATA.value != null) {
			args.add("-Dgda.data=" + APP_DATA);
		}
		if (APP_VAR.value != null) {
			args.add("-Dgda.var=" + APP_VAR);
		}
		return args.toArray(new String[args.size()]);
	}

	/**
	 * Load the configuration layout properties from the default beamline cfg file (beamlineLayouts.cfg) or from another file
	 * with the path declared by the BEAMLINE_CONFIG property.
	 *
	 * @return
	 * either: the comma separated pair of the config relative path and layout scheme if set in beamlineLayouts.cfg or other file
	 * or (if using the default cfg):
	 * 			"$rel_path_from_cfg_file,standard" if no layout scheme provided
	 * 			"gda-diamond.git/configurations/$APP_BEAMLINE-config,standard" if neither relpath or layout provided
	 * @throws the Runtime IllegalArgumentException if the file cannot be loaded or it the loaded string is badly formed
	 */
	private static String loadLayoutLookup() {
		final Properties layoutLookup = new Properties();
		String configLocation = getHierarchicalValueWithDefault(BEAMLINE_CONFIG);
		if (configLocation.equals(BEAMLINE_CONFIG.value)) {
			return defaultLoadLayout(layoutLookup);
		}
		try {
			InputStream stream = new FileInputStream(new File(configLocation));
			layoutLookup.load(stream);
			String layoutDetails = layoutLookup.getProperty(APP_BEAMLINE.value);
			if (StringUtils.isBlank(layoutDetails) || !layoutDetails.contains(",")) {
				throw new IllegalArgumentException("Beamline config layout property invalid");
			}
			return layoutDetails;
		} catch (FileNotFoundException f) {
			throw new IllegalArgumentException("Beamline config layout file cannot be loaded", f);
		} catch (IOException e) {
			throw new IllegalArgumentException(String.format("Unable to look up %s from %s", APP_BEAMLINE.value , configLocation), e);
		}
	}

	private static String defaultLoadLayout(Properties layoutLookup) {
		try {
			layoutLookup.load(ConfigurationDefaults.class.getResourceAsStream(BEAMLINE_CONFIG.value));
			String layoutDetails = layoutLookup.getProperty(APP_BEAMLINE.value) == null
					? String.format("gda-diamond.git/configurations/%s-config,standard", APP_BEAMLINE.value)
					: layoutLookup.getProperty(APP_BEAMLINE.value);
			return String.format(layoutDetails.contains(",") ? layoutDetails : layoutDetails.concat(",standard"));
		} catch (IOException e) {
			throw new IllegalArgumentException(String.format("Unable to look up %s from %s", APP_BEAMLINE.value , BEAMLINE_CONFIG.value), e);
		}
	}

	/**
	 * Convenience method for use in calls to methods that don't or can't (because of parameter position) take String varargs.
	 *
	 * @param members		The array members in varargs form
	 * @return				The array of members
	 */
	private static String[] arrayOf(String ... members) {
		return members;
	}
}
