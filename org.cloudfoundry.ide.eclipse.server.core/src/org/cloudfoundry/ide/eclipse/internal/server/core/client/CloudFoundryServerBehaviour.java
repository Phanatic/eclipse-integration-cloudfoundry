/*******************************************************************************
 * Copyright (c) 2012, 2013 GoPivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     GoPivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.cloudfoundry.ide.eclipse.internal.server.core.client;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudfoundry.client.lib.CloudCredentials;
import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.StartingInfo;
import org.cloudfoundry.client.lib.UploadStatusCallback;
import org.cloudfoundry.client.lib.archive.ApplicationArchive;
import org.cloudfoundry.client.lib.domain.ApplicationStats;
import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.cloudfoundry.client.lib.domain.CloudApplication.AppState;
import org.cloudfoundry.client.lib.domain.CloudDomain;
import org.cloudfoundry.client.lib.domain.CloudService;
import org.cloudfoundry.client.lib.domain.CloudServiceOffering;
import org.cloudfoundry.client.lib.domain.InstancesInfo;
import org.cloudfoundry.client.lib.domain.Staging;
import org.cloudfoundry.ide.eclipse.internal.server.core.ApplicationAction;
import org.cloudfoundry.ide.eclipse.internal.server.core.CachingApplicationArchive;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudApplicationUrlLookup;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudErrorUtil;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryLoginHandler;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryPlugin;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryServer;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudUtil;
import org.cloudfoundry.ide.eclipse.internal.server.core.DeploymentConfiguration;
import org.cloudfoundry.ide.eclipse.internal.server.core.ModuleResourceDeltaWrapper;
import org.cloudfoundry.ide.eclipse.internal.server.core.RefreshJob;
import org.cloudfoundry.ide.eclipse.internal.server.core.application.ApplicationRegistry;
import org.cloudfoundry.ide.eclipse.internal.server.core.application.IApplicationDelegate;
import org.cloudfoundry.ide.eclipse.internal.server.core.debug.CloudFoundryProperties;
import org.cloudfoundry.ide.eclipse.internal.server.core.debug.DebugCommandBuilder;
import org.cloudfoundry.ide.eclipse.internal.server.core.debug.DebugModeType;
import org.cloudfoundry.ide.eclipse.internal.server.core.spaces.CloudFoundrySpace;
import org.cloudfoundry.ide.eclipse.internal.server.core.spaces.CloudSpaceServerLookup;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jst.server.core.IWebModule;
import org.eclipse.osgi.util.NLS;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.IServerListener;
import org.eclipse.wst.server.core.ServerEvent;
import org.eclipse.wst.server.core.internal.Server;
import org.eclipse.wst.server.core.model.IModuleFile;
import org.eclipse.wst.server.core.model.IModuleResource;
import org.eclipse.wst.server.core.model.IModuleResourceDelta;
import org.eclipse.wst.server.core.model.ServerBehaviourDelegate;
import org.springframework.web.client.RestClientException;

/**
 * 
 * Contains many of the calls to the CF Java client. The CF server behaviour
 * should be the main call point for interacting with the actual cloud server,
 * with the exception of Caldecott, which is handled in a similar behaviour.
 * 
 * It's important to note that almost all Java client calls are wrapped around a
 * Request object, and it is important to wrap future client calls around a
 * Request object, as the request object handles automatic client login, server
 * state verification, and proxy handling.
 * 
 * 
 * @author Christian Dupuis
 * @author Leo Dos Santos
 * @author Terry Denney
 * @author Steffen Pingel
 * @author Nieraj Singh
 */
@SuppressWarnings("restriction")
public class CloudFoundryServerBehaviour extends ServerBehaviourDelegate {

	static String ERROR_RESULT_MESSAGE = " - Unable to deploy or start application";

	private CloudFoundryOperations client;

	private RefreshJob refreshJob;

	private CloudApplicationUrlLookup applicationUrlLookup;

	/*
	 * FIXNS: Until V2 MCF is released, disable debugging support for V2, as
	 * public clouds also indicate they support debug.
	 */
	private DebugSupportCheck isDebugModeSupported = DebugSupportCheck.UNSUPPORTED;

	private IServerListener serverListener = new IServerListener() {

		public void serverChanged(ServerEvent event) {
			if (event.getKind() == ServerEvent.SERVER_CHANGE) {
				// reset client to consume updated credentials
				resetClient();
			}
		}
	};

	protected enum DebugSupportCheck {
		// Initial state of the debug support check. used so that further checks
		// are not necessary in a given session
		UNCHECKED,
		// Server supports debug mode
		SUPPORTED,
		// Server does not support debug mode
		UNSUPPORTED,
	}

	@Override
	public boolean canControlModule(IModule[] module) {
		return module.length == 1;
	}

	public void connect(IProgressMonitor monitor) throws CoreException {
		final CloudFoundryServer cloudServer = getCloudFoundryServer();

		new Request<Void>(NLS.bind("Loggging in to {0}", cloudServer.getUrl())) {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.login();
				return null;
			}
		}.run(monitor);

		doRefreshModules(cloudServer, monitor);

		Server server = (Server) cloudServer.getServerOriginal();
		server.setServerState(IServer.STATE_STARTED);
		server.setServerPublishState(IServer.PUBLISH_STATE_NONE);

