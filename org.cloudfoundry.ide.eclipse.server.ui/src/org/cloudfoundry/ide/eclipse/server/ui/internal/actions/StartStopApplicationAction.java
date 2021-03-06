/*******************************************************************************
 * Copyright (c) 2012, 2014 Pivotal Software, Inc. 
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, 
 * Version 2.0 (the "License�); you may not use this file except in compliance 
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *  
 *  Contributors:
 *     Pivotal Software, Inc. - initial API and implementation
 ********************************************************************************/
package org.cloudfoundry.ide.eclipse.server.ui.internal.actions;

import org.cloudfoundry.ide.eclipse.server.core.internal.ApplicationAction;
import org.cloudfoundry.ide.eclipse.server.core.internal.client.CloudFoundryApplicationModule;
import org.cloudfoundry.ide.eclipse.server.core.internal.client.CloudFoundryServerBehaviour;
import org.cloudfoundry.ide.eclipse.server.core.internal.client.ICloudFoundryOperation;
import org.cloudfoundry.ide.eclipse.server.ui.internal.editor.CloudFoundryApplicationsEditorPage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * @author Terry Denney
 * @author Steffen Pingel
 * @author Christian Dupuis
 */
public class StartStopApplicationAction extends CloudFoundryEditorAction {

	private final ApplicationAction action;

	private final CloudFoundryApplicationModule application;

	private final CloudFoundryServerBehaviour serverBehaviour;

	public StartStopApplicationAction(CloudFoundryApplicationsEditorPage editorPage, ApplicationAction action,
			CloudFoundryApplicationModule application, CloudFoundryServerBehaviour serverBehaviour) {
		super(editorPage, RefreshArea.DETAIL);
		this.action = action;
		this.application = application;
		this.serverBehaviour = serverBehaviour;
	}

	@Override
	public String getJobName() {
		StringBuilder jobName = new StringBuilder();
		switch (action) {
		case START:
			jobName.append("Starting"); //$NON-NLS-1$
			break;
		case STOP:
			jobName.append("Stopping"); //$NON-NLS-1$
			break;
		case RESTART:
			jobName.append("Restarting"); //$NON-NLS-1$
			break;
		case UPDATE_RESTART:
			jobName.append("Update and Restarting"); //$NON-NLS-1$
			break;
		}

		jobName.append(" application " + application.getDeployedApplicationName()); //$NON-NLS-1$
		return jobName.toString();
	}

	public ICloudFoundryOperation getOperation(IProgressMonitor monitor) throws CoreException {
		return serverBehaviour.getApplicationOperation(application, action);
	}

}
