/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.events.listeners.upgrade;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.EagerSingleton;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.bootstrap.DistributeRepositoriesStructuredOutput;
import org.apache.ambari.server.controller.RootServiceResponseFactory.Services;
import org.apache.ambari.server.events.ActionFinalReportReceivedEvent;
import org.apache.ambari.server.events.AlertReceivedEvent;
import org.apache.ambari.server.events.AlertStateChangeEvent;
import org.apache.ambari.server.events.publishers.AlertEventPublisher;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.orm.dao.AlertDefinitionDAO;
import org.apache.ambari.server.orm.dao.AlertsDAO;
import org.apache.ambari.server.orm.dao.ClusterVersionDAO;
import org.apache.ambari.server.orm.dao.HostVersionDAO;
import org.apache.ambari.server.orm.entities.AlertCurrentEntity;
import org.apache.ambari.server.orm.entities.AlertDefinitionEntity;
import org.apache.ambari.server.orm.entities.AlertHistoryEntity;
import org.apache.ambari.server.orm.entities.HostVersionEntity;
import org.apache.ambari.server.state.Alert;
import org.apache.ambari.server.state.AlertState;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.MaintenanceState;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.utils.StageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link org.apache.ambari.server.events.listeners.upgrade.DistributeRepositoriesActionListener} class
 * handles {@link org.apache.ambari.server.events.ActionFinalReportReceivedEvent}
 * for "Distribute repositories/install packages" action.
 * It processes command reports and and updates host stack version state acordingly.
 */
@Singleton
@EagerSingleton
public class DistributeRepositoriesActionListener {
  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(DistributeRepositoriesActionListener.class);
  public static final String INSTALL_PACKAGES = "install_packages";

  @Inject
  private Provider<HostVersionDAO> hostVersionDAO;

  @Inject
  private Provider<Clusters> clusters;

  @Inject
  private Provider<ClusterVersionDAO> clusterVersionDAO;

  private AmbariEventPublisher publisher;


  /**
   * Constructor.
   *
   * @param publisher
   */
  @Inject
  public DistributeRepositoriesActionListener(AmbariEventPublisher publisher) {
    this.publisher = publisher;
    publisher.register(this);
  }

  @Subscribe
  // @AllowConcurrentEvents //TODO: is it thread safe?
  public void onActionFinished(ActionFinalReportReceivedEvent event) {
    // Check if it is "Distribute repositories/install packages" action.
    if (! event.getRole().equals(INSTALL_PACKAGES)) {
      return;
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug(event.toString());
    }

    RepositoryVersionState newHostState = RepositoryVersionState.INSTALL_FAILED;
    Long clusterId = event.getClusterId();
    if (clusterId == null) {
      LOG.error("Distribute Repositories expected a cluster Id for host " + event.getHostname());
      return;
    }


    String repositoryVersion = null;
    
    if (event.getCommandReport() == null) {
      LOG.error("Command report is null, marking action as INSTALL_FAILED");
    } else {
      // Parse structured output
      try {
        DistributeRepositoriesStructuredOutput structuredOutput = StageUtils.getGson().fromJson(
                event.getCommandReport().getStructuredOut(),
                DistributeRepositoriesStructuredOutput.class);
        if (event.getCommandReport().getStatus().equals(HostRoleStatus.COMPLETED.toString())) {
          newHostState = RepositoryVersionState.INSTALLED;
        }
        repositoryVersion = structuredOutput.getInstalledRepositoryVersion();
      } catch (JsonSyntaxException e) {
        LOG.error("Can not parse structured output %s", e);
      }
    }

    if (repositoryVersion != null) {
      List<HostVersionEntity> hostVersions = hostVersionDAO.get().findByHost(event.getHostname());
      HostVersionEntity foundHostVersion = null;

      for (HostVersionEntity hostVersion : hostVersions) {
        if (hostVersion.getRepositoryVersion().getVersion().equals(repositoryVersion)) {
          foundHostVersion = hostVersion;
          break;
        }
      }

      if (foundHostVersion != null && foundHostVersion.getState() == RepositoryVersionState.INSTALLING) {
        foundHostVersion.setState(newHostState);

        // Update state of a cluster stack version
        try {
          Cluster cluster = clusters.get().getClusterById(clusterId);
          cluster.recalculateClusterVersionState(foundHostVersion.getRepositoryVersion().getVersion());
        } catch (AmbariException e) {
          LOG.error("Cannot get cluster with Id " + clusterId.toString(), e);
        }
      }
    }
  }
}