		CloudFoundryPlugin.getDefault().fireServerRefreshed(cloudServer);
	}

	/**
	 * Determine if server supports debug mode, if necessary by sending a
	 * request to the server. The information is cached for quicker, subsequent
	 * checks.
	 * 
	 */
	protected synchronized void requestAllowDebug(CloudFoundryOperations client) throws CoreException {
		// Check the debug support of the server once per working copy of server
		if (isDebugModeSupported == DebugSupportCheck.UNCHECKED) {
			isDebugModeSupported = client.getCloudInfo().getAllowDebug() ? DebugSupportCheck.SUPPORTED
					: DebugSupportCheck.UNSUPPORTED;
		}
	}

	/**
	 * Creates the given list of services
	 * @param services
	 * @param monitor
	 * @throws CoreException
	 */
	public void createService(final CloudService[] services, IProgressMonitor monitor) throws CoreException {

		new Request<Void>(services.length == 1 ? NLS.bind("Creating service {0}", services[0].getName())
				: "Creating services") {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {

				for (CloudService service : services) {
					client.createService(service);
				}

				return null;
			}
		}.run(monitor);
		CloudFoundryPlugin.getDefault().fireServicesUpdated(getCloudFoundryServer());
	}

	public synchronized List<CloudDomain> getDomainsFromOrgs(IProgressMonitor monitor) throws CoreException {
		return new Request<List<CloudDomain>>("Getting domains for orgs") {
			@Override
			protected List<CloudDomain> doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getDomainsForOrg();
			}
		}.run(monitor);

	}

	public synchronized List<CloudDomain> getDomainsForSpace(IProgressMonitor monitor) throws CoreException {
		return new Request<List<CloudDomain>>("Getting domains for current space") {
			@Override
			protected List<CloudDomain> doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getDomains();
			}
		}.run(monitor);
	}

	public void deleteModules(final IModule[] modules, final boolean deleteServices, IProgressMonitor monitor)
			throws CoreException {
		final CloudFoundryServer cloudServer = getCloudFoundryServer();
		new Request<Void>() {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				for (IModule module : modules) {
					final CloudFoundryApplicationModule appModule = cloudServer.getExistingCloudModule(module);

					List<String> servicesToDelete = new ArrayList<String>();

					CloudApplication application = client.getApplication(appModule.getDeployedApplicationName());

					// Fix for STS-2416: Get the CloudApplication from
					// the client again, as the CloudApplication
					// mapped to the local Cloud application module may be
					// out of date and have an out of date list of
					// services.
					List<String> actualServices = application.getServices();
					if (actualServices != null) {
						// This has to be used instead of addAll(..), as
						// there is a chance the list is non-empty but
						// contains null entries
						for (String serviceName : actualServices) {
							if (serviceName != null) {
								servicesToDelete.add(serviceName);
							}
						}
					}

					// Close any Caldecott tunnels before deleting app
					if (TunnelBehaviour.isCaldecottApp(appModule.getDeployedApplicationName())) {
						// Delete all tunnels if the Caldecott app is
						// removed
						new TunnelBehaviour(cloudServer).stopAndDeleteAllTunnels(progress);
					}

					client.deleteApplication(appModule.getDeployedApplicationName());

					cloudServer.removeApplication(appModule);

					// Be sure the cloud application mapping is removed
					// in case other components still have a reference to the
					// module
					appModule.setCloudApplication(null);

					// Prompt the user to delete services as well
					if (deleteServices && !servicesToDelete.isEmpty()) {
						CloudFoundryPlugin.getCallback().deleteServices(servicesToDelete, cloudServer);
						CloudFoundryPlugin.getDefault().fireServicesUpdated(cloudServer);
					}

				}
				return null;
			}
		}.run(monitor);
		// If succeeded, refresh module list right away
		restartRefreshJob(ClientRequestOperation.DEFAULT_INTERVAL);
	}

	public void deleteServices(final List<String> services, IProgressMonitor monitor) throws CoreException {
		new Request<Void>("Deleting services") {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				TunnelBehaviour handler = new TunnelBehaviour(getCloudFoundryServer());
				for (String service : services) {
					client.deleteService(service);

					// Also delete any existing Tunnels
					handler.stopAndDeleteCaldecottTunnel(service, progress);
				}
				return null;
			}
		}.run(monitor);
	}

	/**
	 * The Cloud application URL lookup is used to resolve a list of URL domains
	 * that an application can user when specifying a URL. It also validates
	 * suggested application URLs, to check that the host and domain portions of
	 * the URL are correction.
	 * <p/>
	 * Note that this only returns a cached lookup. The lookup may have to be
	 * refreshed separately to get the most recent list of domains.
	 * @return Lookup to retrieve list of application URL domains, as well as
	 * verify validity of an application URL. May be null as its a cached
	 * version.
	 * @throws CoreException if server related errors, like failing to connect
	 * or resolve server
	 */
	public CloudApplicationUrlLookup getApplicationUrlLookup() {
		return applicationUrlLookup;
	}

	protected List<IModuleResource> getChangedResources(IModuleResourceDelta[] deltas) {
		List<IModuleResource> changed = new ArrayList<IModuleResource>();
		if (deltas != null) {
			findNonChangedResources(deltas, changed);
		}
		return changed;

	}

	protected void findNonChangedResources(IModuleResourceDelta[] deltas, List<IModuleResource> changed) {
		if (deltas == null || deltas.length == 0) {
			return;
		}
		for (IModuleResourceDelta delta : deltas) {
			// Only handle file resources
			IModuleResource resource = delta.getModuleResource();
			if (resource instanceof IModuleFile && delta.getKind() != IModuleResourceDelta.NO_CHANGE) {
				changed.add(new ModuleResourceDeltaWrapper(delta));
			}

			findNonChangedResources(delta.getAffectedChildren(), changed);
		}
	}

	public void disconnect(IProgressMonitor monitor) throws CoreException {
		CloudFoundryPlugin.getCallback().disconnecting(getCloudFoundryServer());

		Server server = (Server) getServer();
		server.setServerState(IServer.STATE_STOPPING);

		setRefreshInterval(-1);

		CloudFoundryServer cloudServer = getCloudFoundryServer();

		Set<CloudFoundryApplicationModule> deletedModules = new HashSet<CloudFoundryApplicationModule>(
				cloudServer.getExistingCloudModules());
		cloudServer.clearApplications();

		// update state for cloud applications
		server.setExternalModules(new IModule[0]);
		for (CloudFoundryApplicationModule module : deletedModules) {
			server.setModuleState(new IModule[] { module.getLocalModule() }, IServer.STATE_UNKNOWN);
		}

		server.setServerState(IServer.STATE_STOPPED);
		server.setServerPublishState(IServer.PUBLISH_STATE_NONE);
		closeCaldecottTunnels(monitor);
	}

	@Override
	public void dispose() {
		super.dispose();
		getServer().removeServerListener(serverListener);
		closeCaldecottTunnelsAsynch();
	}

	/**
	 * This method is API used by CloudFoundry Code.
	 */
	public CloudFoundryServer getCloudFoundryServer() throws CoreException {
		Server server = (Server) getServer();

		CloudFoundryServer cloudFoundryServer = (CloudFoundryServer) server.loadAdapter(CloudFoundryServer.class, null);
		if (cloudFoundryServer == null) {
			throw new CoreException(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID, "Fail to load server"));
		}
		return cloudFoundryServer;
	}

	public CloudApplication getApplication(final String applicationId, IProgressMonitor monitor) throws CoreException {
		return new Request<CloudApplication>() {
			@Override
			protected CloudApplication doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getApplication(applicationId);
			}
		}.run(monitor);
	}

	public List<CloudApplication> getApplications(IProgressMonitor monitor) throws CoreException {
		return new Request<List<CloudApplication>>("Getting applications") {
			@Override
			protected List<CloudApplication> doRun(CloudFoundryOperations client, SubMonitor progress)
					throws CoreException {
				return client.getApplications();
			}
		}.run(monitor);
	}

	public ApplicationStats getApplicationStats(final String applicationId, IProgressMonitor monitor)
			throws CoreException {
		return new StagingAwareRequest<ApplicationStats>(NLS.bind("Getting application statistics for {0}",
				applicationId)) {
			@Override
			protected ApplicationStats doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getApplicationStats(applicationId);
			}
		}.run(monitor);
	}

	public InstancesInfo getInstancesInfo(final String applicationId, IProgressMonitor monitor) throws CoreException {
		return new StagingAwareRequest<InstancesInfo>(NLS.bind("Getting application statistics for {0}", applicationId)) {
			@Override
			protected InstancesInfo doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getApplicationInstances(applicationId);
			}
		}.run(monitor);
	}

	public String getFile(final String applicationId, final int instanceIndex, final String path,
			IProgressMonitor monitor) throws CoreException {
		return new FileRequest<String>() {
			@Override
			protected String doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getFile(applicationId, instanceIndex, path);
			}
		}.run(monitor);
	}

	public String getFile(final String applicationId, final int instanceIndex, final String filePath,
			final int startPosition, IProgressMonitor monitor) throws CoreException {
		return new FileRequest<String>() {
			@Override
			protected String doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getFile(applicationId, instanceIndex, filePath, startPosition);
			}
		}.run(monitor);
	}

	public int[] getApplicationMemoryChoices() {
		if (client != null) {
			return client.getApplicationMemoryChoices();
		}
		return new int[0];
	}

	public DeploymentConfiguration getDeploymentConfiguration(IProgressMonitor monitor) throws CoreException {
		return new Request<DeploymentConfiguration>("Getting available service options") {
			@Override
			protected DeploymentConfiguration doRun(CloudFoundryOperations client, SubMonitor progress)
					throws CoreException {
				DeploymentConfiguration configuration = new DeploymentConfiguration(getApplicationMemoryChoices());
				// XXX make bogus call that triggers login if needed to work
				// around NPE in client.getApplicationMemoryChoices()
				client.getServices();
				return configuration;
			}
		}.run(monitor);
	}

	public List<CloudServiceOffering> getServiceOfferings(IProgressMonitor monitor) throws CoreException {
		return new Request<List<CloudServiceOffering>>("Getting available service options") {
			@Override
			protected List<CloudServiceOffering> doRun(CloudFoundryOperations client, SubMonitor progress)
					throws CoreException {
				return client.getServiceOfferings();
			}
		}.run(monitor);
	}

	/**
	 * For testing only.
	 */
	public void deleteAllApplications(IProgressMonitor monitor) throws CoreException {
		new Request<Object>("Deleting all applications") {
			@Override
			protected Object doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.deleteAllApplications();
				return null;
			}
		}.run(monitor);
	}

	public List<CloudService> getServices(IProgressMonitor monitor) throws CoreException {
		return new Request<List<CloudService>>("Getting available services") {
			@Override
			protected List<CloudService> doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getServices();
			}
		}.run(monitor);
	}

	/**
	 * Refresh the application modules and reschedules the app module refresh
	 * job to execute at certain intervals.
	 * @param monitor
	 * @throws CoreException
	 */
	public void refreshModules(IProgressMonitor monitor) throws CoreException {
		final CloudFoundryServer cloudServer = getCloudFoundryServer();

		doRefreshModules(cloudServer, monitor);

		CloudFoundryPlugin.getDefault().fireServerRefreshed(cloudServer);
	}

	public void resetClient() {
		client = null;
		applicationUrlLookup = null;
	}

	/**
	 * Updates the app module's deployment information. If necessary, it may
	 * prompt for any missing information. Note that the current deployment
	 * information in the app module need not be valid or set.
	 * @param appModule
	 * @param monitor
	 * @throws CoreException if error in prompting or validating deployment
	 * information.
	 * @throws OperationCanceledException if deployment was cancelled while
	 * prompting for missing values.
	 */
	protected void updateDeploymentInfo(CloudFoundryApplicationModule appModule, IProgressMonitor monitor)
			throws CoreException {

		CloudFoundryServer cloudServer = getCloudFoundryServer();

		// prompt user for missing details
		CloudFoundryPlugin.getCallback().prepareForDeployment(cloudServer, appModule, monitor);
	}

	/**
	 * Stops the application and does a full publish of the application before
	 * restarting it. Should not be called for incremental updates.
	 * @param modules
	 * @param monitor
	 * @return
	 * @throws CoreException
	 */
	public CloudFoundryApplicationModule debugModule(IModule[] modules, IProgressMonitor monitor) throws CoreException {
		return doDebugModule(modules, true, monitor);
	}

	/**
	 * Deploys or starts an app in debug mode and either a full publish or
	 * incremental publish may be specified. Automatic detection of changes in
	 * an app project are detected, and if incremental publish is specified, an
	 * optimised incremental publish is used that only typically would involve
	 * one payload to the server containing just the changes.
	 * @param modules
	 * @param fullPublish
	 * @param monitor
	 * @return
	 * @throws CoreException
	 */
	protected CloudFoundryApplicationModule doDebugModule(IModule[] modules, boolean incrementalPublish,
			IProgressMonitor monitor) throws CoreException {
		// This flag allows modules to be updated after the app is deployed in
		// debug mode
		// This is necessary to make sure the app module is updated with the
		// latest cloud application
		// in particular if a mode change has occurred (i.e. starting an
		// application in debug
		// mode that was previously running in regular mode). CF feature relies
		// on
		// cached information from the cloud application to be accurate to check
		// whether certain functionality is enabled, etc..
		boolean waitForDeployment = true;

		boolean debug = true;

		return doDeployOrStartModule(modules, waitForDeployment, incrementalPublish, monitor, debug);
	}

	/**
	 * Deploys or starts a module by first doing a full publish of the app.
	 * @param modules
	 * @param waitForDeployment
	 * @param monitor
	 * @return
	 * @throws CoreException
	 * @throws {@link OperationCanceledException} if deployment or start
	 * cancelled.
	 */
	public CloudFoundryApplicationModule deployOrStartModule(final IModule[] modules, boolean waitForDeployment,
			IProgressMonitor monitor) throws CoreException {
		return doDeployOrStartModule(modules, waitForDeployment, false, monitor, false);
	}

	/**
	 * Deploys or starts a module by doing either a full publish or incremental.
	 * @param modules
	 * @param waitForDeployment
	 * @param isIncremental
	 * @param monitor
	 * @return
	 * @throws CoreException
	 */
	protected CloudFoundryApplicationModule doDeployOrStartModule(final IModule[] modules, boolean waitForDeployment,
			boolean isIncremental, IProgressMonitor monitor, boolean debug) throws CoreException {

		CloudFoundryApplicationModule appModule = getOrCreateCloudApplicationModule(modules);

		updateDeploymentInfo(appModule, monitor);

		DeploymentInfoWorkingCopy workingCopy = appModule.getDeploymentInfoWorkingCopy();
		workingCopy.setIncrementalPublish(isIncremental);

		if (debug) {
			workingCopy.setDeploymentMode(ApplicationAction.DEBUG);
		}
		workingCopy.save();
		appModule = new StartOrDeployAction(waitForDeployment, modules).run(monitor);

		return appModule;
	}

	/**
	 * Returns non-null Cloud application module mapped to the first module in
	 * the list of modules. If the cloud module module does not exist for the
	 * given module, it will attempt to create it. To avoid re-creating a cloud
	 * application module that may have been deleted, restrict invoking this
	 * method to only operations that start, restart, or update an application.
	 * Should not be called when deleting an application.
	 * @param local WST modules representing app to be deployed.
	 * @return non-null Cloud Application module mapped to the given WST module.
	 * @throws CoreException if no modules specified or mapped cloud application
	 * module cannot be resolved.
	 */
	protected CloudFoundryApplicationModule getOrCreateCloudApplicationModule(IModule[] modules) throws CoreException {

		CloudFoundryApplicationModule appModule = null;
		if (modules == null || modules.length == 0) {
			throw CloudErrorUtil.toCoreException("No WST IModule specified." + ERROR_RESULT_MESSAGE);
		}
		else {
			IModule module = modules[0];

			CloudFoundryServer cloudServer = getCloudFoundryServer();

			appModule = cloudServer.getCloudModule(module);

			if (appModule == null) {
				throw CloudErrorUtil.toCoreException(("No mapped Cloud Foundry application module found for: "
						+ modules[0].getId() + ERROR_RESULT_MESSAGE));
			}

		}

		return appModule;
	}

	/**
	 * Returns non-null Cloud application module mapped to the first module in
	 * the list of modules. The Cloud application module will also have a valid
	 * deployment info.
	 * @param modules representing app to be deployed.
	 * @return non-null Cloud Application module mapped to the given WST module.
	 * @throws CoreException if no modules specified, mapped cloud application
	 * module cannot be resolved, or the app module's deployment info is
	 * invalid.
	 */
	protected CloudFoundryApplicationModule getAndValidateCloudApplicationModule(IModule[] modules)
			throws CoreException {
		CloudFoundryApplicationModule appModule = getOrCreateCloudApplicationModule(modules);
		IStatus validationStatus = appModule.validateDeploymentInfo();
		if (!validationStatus.isOK()) {
			throw CloudErrorUtil.toCoreException("Invalid application deployment information for: "
					+ appModule.getDeployedApplicationName() + ERROR_RESULT_MESSAGE + " - "
					+ validationStatus.getMessage());
		}
		return appModule;
	}

	@Override
	public void startModule(IModule[] modules, IProgressMonitor monitor) throws CoreException {
		deployOrStartModule(modules, false, monitor);
	}

	@Override
	public void stop(boolean force) {
		// This stops the server locally, it does NOT stop the remotely running
		// applications
		setServerState(IServer.STATE_STOPPED);
		closeCaldecottTunnelsAsynch();
	}

	protected void closeCaldecottTunnelsAsynch() {
		String jobName = "Stopping all tunnels";

		try {
			jobName += ": " + getCloudFoundryServer().getDeploymentName();
		}
		catch (CoreException e1) {
			CloudFoundryPlugin.log(e1);
		}

		Job job = new Job(jobName) {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				closeCaldecottTunnels(monitor);
				return Status.OK_STATUS;
			}

		};
		job.setSystem(false);
		job.schedule();
	}

	protected void closeCaldecottTunnels(IProgressMonitor monitor) {
		// Close all open Caldecott Tunnels
		try {
			new TunnelBehaviour(getCloudFoundryServer()).stopAndDeleteAllTunnels(monitor);
		}
		catch (CoreException e) {
			CloudFoundryPlugin.log(e);
		}
	}

	@Override
	public void stopModule(IModule[] modules, IProgressMonitor monitor) throws CoreException {
		Server server = (Server) getServer();
		boolean succeeded = false;
		try {
			server.setModuleState(modules, IServer.STATE_STOPPING);

			CloudFoundryServer cloudServer = getCloudFoundryServer();
			final CloudFoundryApplicationModule cloudModule = cloudServer.getExistingCloudModule(modules[0]);

			// CloudFoundryPlugin.getCallback().applicationStopping(getCloudFoundryServer(),
			// cloudModule);
			new Request<Void>() {
				@Override
				protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
					client.stopApplication(cloudModule.getDeployedApplicationName());
					return null;
				}
			}.run(monitor);

			server.setModuleState(modules, IServer.STATE_STOPPED);
			succeeded = true;
			CloudFoundryPlugin.getCallback().applicationStopped(cloudModule, cloudServer);

			// If succeeded, stop all Caldecott tunnels if the app is the
			// Caldecott app
			if (TunnelBehaviour.isCaldecottApp(cloudModule.getDeployedApplicationName())) {
				TunnelBehaviour handler = new TunnelBehaviour(cloudServer);
				handler.stopAndDeleteAllTunnels(monitor);
			}
		}
		finally {
			if (!succeeded) {
				server.setModuleState(modules, IServer.STATE_UNKNOWN);
			}
		}
	}

	/**
	 * Updates and restarts an application in debug mode. Incremental publish
	 * will occur on update restarts if any changes are detected.
	 * @param modules
	 * @param monitor
	 * @param isIncrementalPublishing true if optimised incremental publishing
	 * should be enabled. False otherwise
	 * @return
	 * @throws CoreException
	 */
	public CloudFoundryApplicationModule updateRestartDebugModule(IModule[] modules, boolean isIncrementalPublishing,
			IProgressMonitor monitor) throws CoreException {
		stopModule(modules, monitor);
		return doDebugModule(modules, isIncrementalPublishing, monitor);
	}

	@Override
	/**
	 * Note that this automatically restarts a module in the start mode it is currently, or was currently running in.
	 * It automatically detects if an application is running in debug mode or regular run mode, and restarts it in that
	 * same mode. Other API exists to restart an application in a specific mode, if automatic detection and restart in
	 * existing mode is not required.
	 */
	public void restartModule(IModule[] modules, IProgressMonitor monitor) throws CoreException {

		if (CloudFoundryProperties.isApplicationRunningInDebugMode.testProperty(modules, getCloudFoundryServer())) {
			restartDebugModule(modules, monitor);
		}
		else {
			restartModuleRunMode(modules, monitor);
		}

	}

	/**
	 * This will restart an application in debug mode only. Does not push
	 * application changes or create an application. Application must exist in
	 * the CF server.
	 * @param modules
	 * @param monitor
	 * @throws CoreException
	 */
	public void restartDebugModule(IModule[] modules, IProgressMonitor monitor) throws CoreException {

		CloudFoundryApplicationModule appModule = getOrCreateCloudApplicationModule(modules);

		updateDeploymentInfo(appModule, monitor);

		DeploymentInfoWorkingCopy deploymentInfo = appModule.getDeploymentInfoWorkingCopy();
		// Indicate that the application should be launched in DebugMode
		deploymentInfo.setDeploymentMode(ApplicationAction.DEBUG);
		deploymentInfo.save();

		// stop the application first as it may be connected to a debugger
		// and port/IP need to be released
		stopModule(modules, monitor);

		new RestartAction(modules).run(monitor);
	}

	public String getStagingLogs(final StartingInfo info, final int offset, IProgressMonitor monitor)
			throws CoreException {
		return new FileRequest<String>("Reading staging logs") {

			@Override
			protected String doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				return client.getStagingLogs(info, offset);
			}

		}.run(monitor);
	}

	/**
	 * Update restart republishes redeploys the application with changes. This
	 * is not the same as restarting an application which simply restarts the
	 * application in its current server version without receiving any local
	 * changes. It will only update restart an application in regular run mode.
	 * It does not support debug mode.Publishing of changes is done
	 * incrementally.
	 * @param modules
	 * @param monitor
	 * @param isIncrementalPublishing true if optimised incremental publishing
	 * should be enabled. False otherwise
	 * @throws CoreException
	 */
	public void updateRestartModuleRunMode(IModule[] modules, boolean isIncrementalPublishing, IProgressMonitor monitor)
			throws CoreException {
		doDeployOrStartModule(modules, false, isIncrementalPublishing, monitor, false);
	}

	/**
	 * This will restart an application in run mode. It does not restart an
	 * application in debug mode. Does not push application resources or create
	 * the application. The application must exist in the CloudFoundry server.
	 * @param modules
	 * @param monitor
	 * @throws CoreException
	 */
	public void restartModuleRunMode(IModule[] modules, IProgressMonitor monitor) throws CoreException {
		CloudFoundryApplicationModule appModule = getOrCreateCloudApplicationModule(modules);
		updateDeploymentInfo(appModule, monitor);
		new RestartAction(modules).run(monitor);
	}

	public void updateApplicationInstances(CloudFoundryApplicationModule module, final int instanceCount,
			IProgressMonitor monitor) throws CoreException {
		final String appName = module.getApplication().getName();
		new AppInStoppedStateAwareRequest<Void>() {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.updateApplicationInstances(appName, instanceCount);
				return null;
			}
		}.run(monitor);

		CloudFoundryPlugin.getDefault().fireInstancesUpdated(getCloudFoundryServer());
	}

	public void updatePassword(final String newPassword, IProgressMonitor monitor) throws CoreException {
		new Request<Void>() {

			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.updatePassword(newPassword);
				return null;
			}

		}.run(monitor);
	}

	public void updateApplicationMemory(CloudFoundryApplicationModule module, final int memory, IProgressMonitor monitor)
			throws CoreException {
		final String appName = module.getApplication().getName();
		new AppInStoppedStateAwareRequest<Void>() {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.updateApplicationMemory(appName, memory);
				return null;
			}
		}.run(monitor);
	}

	public void updateApplicationUrls(final String appName, final List<String> uris, IProgressMonitor monitor)
			throws CoreException {
		new AppInStoppedStateAwareRequest<Void>() {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.updateApplicationUris(appName, uris);
				return null;
			}
		}.run(monitor);
	}

	public List<String> findCaldecottTunnelsToClose(CloudFoundryOperations client, String appName,
			List<String> servicesToUpdate) {
		List<String> services = new ArrayList<String>();

		CloudApplication caldecottApp = client.getApplication(appName);
		if (caldecottApp != null) {
			List<String> existingServices = caldecottApp.getServices();
			if (existingServices != null) {
				Set<String> possibleDeletedServices = new HashSet<String>();
				// Must iterate rather than passing to constructor or using
				// addAll, as some
				// of the entries in existing services may be null
				for (String existingService : existingServices) {
					if (existingService != null) {
						possibleDeletedServices.add(existingService);
					}
				}

				for (String updatedService : servicesToUpdate) {
					if (possibleDeletedServices.contains(updatedService)) {
						possibleDeletedServices.remove(updatedService);
					}
				}
				services.addAll(possibleDeletedServices);
			}
		}
		return services;
	}

	public void updateServices(String appName, List<String> services, IProgressMonitor monitor) throws CoreException {
		updateServices(appName, services, false, monitor);
	}

	public void updateServicesAndCloseCaldecottTunnels(String appName, List<String> services, IProgressMonitor monitor)
			throws CoreException {
		updateServices(appName, services, true, monitor);

	}

	protected void updateServices(final String appName, final List<String> services,
			final boolean closeRelatedCaldecottTunnels, IProgressMonitor monitor) throws CoreException {
		new StagingAwareRequest<Void>() {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				// Prior to updating the services, obtain the current list of
				// bound services for the app
				// and determine if any services are being unbound. If unbound,
				// check if it is the Caldecott app
				// and accordingly, close related tunnels.
				if (closeRelatedCaldecottTunnels && TunnelBehaviour.isCaldecottApp(appName)) {

					List<String> caldecottServicesToClose = findCaldecottTunnelsToClose(client, appName, services);
					// Close tunnels before the services are removed
					if (caldecottServicesToClose != null) {
						TunnelBehaviour handler = new TunnelBehaviour(getCloudFoundryServer());

						for (String serviceName : caldecottServicesToClose) {
							handler.stopAndDeleteCaldecottTunnel(serviceName, progress);
						}
					}
				}

				client.updateApplicationServices(appName, services);

				return null;
			}
		}.run(monitor);
	}

	public void register(final String email, final String password, IProgressMonitor monitor) throws CoreException {
		new Request<Void>() {
			@Override
			protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
				client.register(email, password);
				return null;
			}
		}.run(monitor);
	}

	/**
	 * Public for testing only. Use alternate public getClient() API for actual
	 * client operations. If credentials are not used, as in the case when only
	 * a URL is present for a server, null must be passed for the credentials.
	 */
	public synchronized CloudFoundryOperations getClient(CloudCredentials credentials, IProgressMonitor monitor)
			throws CoreException {
		if (client == null) {
			CloudFoundrySpace cloudSpace = new CloudSpaceServerLookup(getCloudFoundryServer(), credentials)
					.getCloudSpace(monitor);

			if (credentials != null) {
				client = createClient(getCloudFoundryServer().getUrl(), credentials, cloudSpace);
			}
			else {
				String userName = getCloudFoundryServer().getUsername();
				String password = getCloudFoundryServer().getPassword();
				client = createClient(getCloudFoundryServer().getUrl(), userName, password, cloudSpace);
			}
		}
		return client;
	}

	/**
	 * In most cases, the progress monitor can be null, although if available
	 * 
	 * @param monitor
	 * @return
	 * @throws CoreException
	 */
	public synchronized CloudFoundryOperations getClient(IProgressMonitor monitor) throws CoreException {
		return getClient((CloudCredentials) null, monitor);
	}

	private boolean isApplicationReady(CloudApplication application) {
		/*
		 * RestTemplate restTemplate = new RestTemplate(); String response =
		 * restTemplate.getForObject(application.getUris().get(0),
		 * String.class); if
		 * (response.contains("B29 ROUTER: 404 - FILE NOT FOUND")) { return
		 * false; }
		 */
		return AppState.STARTED.equals(application.getState());
	}

	/**
	 * Reschedules the refresh job to start after waiting for the specified
	 * amount of time. All subsequent iterations of the refresh job also wait
	 * for the specified interval time before running.
	 */
	private void setRefreshInterval(long interval) {
		if (refreshJob == null) {
			try {
				refreshJob = new RefreshJob(getCloudFoundryServer());
			}
			catch (CoreException e) {
				CloudFoundryPlugin
						.getDefault()
						.getLog()
						.log(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID, NLS.bind(
								"Failed to start automatic refresh: {0}", e.getMessage()), e));
				return;
			}
		}
		refreshJob.reschedule(interval);
	}

	/**
	 * Schedules the refresh job to run as soon as possible the first time, and
	 * any subsequent executions continue to occur after the specified interval.
	 * @param interval to wait between each job run
	 */
	private void restartRefreshJob(long interval) {
		if (refreshJob == null) {
			try {
				refreshJob = new RefreshJob(getCloudFoundryServer());
			}
			catch (CoreException e) {
				CloudFoundryPlugin
						.getDefault()
						.getLog()
						.log(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID, NLS.bind(
								"Failed to start automatic refresh: {0}", e.getMessage()), e));
				return;
			}
		}
		refreshJob.runAndReschedule(interval);
	}

	private boolean waitForStart(CloudFoundryOperations client, String deploymentId, IProgressMonitor monitor)
			throws InterruptedException {
		long initialInterval = ClientRequestOperation.SHORT_INTERVAL;
		Thread.sleep(initialInterval);
		long timeLeft = ClientRequestOperation.DEPLOYMENT_TIMEOUT - initialInterval;
		while (timeLeft > 0) {
			CloudApplication deploymentDetails = client.getApplication(deploymentId);
			if (isApplicationReady(deploymentDetails)) {
				return true;
			}
			Thread.sleep(ClientRequestOperation.ONE_SECOND_INTERVAL);
			timeLeft -= ClientRequestOperation.ONE_SECOND_INTERVAL;
		}
		return false;
	}

	private CloudApplication getDeployedCloudApplication(CloudFoundryOperations client, String applicationId,
			IProgressMonitor monitor) {
		long timeLeft = ClientRequestOperation.UPLOAD_TIMEOUT;
		while (timeLeft > 0) {
			CloudApplication application = client.getApplication(applicationId);
			if (applicationId.equals(application.getName())) {
				return application;
			}
			try {
				Thread.sleep(ClientRequestOperation.SHORT_INTERVAL);
			}
			catch (InterruptedException e) {
				// Ignore. Try again until time runs out
			}
			timeLeft -= ClientRequestOperation.SHORT_INTERVAL;
		}
		return null;
	}

	/**
	 * Will fetch the latest list of cloud applications from the server, and
	 * update the local module mappings accordingly. It does not fire any
	 * events, it simply performs the module refreshing.
	 * @param cloudServer
	 * @param monitor
	 * @throws CoreException
	 */
	protected void doRefreshModules(final CloudFoundryServer cloudServer, IProgressMonitor monitor)
			throws CoreException {
		// Get updated list of cloud applications from the server
		List<CloudApplication> applications = getApplications(monitor);

		// update applications and deployments from server
		Map<String, CloudApplication> deployedApplicationsByName = new LinkedHashMap<String, CloudApplication>();

		for (CloudApplication application : applications) {
			deployedApplicationsByName.put(application.getName(), application);
		}

		cloudServer.updateModules(deployedApplicationsByName);
	}

	@Override
	protected void initialize(IProgressMonitor monitor) {
		super.initialize(monitor);
		getServer().addServerListener(serverListener, ServerEvent.SERVER_CHANGE);
	}

	@Override
	public IStatus publish(int kind, IProgressMonitor monitor) {
		try {
			if (kind == IServer.PUBLISH_CLEAN) {
				List<IModule[]> allModules = getAllModules();
				for (IModule[] module : allModules) {
					if (!module[0].isExternal()) {
						deployOrStartModule(module, false, monitor);
					}
				}
				return Status.OK_STATUS;
			}
			else if (kind == IServer.PUBLISH_INCREMENTAL) {
				List<IModule[]> allModules = getAllModules();
				for (IModule[] module : allModules) {
					CloudApplication app = getCloudFoundryServer().getCloudModule(module[0]).getApplication();
					if (app != null) {
						int publishState = getServer().getModulePublishState(module);
						if (publishState != IServer.PUBLISH_STATE_NONE) {
							deployOrStartModule(module, false, monitor);
						}
					}
				}
				((Server) getServer()).setServerPublishState(IServer.PUBLISH_STATE_NONE);
			}
		}
		catch (CoreException e) {
			CloudFoundryPlugin.getDefault().getLog()
					.log(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID, "Fail to publish to server", e));
			return Status.CANCEL_STATUS;
		}

		return Status.OK_STATUS;
		// return super.publish(kind, monitor);
	}

	@Override
	protected void publishModule(int kind, int deltaKind, IModule[] module, IProgressMonitor monitor)
			throws CoreException {
		super.publishModule(kind, deltaKind, module, monitor);

		// If the delta indicates that the module has been removed, remove it
		// from the server.
		// Note that although the "module" parameter is of IModule[] type,
		// documentation
		// (and the name of the parameter) indicates that it is always one
		// module
		if (deltaKind == REMOVED) {
			final CloudFoundryServer cloudServer = getCloudFoundryServer();
			final CloudFoundryApplicationModule cloudModule = cloudServer.getCloudModule(module[0]);
			if (cloudModule.getApplication() != null) {
				new Request<Void>() {
					@Override
					protected Void doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
						client.deleteApplication(cloudModule.getDeployedApplicationName());
						return null;
					}
				}.run(monitor);
			}
			// } else if (deltaKind == ADDED | deltaKind == CHANGED) {
			// IModuleResourceDelta[] delta = getPublishedResourceDelta(module);
			// if (delta.length > 0 &&
			// getCloudFoundryServer().getApplication(module[0]).getApplication()
			// != null) {
			// deployOrStartModule(module, false, monitor);
			// }
		}
	}

	/**
	 * Determines if a server supports debug mode. Typically this would be a
	 * cached value for performance reasons, and will not reflect changes
	 */
	public synchronized boolean isServerDebugModeAllowed() {
		return isDebugModeSupported == DebugSupportCheck.SUPPORTED;
	}

	/**
	 * Obtains the debug mode type of the given module. Note that the module
	 * need not be started. It could be stopped, and still have a debug mode
	 * associated with it.
	 * @param module
	 * @param monitor
	 * @return
	 */
	public DebugModeType getDebugModeType(IModule module, IProgressMonitor monitor) {
		try {
			CloudFoundryServer cloudServer = getCloudFoundryServer();
			CloudFoundryApplicationModule cloudModule = cloudServer.getExistingCloudModule(module);

			if (cloudModule == null) {
				CloudFoundryPlugin.logError("No cloud application module found for: " + module.getId()
						+ ". Unable to determine debug mode.");
				return null;
			}

			// Check if a cloud application exists (i.e., it is deployed) before
			// determining if it is deployed in debug mode
			CloudApplication cloudApplication = cloudModule.getApplication();

			if (cloudApplication != null) {
				return DebugModeType.getDebugModeType(cloudApplication.getDebug());
			}

		}
		catch (CoreException e) {
			CloudFoundryPlugin.logError(e);
		}
		return null;
	}

	/**
	 * Given a WTP module, the corresponding CF application module will have its
	 * app instance stats refreshed. As the application module also has a
	 * reference to the actual cloud application, an updated cloud application
	 * will be retrieved as well.
	 * @param module whos application instances and stats should be refreshed
	 * @param monitor
	 * @throws CoreException
	 */
	public void refreshApplicationInstanceStats(IModule module, IProgressMonitor monitor) throws CoreException {
		if (module != null) {
			CloudFoundryApplicationModule appModule = getCloudFoundryServer().getExistingCloudModule(module);

			try {
				// Update the CloudApplication in the cloud module.
				CloudApplication application = getApplication(appModule.getDeployedApplicationName(), monitor);
				appModule.setCloudApplication(application);
			}
			catch (CoreException e) {
				// application is not deployed to server yet
			}

			if (appModule.getApplication() != null) {
				// refresh application stats
				ApplicationStats stats = getApplicationStats(appModule.getDeployedApplicationName(), monitor);
				InstancesInfo info = getInstancesInfo(appModule.getDeployedApplicationName(), monitor);
				appModule.setApplicationStats(stats);
				appModule.setInstancesInfo(info);
			}
			else {
				appModule.setApplicationStats(null);
			}
		}
	}

	public static void validate(String location, String userName, String password, IProgressMonitor monitor)
			throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor);
		progress.beginTask("Connecting", IProgressMonitor.UNKNOWN);
		try {
			CloudFoundryOperations client = createClient(location, userName, password);
			CloudFoundryLoginHandler operationsHandler = new CloudFoundryLoginHandler(client, null);
			operationsHandler.login(progress);
		}
		catch (RestClientException e) {
			throw CloudErrorUtil.toCoreException(e);
		}
		catch (RuntimeException e) {
			// try to guard against IOException in parsing response
			if (e.getCause() instanceof IOException) {
				CloudFoundryPlugin
						.getDefault()
						.getLog()
						.log(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID,
								"Parse error from server response", e.getCause()));
				throw new CoreException(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID,
						"Unable to communicate with server"));
			}
			else {
				throw e;
			}
		}
		finally {
			progress.done();
		}
	}

	public static void register(String location, String userName, String password, IProgressMonitor monitor)
			throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor);
		progress.beginTask("Connecting", IProgressMonitor.UNKNOWN);
		try {
			CloudFoundryOperations client = createClient(location, userName, password);
			client.register(userName, password);
		}
		catch (RestClientException e) {
			throw CloudErrorUtil.toCoreException(e);
		}
		catch (RuntimeException e) {
			// try to guard against IOException in parsing response
			if (e.getCause() instanceof IOException) {
				CloudFoundryPlugin
						.getDefault()
						.getLog()
						.log(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID,
								"Parse error from server response", e.getCause()));
				throw new CoreException(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID,
						"Unable to communicate with server"));
			}
			else {
				throw e;
			}
		}
		finally {
			progress.done();
		}
	}

	/**
	 * Creates a standalone client with no association with a server behaviour.
	 * This is used only for connecting to a Cloud Foundry server for credential
	 * verification verification. To create a client bound to a server
	 * behaviour, it must be done through the server behaviour
	 * @param location
	 * @param userName
	 * @param password
	 * @return
	 * @throws CoreException
	 */
	public static CloudFoundryOperations createClient(String location, String userName, String password)
			throws CoreException {
		return createClient(location, userName, password, null);
	}

	private static CloudFoundryOperations createClient(String location, String userName, String password,
			CloudFoundrySpace cloudSpace) throws CoreException {
		if (password == null) {
			// lost the password, start with an empty one to avoid assertion
			// error
			password = "";
		}
		return createClient(location, new CloudCredentials(userName, password), cloudSpace);
	}

	private static CloudFoundryOperations createClient(String location, CloudCredentials credentials,
			CloudFoundrySpace cloudSpace) throws CoreException {

		URL url;
		try {
			url = new URL(location);
			int port = url.getPort();
			if (port == -1) {
				port = url.getDefaultPort();
			}
			// At this stage, determine if it is a cloud server and account that
			// supports orgs and spaces

			return cloudSpace != null ? CloudFoundryPlugin.getDefault().getCloudFoundryClient(credentials,
					cloudSpace.getSpace(), url) : CloudFoundryPlugin.getDefault().getCloudFoundryClient(credentials,
					url);
		}
		catch (MalformedURLException e) {
			throw new CoreException(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID, NLS.bind(
					"The server url ''{0}'' is invalid: {1}", location, e.getMessage()), e));
		}
	}

	// public static class RequestFactory extends
	// CommonsClientHttpRequestFactory {
	//
	// private HttpClient client;
	//
	// /**
	// * For testing.
	// */
	// public static boolean proxyEnabled = true;
	//
	// public RequestFactory(HttpClient client) {
	// super(client);
	// this.client = client;
	// }
	//
	// public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod)
	// throws IOException {
	// IProxyData[] proxy =
	// CloudFoundryPlugin.getDefault().getProxyService().select(uri);
	// if (proxyEnabled && proxy != null && proxy.length > 0) {
	// client.getHostConfiguration().setProxy(proxy[0].getHost(),
	// proxy[0].getPort());
	// }else {
	// client.getHostConfiguration().setProxyHost(null);
	// }
	// return super.createRequest(uri, httpMethod);
	// }
	//
	// }

	/**
	 * A Request performs a CF request via the cloudfoundry-client-lib API. It
	 * performs various error handling that are generally common to most client
	 * requests, and therefore any client request should be wrapped around a
	 * Request.
	 * <p/>
	 * By default, the set of client calls in the Request is made twice, the
	 * first time it is executed immediate. If it fails due to
	 * unauthorisation/forbidden error, it will attempt a second time. If it
	 * still fails, it will no longer attempt and propagate a client error to
	 * the caller in the form of a CoreException
	 * <p/>
	 * Subtypes can modify this behaviour and add conditions that will result in
	 * further retries aside from unauthorised/forbidden errors given an
	 * exception thrown while invoking a client API.
	 * <p/>
	 * In addition, all requests are performed in a sub monitor, therefore
	 * submonitor operations like creating a new child to track progress worked
	 * should be used.
	 * 
	 * @param <T>
	 * 
	 */
	abstract class Request<T> {

		private final String label;

		public Request() {
			this("");
		}

		public Request(String label) {
			Assert.isNotNull(label);
			this.label = label;
		}

		/**
		 * 
		 * @param monitor
		 * @return result of client operation
		 * @throws CoreException if failure occurred while attempting to execute
		 * the client operation.
		 */
		public T run(IProgressMonitor monitor) throws CoreException {
			CloudFoundryServer cloudServer = getCloudFoundryServer();

			if (cloudServer.getUsername() == null || cloudServer.getUsername().length() == 0
					|| cloudServer.getPassword() == null || cloudServer.getPassword().length() == 0) {
				CloudFoundryPlugin.getCallback().getCredentials(cloudServer);
			}

			Server server = (Server) getServer();
			if (server.getServerState() == IServer.STATE_STOPPED || server.getServerState() == IServer.STATE_STOPPING) {
				server.setServerState(IServer.STATE_STARTING);
			}

			SubMonitor subProgress = SubMonitor.convert(monitor, label, 100);

			T result;
			boolean succeeded = false;
			try {

				CloudFoundryOperations client = getClient(subProgress);

				// Execute the request through a client request handler that
				// handles errors as well as proxy checks, and reattempts the
				// request once
				// if unauthorised/forbidden exception is thrown, and client
				// login is
				// attempted again.
				result = runAsClientRequestCheckConnection(client, cloudServer, subProgress);

				succeeded = true;

				try {
					// At this stage, the client is connected, otherwise the
					// client request would have failed.
					// Now retrieve information that should be done once per
					// connection session,
					// including whether the server supports debug, list of
					// application plans, and domains for the org.
					// Since request succeeded, at this stage determine
					// if the server supports debugging.

					// FIXNS: Disabled for CF 1.5.0 until V2 MCF is released
					// that supports debug.
					// requestAllowDebug(client);
					if (applicationUrlLookup == null) {
						applicationUrlLookup = new CloudApplicationUrlLookup(getCloudFoundryServer());
						applicationUrlLookup.refreshDomains(subProgress);
					}

				}
				catch (RestClientException e) {
					throw CloudErrorUtil.toCoreException(e);
				}

			}
			finally {
				if (!succeeded) {
					if (server.getServerState() == IServer.STATE_STARTING) {
						server.setServerState(IServer.STATE_STOPPED);
					}
				}
				subProgress.done();
			}

			if (server.getServerState() != IServer.STATE_STARTED) {
				server.setServerState(IServer.STATE_STARTED);
			}
			// server.setServerPublishState(IServer.PUBLISH_STATE_NONE);

			return result;
		}

		/**
		 * Attempts to execute the client request by first checking proxy
		 * settings, and if unauthorised/forbidden exceptions thrown the first
		 * time, will attempt to log in. If that succeeds, it will attempt one
		 * more time. Otherwise it will fail and not attempt the request any
		 * further.
		 * @param client
		 * @param cloudServer
		 * @param subProgress
		 * @return
		 * @throws CoreException if attempt to execute failed, even after a
		 * second attempt after a client login.
		 */
		protected T runAsClientRequestCheckConnection(CloudFoundryOperations client, CloudFoundryServer cloudServer,
				SubMonitor subProgress) throws CoreException {
			// Check that a user is logged in and proxy is updated
			String cloudURL = cloudServer.getUrl();
			CloudFoundryLoginHandler handler = new CloudFoundryLoginHandler(client, cloudURL);

			// Always check if proxy settings have changed.
			handler.updateProxyInClient(client);

			try {
				return runAsClientRequest(client, subProgress);
			}
			catch (CoreException ce) {
				CloudFoundryException cfe = ce.getCause() instanceof CloudFoundryException ? (CloudFoundryException) ce
						.getCause() : null;
				if (cfe != null && handler.shouldAttemptClientLogin(cfe)) {
					handler.login(subProgress, 3, ClientRequestOperation.LOGIN_INTERVAL);
					return runAsClientRequest(client, subProgress);
				}
				else {
					throw ce;
				}
			}
		}

		protected T runAsClientRequest(CloudFoundryOperations client, SubMonitor subProgress) throws CoreException {
			return new ClientRequestOperation<T>(client) {

				@Override
				protected T doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
					return Request.this.doRun(client, progress);
				}

			}.run(subProgress);
		}

		protected abstract T doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException;

	}

	/**
	 * 
	 * Request that is aware of potential staging related errors and may attempt
	 * the request again on certain types of staging errors like Staging Not
	 * Finished errors.
	 * <p/>
	 * Because the set of client operations wrapped around this Request may be
	 * attempted again on certain types of errors, it's best to keep the set of
	 * client operations as minimal as possible, to avoid performing client
	 * operations again that had no errors.
	 * 
	 * <p/>
	 * Note that this should only be used around certain types of operations
	 * performed on a app that is already started, like fetching the staging
	 * logs, or app instances stats, as re-attempts on these operations due to
	 * staging related errors (e.g. staging not finished yet) is permissable.
	 * 
	 * <p/>
	 * However, operations not related an application being in a running state
	 * (e.g. creating a service, getting list of all apps), should not use this
	 * request.
	 */
	abstract class StagingAwareRequest<T> extends Request<T> {

		protected final long requestTimeOut;

		public StagingAwareRequest() {
			this("");
		}

		public StagingAwareRequest(String label) {
			this(label, ClientRequestOperation.DEFAULT_CF_CLIENT_REQUEST_TIMEOUT);
		}

		public StagingAwareRequest(String label, long requestTimeOut) {
			super(label);
			this.requestTimeOut = requestTimeOut > 0 ? requestTimeOut
					: ClientRequestOperation.DEFAULT_CF_CLIENT_REQUEST_TIMEOUT;
		}

		protected T runAsClientRequest(CloudFoundryOperations client, SubMonitor subProgress) throws CoreException {
			return new RetryNotFinishedStagingOperation<T>(client, requestTimeOut) {

				@Override
				protected T doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
					return StagingAwareRequest.this.doRun(client, progress);
				}

			}.run(subProgress);
		}

		protected abstract T doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException;

	}

	/**
	 * 
	 * Reattempts the operation if a app in stopped state error is encountered.
	 * 
	 */
	abstract class AppInStoppedStateAwareRequest<T> extends Request<T> {

		protected final long requestTimeOut;

		public AppInStoppedStateAwareRequest() {
			this("");
		}

		public AppInStoppedStateAwareRequest(String label) {
			this.requestTimeOut = ClientRequestOperation.DEFAULT_CF_CLIENT_REQUEST_TIMEOUT;
		}

		protected T runAsClientRequest(CloudFoundryOperations client, SubMonitor subProgress) throws CoreException {
			return new RetryAppInStoppedStateOperation<T>(client, requestTimeOut) {

				@Override
				protected T doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException {
					return AppInStoppedStateAwareRequest.this.doRun(client, progress);
				}

			}.run(subProgress);
		}

		protected abstract T doRun(CloudFoundryOperations client, SubMonitor progress) throws CoreException;

	}

	/**
	 * Deploys an application and or starts it in regular or debug mode. If
	 * deployed in debug mode, an attempt will be made to connect the deployed
	 * application to a debugger
	 * 
	 */
	protected abstract class DeployAction {

		final protected IModule[] modules;

		protected DeployAction(IModule[] modules) {
			this.modules = modules;
		}

		public CloudFoundryApplicationModule run(IProgressMonitor monitor) throws CoreException {

			CloudFoundryApplicationModule appModule = getAndValidateCloudApplicationModule(modules);

			// Disable automatic module refresh until operation completes
			setRefreshInterval(-1);

			try {
				CloudFoundryServer cloudServer = getCloudFoundryServer();
				boolean debug = appModule.getDeploymentInfo().getDeploymentMode() == ApplicationAction.DEBUG;

				performDeployment(appModule, monitor);

				refreshModules(monitor);

				if (debug) {
					new DebugCommandBuilder(modules, cloudServer).getDebugCommand(
							ApplicationAction.CONNECT_TO_DEBUGGER, null).run(monitor);
				}

				// In addition, starting, restarting, and update-restarting
				// a
				// caldecott app should always
				// disconnect existing tunnels.
				if (TunnelBehaviour.isCaldecottApp(appModule.getDeployedApplicationName())) {
					new TunnelBehaviour(cloudServer).stopAndDeleteAllTunnels(monitor);
				}
				return appModule;
			}
			catch (OperationCanceledException e) {
				// ignore so webtools does not show an exception
				((Server) getServer()).setModuleState(modules, IServer.STATE_UNKNOWN);
			}
			finally {
				// Restart the refresh job
				restartRefreshJob(ClientRequestOperation.DEFAULT_INTERVAL);
			}

			return null;
		}

		/**
		 * This performs the primary operation of creating an application and
		 * then pushing the application contents to the server. These are
		 * performed in separate requests via the CF client. If the application
		 * does not exist, it is first created through an initial request. Once
		 * the application is created, or if it already exists, the next step is
		 * to upload (push) the application archive containing the application's
		 * resources. This is performed in a second separate request.
		 * <p/>
		 * To avoid replacing the deployment info in the app module, the
		 * mappings in the app module are NOT updated with newly created
		 * application. It is up to the caller to set the mapping in
		 * {@link CloudFoundryApplicationModule}
		 * @param client
		 * @param appModule valid Cloud module with valid deployment info.
		 * @param monitor
		 * @throws CoreException if error creating the application
		 */
		protected void pushApplication(CloudFoundryOperations client, final CloudFoundryApplicationModule appModule,
				File warFile, ApplicationArchive applicationArchive, IProgressMonitor monitor) throws CoreException {

			String appName = appModule.getDeploymentInfo().getDeploymentName();

			try {
				List<CloudApplication> existingApps = client.getApplications();
				boolean found = false;
				for (CloudApplication existingApp : existingApps) {
					if (existingApp.getName().equals(appName)) {
						found = true;
						break;
					}
				}

				// 1. Create the application if it doesn't already exist
				if (!found) {
					Staging staging = appModule.getDeploymentInfo().getStaging();
					List<String> uris = appModule.getDeploymentInfo().getUris() != null ? appModule.getDeploymentInfo()
							.getUris() : new ArrayList<String>(0);
					List<String> services = appModule.getDeploymentInfo().getServices() != null ? appModule
							.getDeploymentInfo().getServices() : new ArrayList<String>(0);

					if (staging == null) {
						// For v2, a non-null staging is required.
						staging = new Staging();
					}
					client.createApplication(appName, staging, appModule.getDeploymentInfo().getMemory(), uris,
							services);
				}

				// 2. Now push the application content.
				if (warFile != null) {
					client.uploadApplication(appName, warFile);
				}
				else if (applicationArchive != null) {
					// Handle the incremental publish case separately as it
					// requires
					// a partial war file generation of only the changed
					// resources
					// AFTER
					// the server determines the list of missing file names.
					if (applicationArchive instanceof CachingApplicationArchive) {
						final CachingApplicationArchive cachingArchive = (CachingApplicationArchive) applicationArchive;
						client.uploadApplication(appName, cachingArchive, new UploadStatusCallback() {

							public void onProcessMatchedResources(int length) {

							}

							public void onMatchedFileNames(Set<String> matchedFileNames) {
								cachingArchive.generatePartialWarFile(matchedFileNames);
							}

							public void onCheckResources() {

							}
						});

						// Once the application has run, do a clean up of the
						// sha1
						// cache for deleted resources

					}
					else {
						client.uploadApplication(appName, applicationArchive);
					}
				}
				else {
					throw CloudErrorUtil
							.toCoreException(NLS
									.bind("Failed to deploy application {0} since no deployable war or application archive file was generated.",
											appModule.getDeploymentInfo().getDeploymentName()));
				}

				// Verify the application uploaded and exists in the server
				CloudApplication application = getDeployedCloudApplication(client, appName, monitor);

				if (application == null) {
					throw CloudErrorUtil
							.toCoreException("No cloud application obtained from the Cloud Foundry server for :  "
									+ appModule.getDeployedApplicationName()
									+ ". Application may not have deployed correctly to the Cloud Foundry server, or there are connection problems to the server.");
				}

				appModule.setCloudApplication(application);
			}
			catch (IOException e) {
				throw new CoreException(CloudFoundryPlugin.getErrorStatus(NLS.bind(
						"Failed to deploy application {0} due to {1}", appModule.getDeploymentInfo()
								.getDeploymentName(), e.getMessage()), e));
			}

		}

		/**
		 * 
		 * @param appModule to be deployed or started
		 * @param monitor
		 * @throws CoreException if error occurred during deployment or starting
		 * the app, or resolving the updated cloud application from the client.
		 */
		protected abstract void performDeployment(CloudFoundryApplicationModule appModule, IProgressMonitor monitor)
				throws CoreException;
	}

	protected boolean hasChildModules(IModule[] modules) {
		IWebModule webModule = CloudUtil.getWebModule(modules);
		return webModule != null && webModule.getModules() != null && webModule.getModules().length > 0;
	}

	/**
	 * This action is the primary operation for pushing an application to a CF
	 * server. <br/>
	 * Several primary steps are performed when deploying an application:
	 * <p/>
	 * 1. Create an archive file containing the application's resources.
	 * Incremental publishing is may be used here to create an archive
	 * containing only those files that have been changed.
	 * <p/>
	 * 2. Check if the application exists in the server. If not, create it.
	 * <p/>
	 * 3. Once the application is verified to exist, push the archive of the
	 * application to the server.
	 * <p/>
	 * 4. Set local WTP module states to indicate the an application's contents
	 * have been pushed (i.e. "published")
	 * <p/>
	 * 5. Start the application, in either debug or run mode, based on the
	 * application's descriptor, in the server.
	 * <p/>
	 * 6. Set local WTP module states to indicate whether an application has
	 * started, or is stopped if an error occurred while starting it.
	 * <p/>
	 * 7. Invoke callbacks to notify listeners that an application has been
	 * started. This usually performs UI refreshes, which may invoke additional
	 * calls by the listener to fetch application instance stats and other
	 * information.
	 * <p/>
	 * Note that ALL client calls need to be wrapped around a Request operation,
	 * as the Request operation performs additional checks prior to invoking the
	 * call, as well as handles errors.
	 */
	protected class StartOrDeployAction extends RestartAction {

		final protected boolean waitForDeployment;

		public StartOrDeployAction(boolean waitForDeployment, IModule[] modules) {
			super(modules);
			this.waitForDeployment = waitForDeployment;
		}

		protected void performDeployment(CloudFoundryApplicationModule appModule, IProgressMonitor monitor)
				throws CoreException {
			final Server server = (Server) getServer();
			final CloudFoundryServer cloudServer = getCloudFoundryServer();
			final IModule module = modules[0];

			try {

				// Update the local cloud module representing the application
				// first.
				appModule.setErrorStatus(null);

				server.setModuleState(modules, IServer.STATE_STARTING);

				final String deploymentName = appModule.getDeploymentInfo().getDeploymentName();

				// This request does three things:
				// 1. Checks if the application external or mapped to a local
				// project. If mapped to a local project
				// it creates an archive of the application's content
				// 2. If an archive file was created, it pushes the archive
				// file.
				// 3. While pushing the archive file, a check is made to see if
				// the application exists remotely. If not, the application is
				// created in the
				// CF server.

				if (!modules[0].isExternal()) {

					final ApplicationArchive applicationArchive = generateApplicationArchiveFile(
							appModule.getDeploymentInfo(), appModule, modules, server, monitor);
					File warFile = null;
					if (applicationArchive == null) {
						// Create a full war archive
						warFile = CloudUtil.createWarFile(modules, server, monitor);
						if (warFile == null || !warFile.exists()) {
							throw new CoreException(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID,
									"Unable to create war file for application: " + deploymentName));
						}

						CloudFoundryPlugin.trace("War file " + warFile.getName() + " created");
					}
					// Tell webtools the module has been published
					setModulePublishState(modules, IServer.PUBLISH_STATE_NONE);

					// update server publish status
					IModule[] serverModules = server.getModules();
					boolean allSynched = true;
					for (IModule serverModule : serverModules) {
						int modulePublishState = server.getModulePublishState(new IModule[] { serverModule });
						if (modulePublishState == IServer.PUBLISH_STATE_INCREMENTAL
								|| modulePublishState == IServer.PUBLISH_STATE_FULL) {
							allSynched = false;
						}
					}

					if (allSynched) {
						server.setServerPublishState(IServer.PUBLISH_STATE_NONE);
					}

					final File warFileFin = warFile;
					final CloudFoundryApplicationModule appModuleFin = appModule;
					// Now push the application resources to the server
					new Request<Void>("Pushing the application: " + deploymentName) {
						@Override
						protected Void doRun(final CloudFoundryOperations client, SubMonitor progress)
								throws CoreException {

							pushApplication(client, appModuleFin, warFileFin, applicationArchive, progress);

							CloudFoundryPlugin.trace("Application " + deploymentName
									+ " pushed to Cloud Foundry server.");

							cloudServer.tagAsDeployed(module);

							return null;
						}

					}.run(monitor);

				}

				// If reached here it means the application creation and content
				// pushing probably succeeded without errors, therefore attempt
				// to
				// start the application
				super.performDeployment(appModule, monitor);

			}
			catch (CoreException e) {
				appModule.setErrorStatus(e);
				server.setModulePublishState(modules, IServer.PUBLISH_STATE_UNKNOWN);
				throw e;
			}
		}
	}

	/**
	 * 
	 * @param descriptor that contains the application information, and that
	 * also will be updated with an archive containing the application resources
	 * to be deployed to the Cloud Foundry Server
	 * @param cloudModule the Cloud Foundry wrapper around the application
	 * module to be pushed to the server
	 * @param modules list of WTP modules.
	 * @param cloudServer cloud server where app should be pushed to
	 * @param monitor
	 * @throws CoreException if failure occurred while generated an archive file
	 * containing the application's payload
	 */
	protected ApplicationArchive generateApplicationArchiveFile(ApplicationDeploymentInfo deploymentInfo,
			CloudFoundryApplicationModule cloudModule, IModule[] modules, Server server, IProgressMonitor monitor)
			throws CoreException {

		// Perform local operations like building an archive file
		// and payload for the application
		// resources prior to pushing it to the server.

		// If the module is not external (meaning that it is
		// mapped to a local, accessible workspace project),
		// create an
		// archive file containing changes to the
		// application's
		// resources. Use incremental publishing if
		// possible.

		IApplicationDelegate delegate = ApplicationRegistry.getApplicationDelegate(cloudModule.getLocalModule());

		ApplicationArchive archive = null;
		if (delegate != null && delegate.providesApplicationArchive(cloudModule.getLocalModule())) {
			IModuleResource[] resources = getResources(modules);

			try {
				archive = delegate.getApplicationArchive(cloudModule.getLocalModule(), resources);
			}
			catch (CoreException e) {
				// Log the error, but continue anyway to
				// see
				// if generating a .war file will work
				// for
				// this application type
				CloudFoundryPlugin.log(e);
			}
		}

		// If no application archive was provided,then attempt an incremental
		// publish. Incremental publish is only supported for apps without child
		// modules.
		if (archive == null && deploymentInfo.isIncrementalPublish() && !hasChildModules(modules)) {
			// Determine if an incremental publish
			// should
			// occur
			// For the time being support incremental
			// publish
			// only if the app does not have child
			// modules
			// To compute incremental deltas locally,
			// modules must be provided
			// Computes deltas locally before publishing
			// to
			// the server.
			// Potentially more efficient. Should be
			// used
			// only on incremental
			// builds

			archive = getIncrementalPublishArchive(deploymentInfo, modules);
		}
		return archive;

	}

	protected ApplicationArchive getIncrementalPublishArchive(final ApplicationDeploymentInfo deploymentInfo,
			IModule[] modules) {
		IModuleResource[] allResources = getResources(modules);
		IModuleResourceDelta[] deltas = getPublishedResourceDelta(modules);
		List<IModuleResource> changedResources = getChangedResources(deltas);
		ApplicationArchive moduleArchive = new CachingApplicationArchive(Arrays.asList(allResources), changedResources,
				modules[0], deploymentInfo.getDeploymentName());

		return moduleArchive;
	}

	/**
	 * 
	 * Attempts to start an application. It does not create an application, or
	 * incrementally or fully push the application's resources. It simply starts
	 * the application in the server with the application's currently published
	 * resources, regardless of local changes have occurred or not.
	 * 
	 */
	protected class RestartAction extends DeployAction {

		public RestartAction(IModule[] modules) {
			super(modules);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.cloudfoundry.ide.eclipse.internal.server.core.client.
		 * CloudFoundryServerBehaviour
		 * .DeployAction#performDeployment(org.cloudfoundry
		 * .ide.eclipse.internal.
		 * server.core.client.CloudFoundryApplicationModule,
		 * org.eclipse.core.runtime.IProgressMonitor)
		 */
		protected void performDeployment(CloudFoundryApplicationModule appModule, IProgressMonitor monitor)
				throws CoreException {
			final Server server = (Server) getServer();
			final CloudFoundryServer cloudServer = getCloudFoundryServer();
			final CloudFoundryApplicationModule cloudModule = appModule;

			try {
				cloudModule.setErrorStatus(null);

				final String deploymentName = cloudModule.getDeploymentInfo().getDeploymentName();

				server.setModuleState(modules, IServer.STATE_STARTING);

				if (deploymentName == null) {
					server.setModuleState(modules, IServer.STATE_STOPPED);

					throw CloudErrorUtil
							.toCoreException("Unable to start application. Missing application deployment name in application deployment information.");
				}

				if (cloudModule.getDeploymentInfo().getDeploymentMode() != null) {
					// Start the application. Use a regular request rather than
					// a staging-aware request, as any staging errors should not
					// result in a reattempt, unlike other cases (e.g. get the
					// staging
					// logs or refreshing app instance stats after an app has
					// started).

					CloudFoundryPlugin.getCallback().applicationAboutToStart(getCloudFoundryServer(), cloudModule);

					new Request<Void>("Starting application " + deploymentName) {
						@Override
						protected Void doRun(final CloudFoundryOperations client, SubMonitor progress)
								throws CoreException {
							CloudFoundryPlugin.trace("Application " + deploymentName + " starting");

							switch (cloudModule.getDeploymentInfo().getDeploymentMode()) {
							case DEBUG:
								// Only launch in Suspend mode
								client.debugApplication(deploymentName, DebugModeType.SUSPEND.getDebugMode());
								break;
							default:
								client.stopApplication(deploymentName);

								StartingInfo info = client.startApplication(deploymentName);
								if (info != null) {

									cloudModule.setStartingInfo(info);

									// Inform through callback that application
									// has started
									CloudFoundryPlugin.getCallback().applicationStarting(getCloudFoundryServer(),
											cloudModule);
								}

								break;
							}
							return null;
						}
					}.run(monitor);

					// This should be staging aware, in order to reattempt on
					// staging related issues when checking if an app has
					// started or not
					new StagingAwareRequest<Void>("Waiting for application to start: " + deploymentName,
							ClientRequestOperation.DEPLOYMENT_TIMEOUT) {
						@Override
						protected Void doRun(final CloudFoundryOperations client, SubMonitor progress)
								throws CoreException {

							// Now verify that the application did start
							try {
								if (!waitForStart(client, deploymentName, progress)) {
									server.setModuleState(modules, IServer.STATE_STOPPED);

									throw new CoreException(new Status(IStatus.ERROR, CloudFoundryPlugin.PLUGIN_ID,
											NLS.bind("Starting of {0} timed out",
													cloudModule.getDeployedApplicationName())));
								}
							}
							catch (InterruptedException e) {
								server.setModuleState(modules, IServer.STATE_STOPPED);
								throw new OperationCanceledException();
							}

							server.setModuleState(modules, IServer.STATE_STARTED);

							CloudFoundryPlugin.trace("Application " + deploymentName + " started");

							CloudFoundryPlugin.getCallback().applicationStarted(getCloudFoundryServer(), cloudModule);

							return null;
						}
					}.run(monitor);
				}
				else {
					// Missing a deployment mode is acceptable, as the
					// user may have elected
					// to push the application but not start it.
					server.setModuleState(modules, IServer.STATE_STOPPED);
				}

			}
			catch (CoreException e) {
				appModule.setErrorStatus(e);
				server.setModulePublishState(modules, IServer.PUBLISH_STATE_UNKNOWN);
				throw e;
			}
		}

	}

	abstract class FileRequest<T> extends StagingAwareRequest<T> {

		FileRequest() {
			super("Retrieving file");
		}

		FileRequest(String label) {
			super(label, ClientRequestOperation.SHORT_INTERVAL);
		}

	}

}