/*
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

package org.apache.ivory.converter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.ivory.IvoryException;
import org.apache.ivory.Tag;
import org.apache.ivory.entity.ClusterHelper;
import org.apache.ivory.entity.EntityUtil;
import org.apache.ivory.entity.FeedHelper;
import org.apache.ivory.entity.store.ConfigurationStore;
import org.apache.ivory.entity.v0.EntityType;
import org.apache.ivory.entity.v0.Frequency;
import org.apache.ivory.entity.v0.Frequency.TimeUnit;
import org.apache.ivory.entity.v0.SchemaHelper;
import org.apache.ivory.entity.v0.cluster.Cluster;
import org.apache.ivory.entity.v0.feed.ClusterType;
import org.apache.ivory.entity.v0.feed.Feed;
import org.apache.ivory.entity.v0.feed.LocationType;
import org.apache.ivory.entity.v0.feed.Property;
import org.apache.ivory.expression.ExpressionHelper;
import org.apache.ivory.messaging.EntityInstanceMessage.ARG;
import org.apache.ivory.messaging.EntityInstanceMessage.EntityOps;
import org.apache.ivory.oozie.coordinator.ACTION;
import org.apache.ivory.oozie.coordinator.COORDINATORAPP;
import org.apache.ivory.oozie.coordinator.SYNCDATASET;
import org.apache.ivory.oozie.coordinator.WORKFLOW;
import org.apache.ivory.oozie.workflow.WORKFLOWAPP;
import org.apache.log4j.Logger;

public class OozieFeedMapper extends AbstractOozieEntityMapper<Feed> {

    private static Logger LOG = Logger.getLogger(OozieFeedMapper.class);

    private static final int THIRTY_MINUTES = 30 * 60 * 1000;

    private static final String RETENTION_WF_TEMPLATE = "/config/workflow/retention-workflow.xml";
    private static final String REPLICATION_COORD_TEMPLATE = "/config/coordinator/replication-coordinator.xml";
    private static final String REPLICATION_WF_TEMPLATE = "/config/workflow/replication-workflow.xml";
    
    private static final String FEED_PATH_SEP="#";
	private static final String TIMEOUT = "timeout";
	private static final String PARALLEL = "parallel";

    public OozieFeedMapper(Feed feed) {
        super(feed);
    }

    @Override
    protected List<COORDINATORAPP> getCoordinators(Cluster cluster, Path bundlePath) throws IvoryException {
        List<COORDINATORAPP> coords = new ArrayList<COORDINATORAPP>();
        COORDINATORAPP retentionCoord = getRetentionCoordinator(cluster, bundlePath);
        if (retentionCoord != null) {
            coords.add(retentionCoord);
        }
        List<COORDINATORAPP> replicationCoords = getReplicationCoordinators(cluster, bundlePath);
        coords.addAll(replicationCoords);
        return coords;
    }

    private COORDINATORAPP getRetentionCoordinator(Cluster cluster, Path bundlePath) throws IvoryException {

        Feed feed = getEntity();
        org.apache.ivory.entity.v0.feed.Cluster feedCluster = FeedHelper.getCluster(feed, cluster.getName());

        if (feedCluster.getValidity().getEnd().before(new Date())) {
            LOG.warn("Feed Retention is not applicable as Feed's end time for cluster " + cluster.getName() + " is not in the future");
            return null;
        }
        COORDINATORAPP retentionApp = new COORDINATORAPP();
        String coordName = EntityUtil.getWorkflowName(Tag.RETENTION, feed).toString();
        retentionApp.setName(coordName);
        retentionApp.setEnd(SchemaHelper.formatDateUTC(feedCluster.getValidity().getEnd()));
        retentionApp.setStart(SchemaHelper.formatDateUTC(new Date()));
        retentionApp.setTimezone(feed.getTimezone().getID());
        TimeUnit timeUnit = feed.getFrequency().getTimeUnit();
        if (timeUnit == TimeUnit.hours || timeUnit == TimeUnit.minutes) {
            retentionApp.setFrequency("${coord:hours(6)}");
        } else {
            retentionApp.setFrequency("${coord:days(1)}");
        }

        Path wfPath = getCoordPath(bundlePath, coordName);
        retentionApp.setAction(getRetentionWorkflowAction(cluster, wfPath, coordName));
        return retentionApp;
    }

    private ACTION getRetentionWorkflowAction(Cluster cluster, Path wfPath, String wfName) throws IvoryException {
        Feed feed = getEntity();
        ACTION retentionAction = new ACTION();
        WORKFLOW retentionWorkflow = new WORKFLOW();
        try {
            //
            WORKFLOWAPP retWfApp = createRetentionWorkflow(cluster);
            retWfApp.setName(wfName);
            marshal(cluster, retWfApp, wfPath);
            retentionWorkflow.setAppPath(getStoragePath(wfPath.toString()));

            Map<String, String> props = createCoordDefaultConfiguration(cluster, wfPath, wfName);

            org.apache.ivory.entity.v0.feed.Cluster feedCluster = FeedHelper.getCluster(feed, cluster.getName());
            String feedPathMask = getLocationURI(cluster, feed,LocationType.DATA);
			String metaPathMask = getLocationURI(cluster, feed, LocationType.META);
            String statsPathMask = getLocationURI(cluster, feed, LocationType.STATS);
            String tmpPathMask = getLocationURI(cluster, feed, LocationType.TMP);

            StringBuilder feedBasePaths = new StringBuilder(feedPathMask);
            if(metaPathMask!=null){
            	feedBasePaths.append(FEED_PATH_SEP).append(metaPathMask);
            }
            if(statsPathMask!=null){
            	feedBasePaths.append(FEED_PATH_SEP).append(statsPathMask);
            }
            if(tmpPathMask!=null){
            	feedBasePaths.append(FEED_PATH_SEP).append(tmpPathMask);
            }

            props.put("feedDataPath", feedBasePaths.toString().replaceAll("\\$\\{", "\\?\\{"));
            props.put("timeZone", feed.getTimezone().getID());
            props.put("frequency", feed.getFrequency().getTimeUnit().name());
            props.put("limit", feedCluster.getRetention().getLimit().toString());
            props.put(ARG.operation.getPropName(), EntityOps.DELETE.name());
            props.put(ARG.feedNames.getPropName(), feed.getName());
            props.put(ARG.feedInstancePaths.getPropName(), "IGNORE");

            retentionWorkflow.setConfiguration(getCoordConfig(props));
            retentionAction.setWorkflow(retentionWorkflow);
            return retentionAction;
        } catch (Exception e) {
            throw new IvoryException("Unable to create parent/retention workflow", e);
        }
    }

    private List<COORDINATORAPP> getReplicationCoordinators(Cluster targetCluster, Path bundlePath) throws IvoryException {
        Feed feed = getEntity();
        List<COORDINATORAPP> replicationCoords = new ArrayList<COORDINATORAPP>();
        
        if (FeedHelper.getCluster(feed, targetCluster.getName()).getType() == ClusterType.TARGET) {
            String coordName = EntityUtil.getWorkflowName(Tag.REPLICATION, feed).toString();
            Path basePath = getCoordPath(bundlePath, coordName);
            createReplicatonWorkflow(targetCluster, basePath, coordName);
            
            for (org.apache.ivory.entity.v0.feed.Cluster feedCluster : feed.getClusters().getClusters()) {
                if (feedCluster.getType() == ClusterType.SOURCE) {
                    COORDINATORAPP coord = createAndGetCoord(feed,
                            (Cluster) ConfigurationStore.get().get(EntityType.CLUSTER, feedCluster.getName()), targetCluster,
                            bundlePath);
					if (coord != null) {
						replicationCoords.add(coord);
					}
                }
            }

        }
        return replicationCoords;
    }

    private COORDINATORAPP createAndGetCoord(Feed feed, Cluster srcCluster, Cluster trgCluster, Path bundlePath)
            throws IvoryException {
        COORDINATORAPP replicationCoord;
        String coordName;
        try {
            replicationCoord = getCoordinatorTemplate(REPLICATION_COORD_TEMPLATE);
            coordName = EntityUtil.getWorkflowName(Tag.REPLICATION, Arrays.asList(srcCluster.getName()), feed).toString();
            replicationCoord.setName(coordName);
            replicationCoord.setFrequency("${coord:" + feed.getFrequency().toString() + "}");

            long frequency_ms = ExpressionHelper.get().
                    evaluate(feed.getFrequency().toString(), Long.class);
            long timeout_ms = frequency_ms * 6;            
            Map<String,String> props = getEntityProperties();
            String timeout = props.get(TIMEOUT);
            if(timeout!=null){
            	try{
            		timeout_ms= ExpressionHelper.get().
            				evaluate(timeout, Long.class);
            	}catch (Exception ignore) {
            		LOG.error("Unable to evaluate timeout:", ignore);
            	}
            }
            if (timeout_ms < THIRTY_MINUTES) timeout_ms = THIRTY_MINUTES;
            
            String parallelProp = props.get(PARALLEL);
			int parallel = 1;
			if (parallelProp != null) {
				try {
					parallel = Integer.parseInt(parallelProp);
				} catch (NumberFormatException ignore) {
					LOG.error("Unable to parse parallel:", ignore);
				}
			}
			
            replicationCoord.getControls().setTimeout(String.valueOf(timeout_ms / (1000 * 60)));
            replicationCoord.getControls().setThrottle(String.valueOf(timeout_ms / frequency_ms * 2));
            replicationCoord.getControls().setConcurrency(String.valueOf(parallel));
            
			Frequency replicationDelay = FeedHelper.getCluster(feed,
					srcCluster.getName()).getDelay();
			long delay_ms=0;
			if (replicationDelay != null) {
				delay_ms = ExpressionHelper.get().evaluate(
						replicationDelay.toString(), Long.class);
				long delay_mins = -1 * delay_ms / (1000 * 60);
				String elExp = "${now(0," + delay_mins + ")}";
				replicationCoord.getInputEvents().getDataIn().get(0)
						.getInstance().set(0, elExp);
				replicationCoord.getOutputEvents().getDataOut().get(0)
						.setInstance(elExp);
			}

            Date srcStartDate = FeedHelper.getCluster(feed, srcCluster.getName()).getValidity().getStart();
            srcStartDate=new Date(srcStartDate.getTime()+delay_ms);
            Date srcEndDate = FeedHelper.getCluster(feed, srcCluster.getName()).getValidity().getEnd();
            Date trgStartDate = FeedHelper.getCluster(feed, trgCluster.getName()).getValidity().getStart();
            trgStartDate=new Date(trgStartDate.getTime()+delay_ms);
            Date trgEndDate = FeedHelper.getCluster(feed, trgCluster.getName()).getValidity().getEnd();
			if (srcStartDate.after(trgEndDate)
					|| trgStartDate.after(srcEndDate)) {
				LOG.warn("Not creating replication coordinator, as the source cluster:"
						+ srcCluster.getName()
						+ " and target cluster: "
						+ trgCluster.getName()
						+ " do not have overlapping dates");
				return null;
			}
            replicationCoord.setStart(srcStartDate.after(trgStartDate) ? SchemaHelper.formatDateUTC(srcStartDate) : SchemaHelper
                    .formatDateUTC(trgStartDate));
            replicationCoord.setEnd(srcEndDate.before(trgEndDate) ? SchemaHelper.formatDateUTC(srcEndDate) : SchemaHelper
                    .formatDateUTC(trgEndDate));
            replicationCoord.setTimezone(feed.getTimezone().getID());
            SYNCDATASET inputDataset = (SYNCDATASET) replicationCoord.getDatasets().getDatasetOrAsyncDataset().get(0);
            SYNCDATASET outputDataset = (SYNCDATASET) replicationCoord.getDatasets().getDatasetOrAsyncDataset().get(1);

			inputDataset.setUriTemplate(new Path(ClusterHelper
					.getStorageUrl(srcCluster), FeedHelper.getLocation(feed,
					LocationType.DATA,srcCluster.getName()).getPath()).toString());
			outputDataset.setUriTemplate(getStoragePath(FeedHelper.getLocation(
					feed, LocationType.DATA, trgCluster.getName()).getPath()));
            setDatasetValues(inputDataset, feed, srcCluster);
            setDatasetValues(outputDataset, feed, srcCluster);
            if (feed.getAvailabilityFlag() == null) {
                inputDataset.setDoneFlag("");
            } else {
                inputDataset.setDoneFlag(feed.getAvailabilityFlag());
            }

        } catch (IvoryException e) {
            throw new IvoryException("Cannot unmarshall replication coordinator template", e);
        }

        Path wfPath = getCoordPath(bundlePath, coordName);
        replicationCoord.setAction(getReplicationWorkflowAction(srcCluster, trgCluster, wfPath, coordName));
        return replicationCoord;
    }

    private void setDatasetValues(SYNCDATASET dataset, Feed feed, Cluster cluster) {
        dataset.setInitialInstance(SchemaHelper.formatDateUTC(FeedHelper.getCluster(feed, cluster.getName()).getValidity().getStart()));
        dataset.setTimezone(feed.getTimezone().getID());
        dataset.setFrequency("${coord:" + feed.getFrequency().toString() + "}");
    }

    private ACTION getReplicationWorkflowAction(Cluster srcCluster, Cluster trgCluster, Path wfPath, String wfName) throws IvoryException {
        ACTION replicationAction = new ACTION();
        WORKFLOW replicationWF = new WORKFLOW();
        try {
            replicationWF.setAppPath(getStoragePath(wfPath.toString()));
            Feed feed = getEntity();

            String srcPart = FeedHelper.normalizePartitionExpression(FeedHelper.getCluster(feed, srcCluster.getName()).getPartition());
            srcPart = FeedHelper.evaluateClusterExp(srcCluster, srcPart);
            String targetPart = FeedHelper.normalizePartitionExpression(FeedHelper.getCluster(feed, trgCluster.getName()).getPartition());
            targetPart = FeedHelper.evaluateClusterExp(trgCluster, targetPart);
            
            StringBuilder pathsWithPartitions = new StringBuilder();
            pathsWithPartitions.append("${coord:dataIn('input')}/").append(FeedHelper.normalizePartitionExpression(srcPart, targetPart));

            Map<String, String> props = createCoordDefaultConfiguration(trgCluster, wfPath, wfName);
            props.put("srcClusterName", srcCluster.getName());
            props.put("srcClusterColo", srcCluster.getColo());
            props.put(ARG.feedNames.getPropName(), feed.getName());
            props.put(ARG.feedInstancePaths.getPropName(), pathsWithPartitions.toString());
            String parts = pathsWithPartitions.toString().replaceAll("//+", "/");
            parts = StringUtils.stripEnd(parts, "/");
            props.put("sourceRelativePaths", parts);
            props.put("distcpSourcePaths", "${coord:dataIn('input')}");
            props.put("distcpTargetPaths", "${coord:dataOut('output')}");
            props.put("ivoryInPaths", pathsWithPartitions.toString());
            props.put("ivoryInputFeeds", feed.getName());
            replicationWF.setConfiguration(getCoordConfig(props));
            replicationAction.setWorkflow(replicationWF);
        } catch (Exception e) {
            throw new IvoryException("Unable to create replication workflow", e);
        }
        return replicationAction;

    }

    private void createReplicatonWorkflow(Cluster cluster, Path wfPath, String wfName) throws IvoryException {
        WORKFLOWAPP repWFapp = getWorkflowTemplate(REPLICATION_WF_TEMPLATE);
        repWFapp.setName(wfName);
        marshal(cluster, repWFapp, wfPath);
    }

    private WORKFLOWAPP createRetentionWorkflow(Cluster cluster) throws IOException, IvoryException {
        return getWorkflowTemplate(RETENTION_WF_TEMPLATE);
    }

    @Override
    protected Map<String, String> getEntityProperties() {
        Feed feed = getEntity();
        Map<String, String> props = new HashMap<String, String>();
        if (feed.getProperties() != null) {
            for (Property prop : feed.getProperties().getProperties())
                props.put(prop.getName(), prop.getValue());
        }
        return props;
    }
    
	private String getLocationURI(Cluster cluster, Feed feed, LocationType type) {
		String path = FeedHelper.getLocation(feed, type, cluster.getName())
				.getPath();

		if (!path.equals("/tmp")) {
			if (new Path(path).toUri().getScheme() == null){
				return  new Path(ClusterHelper.getStorageUrl(cluster), path)
						.toString();}
			else{
				return  path;
			}
		}
		return null;

}

}
