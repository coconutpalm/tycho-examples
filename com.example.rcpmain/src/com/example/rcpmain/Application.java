package com.example.rcpmain;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.IProvisioningAgentProvider;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.operations.ProvisioningJob;
import org.eclipse.equinox.p2.operations.ProvisioningSession;
import org.eclipse.equinox.p2.operations.UpdateOperation;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.ServiceReference;

/**
 * This class controls all aspects of the application's execution
 */
public class Application implements IApplication {

	private static final String JUSTUPDATED = "justUpdated";

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.app.IApplication#start(org.eclipse.equinox.app.IApplicationContext)
	 */
	public Object start(IApplicationContext context) throws Exception {
		Display display = PlatformUI.createDisplay();
		try {
			if (tryToUpdateApplication(display)) {
				return IApplication.EXIT_RESTART;
			}
			int returnCode = PlatformUI.createAndRunWorkbench(display, new ApplicationWorkbenchAdvisor());
			if (returnCode == PlatformUI.RETURN_RESTART) {
				return IApplication.EXIT_RESTART;
			}
			return IApplication.EXIT_OK;
		} finally {
			display.dispose();
		}
	}

	private boolean tryToUpdateApplication(Display display) throws ProvisionException {
		ServiceReference serviceReference = Activator.bundleContext.getServiceReference(IProvisioningAgentProvider.SERVICE_NAME);
		if (serviceReference == null) {
			log(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
			"No service reference found.  This application is not set up for updates."));
			return false;
		}
		IProvisioningAgentProvider agentProvider = (IProvisioningAgentProvider) Activator.bundleContext.getService(serviceReference);
		final IProvisioningAgent agent = agentProvider.createAgent(null);	// The URI here is the site?  "null" means, use the running system.

		try {
			if (agent == null) {
				log(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
						"No provisioning agent found.  This application is not set up for updates."));
				return false;
			}
			
			// XXX if we're restarting after updating, don't try to update again
			final IPreferenceStore prefStore = Activator.getDefault()
					.getPreferenceStore();
			if (prefStore.getBoolean(JUSTUPDATED)) {
				prefStore.setValue(JUSTUPDATED, false);
				return false;
			}
	
			final boolean[] restart = new boolean[] { false };
			IRunnableWithProgress runnable = new IRunnableWithProgress() {
				public void run(IProgressMonitor monitor)
						throws InvocationTargetException, InterruptedException {
					IStatus updateStatus = checkForUpdates(agent, monitor);
					if (updateStatus.getCode() == UpdateOperation.STATUS_NOTHING_TO_UPDATE) {
						PlatformUI.getWorkbench().getDisplay()
								.asyncExec(new Runnable() {
									public void run() {
										MessageDialog.openInformation(null,
												"Updates", "No updates were found");
									}
								});
					} else if (updateStatus.getSeverity() != IStatus.ERROR) {
						prefStore.setValue(JUSTUPDATED, true);
						restart[0] = true;
					} else {
						log(updateStatus);
					}
				}
			};
			try {
				new ProgressMonitorDialog(null).run(true, true, runnable);
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
			}
			return restart[0];
		} finally {
			agent.stop();
		}
	}

	protected IStatus checkForUpdates(IProvisioningAgent agent,
			IProgressMonitor monitor) {
		// XXX Check for updates to this application and return a status.
		ProvisioningSession session = new ProvisioningSession(agent);
		// the default update operation looks for updates to the currently
		// running profile, using the default profile root marker. To change
		// which installable units are being updated, use the more detailed
		// constructors.
		UpdateOperation operation = new UpdateOperation(session);   // Also provides a second arg with IInstallableUnits
																	// similar to our FVI class
		
		SubMonitor sub = SubMonitor.convert(monitor,
				"Checking for application updates...", 200);
		IStatus status = operation.resolveModal(sub.newChild(100));
		if (status.getCode() == UpdateOperation.STATUS_NOTHING_TO_UPDATE) {
			return status;
		}
		if (status.getSeverity() == IStatus.CANCEL)
			throw new OperationCanceledException();

		if (status.getSeverity() != IStatus.ERROR) {
			// More complex status handling might include showing the user what
			// updates
			// are available if there are multiples, differentiating patches vs.
			// updates, etc.
			// In this example, we simply update as suggested by the operation.
			ProvisioningJob job = operation.getProvisioningJob(null);
			status = job.runModal(sub.newChild(100));
			if (status.getSeverity() == IStatus.CANCEL)
				throw new OperationCanceledException();
		}
		return status;
	}

	private void log(IStatus status) {
		Activator.getDefault().getLog().log(status);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.app.IApplication#stop()
	 */
	public void stop() {
		final IWorkbench workbench = PlatformUI.getWorkbench();
		if (workbench == null)
			return;
		final Display display = workbench.getDisplay();
		display.syncExec(new Runnable() {
			public void run() {
				if (!display.isDisposed())
					workbench.close();
			}
		});
	}
}
