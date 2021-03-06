/*-
 * Copyright © 2014 Diamond Light Source Ltd.
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

package uk.ac.diamond.daq.tomography.datacollection.ui.views;

import org.eclipse.jface.action.IToolBarManager;

import uk.ac.diamond.daq.tomography.datacollection.ui.adviewer.ADViewerConstants;
import uk.ac.gda.epics.adviewer.views.ADActionUtils;
import uk.ac.gda.epics.adviewer.views.TwoDArrayView;

public class DataCollectionPCOArrayView extends TwoDArrayView {

	public static final String Id = "uk.ac.diamond.daq.tomography.datacollection.ui.views.DataCollectionPCOArrayView";

	public DataCollectionPCOArrayView() {
		super(ADViewerConstants.AD_CONTROLLER_SERVICE_NAME);
	}

	@Override
	protected void createShowViewAction() {
		final IToolBarManager toolBarManager = getViewSite().getActionBars().getToolBarManager();
		toolBarManager.add(ADActionUtils.addShowViewAction("Show Stats", DataCollectionPCOHistogramView.ID, null, "Show stats view for selected camera",
				uk.ac.gda.epics.adviewer.Activator.getHistogramViewImage()));
		toolBarManager.add(ADActionUtils.addShowViewAction("Show MPeg", DataCollectionMJPegView.ID, null, "Show MPeg view for selected camera",
				uk.ac.gda.epics.adviewer.Activator.getMJPegViewImage()));
	}
}
