/*******************************************************************************
 * Copyright (c) 2013 GoPivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     GoPivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.cloudfoundry.ide.eclipse.internal.server.ui.wizards;

import java.util.ArrayList;
import java.util.List;

import org.cloudfoundry.client.lib.domain.DeploymentInfo;
import org.cloudfoundry.ide.eclipse.internal.server.core.ApplicationAction;
import org.cloudfoundry.ide.eclipse.internal.server.core.ApplicationInfo;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudApplicationURL;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudApplicationUrlLookup;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryApplicationModule;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryServer;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudFoundryServerBehaviour;
import org.cloudfoundry.ide.eclipse.internal.server.core.CloudUtil;
import org.cloudfoundry.ide.eclipse.internal.server.core.application.IApplicationDelegate;

/**
 * 
 * This contains a reference to the core-level application delegate, which
 * contains API to push an application to a CF server. Since a wizard delegate
 * does NOT require a map to a core level application delegate, the link between
 * the two is not pushed up to the parent.
 */
public abstract class ApplicationWizardDelegate implements IApplicationWizardDelegate {

	private CloudApplicationUrlLookup urlLookup;

	private IApplicationDelegate appDelegate;

	public void setApplicationDelegate(IApplicationDelegate appDelegate) {
		this.appDelegate = appDelegate;
	}

	/**
	 * Corresponding core level application delegate that contains API for
	 * pushing an app to a CF server. This may be null, as a wizard delegate may
	 * not be mapped to an app delegate (in the event it uses a default app
	 * delegate from the CF Application framework) .
	 * @return Corresponding Application delegate, if it exists, or null.
	 */
	public IApplicationDelegate getApplicationDelegate() {
		return appDelegate;
	}

	/*
	 * FIXNS: Note that a very similar logic is also present in corresponding
	 * core level IApplicationDelegate implementation for Java web. Use one
	 * descriptor for both the wizard and the core, instead of two separate
	 * ones, to avoid duplication.
	 */
	public boolean isValid(ApplicationWizardDescriptor applicationDescriptor) {

		if (applicationDescriptor == null) {
			return false;
		}

		ApplicationInfo info = applicationDescriptor.getApplicationInfo();
		if (info == null || info.getAppName() == null) {
			return false;
		}

		DeploymentInfo deploymentInfo = applicationDescriptor.getDeploymentInfo();

		return deploymentInfo != null && deploymentInfo.getDeploymentName() != null && deploymentInfo.getMemory() > 0;

	}

	public void initialiseWizardDescriptor(ApplicationWizardDescriptor applicationDescriptor,
			CloudFoundryServer cloudServer, CloudFoundryApplicationModule module) {

		urlLookup = CloudApplicationUrlLookup.getCurrentLookup(cloudServer);

		DeploymentInfo deploymentInfo = new DeploymentInfo();

		applicationDescriptor.setDeploymentInfo(deploymentInfo);

		DeploymentInfo lastDeploymentInfo = (module != null) ? module.getLastDeploymentInfo() : null;

		String deploymentName = null;

		if (lastDeploymentInfo != null && lastDeploymentInfo.getDeploymentName() != null) {
			deploymentName = lastDeploymentInfo.getDeploymentName();
		}
		else if (module != null) {
			deploymentName = module.getDeploymentName();
		}

		deploymentInfo.setDeploymentName(deploymentName);

		deploymentInfo.setMemory(CloudUtil.DEFAULT_MEMORY);

		String appName = null;

		ApplicationInfo lastApplicationInfo = null;

		if (module != null) {
			lastApplicationInfo = module.getLastApplicationInfo();
		}

		if (lastApplicationInfo != null && lastApplicationInfo.getAppName() != null) {
			appName = lastApplicationInfo.getAppName();
		}
		else if (module != null) {
			appName = module.getDeploymentName();
		}

		if (appName != null) {
			ApplicationInfo appInfo = new ApplicationInfo(appName);
			applicationDescriptor.setApplicationInfo(appInfo);
		}

		applicationDescriptor.setStartDeploymentMode(ApplicationAction.START);

		if (getApplicationDelegate() == null || getApplicationDelegate().requiresURL()) {
			String url = getDefaultURL(lastDeploymentInfo, deploymentName);
			if (url != null) {
				List<String> urls = new ArrayList<String>();
				urls.add(url);
				applicationDescriptor.getDeploymentInfo().setUris(urls);
			}
		}
	}

	protected String getDefaultURL(DeploymentInfo previousInfo, String deploymentName) {

		String url = previousInfo != null && previousInfo.getUris() != null && !previousInfo.getUris().isEmpty() ? previousInfo
				.getUris().get(0) : null;

		if (url == null && urlLookup != null) {
			CloudApplicationURL appURL = urlLookup.getDefaultApplicationURL(deploymentName);
			if (appURL != null) {
				url = appURL.getUrl();
			}
		
		}

		return url;
	}

	/**
	 * @see CloudFoundryServerBehaviour#getApplicationUrlLookup()
	 */
	public CloudApplicationUrlLookup getApplicationUrlLookup() {
		return urlLookup;
	}

}