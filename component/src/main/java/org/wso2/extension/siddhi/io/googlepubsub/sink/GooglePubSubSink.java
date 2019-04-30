/*
 *  Copyright (c) 2019  WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.extension.siddhi.io.googlepubsub.sink;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.stream.ServiceDeploymentInfo;
import io.siddhi.core.stream.output.sink.Sink;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.core.util.transport.DynamicOptions;
import io.siddhi.core.util.transport.OptionHolder;
import io.siddhi.query.api.definition.StreamDefinition;
import org.apache.log4j.Logger;
import org.wso2.extension.siddhi.io.googlepubsub.util.GooglePubSubConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * {@code GooglePubSubSink } Handle the GooglePubSub publishing tasks.
 */
@Extension(
        name = "googlepubsub",
        namespace = "sink",
        description = "GooglePubSub Sink publishes messages to a topic in  GooglePubSub server. If the required topic "
                + "doesn't exist, GooglePubSub Sink creates a topic and publish messages to that topic.",
        parameters = {
                @Parameter(
                        name = GooglePubSubConstants.GOOGLE_PUB_SUB_SERVER_PROJECT_ID,
                        description = "The unique ID of the GCP console project within which the topic is created.",
                        type = DataType.STRING
                ),
                @Parameter(
                        name = GooglePubSubConstants.TOPIC_ID,
                        description = "The topic ID of the topic to which the messages that are processed by Siddhi "
                                + "are published. ",
                        type = DataType.STRING
                ),
                @Parameter(
                        name = GooglePubSubConstants.CREDENTIAL_PATH,
                        description = "The file path of the service account credentials.",
                        type = DataType.STRING
                ),
        },
        examples = {
                @Example(description = "This example shows how to publish messages to a topic in the GooglePubSub with "
                        + "all supportive configurations.Accordingly, the messages are published to a topic having the "
                        + "topic.id named topicA in the project with a project.id 'sp-path-1547649404768'. If the "
                        + "required topic already exists in the particular project the messages are directly published "
                        + "to that topic.If the required topic does not exist, a new topic is created according to the "
                        + "provided topic.id and project.id. Then the messages are published to the particular topic.",

                        syntax = "@sink(type = 'googlepubsub', @map(type= 'text'),\n"
                                + "project.id = 'sp-path-1547649404768', \n"
                                + "credential.path = 'src/test/resources/security/sp.json',\n"
                                + "topic.id ='topicA',\n "
                                + ")\n" +
                                "define stream inputStream(message string);"),
        }
)
/*
 * GooglePubSub publishing tasks.
 */
public class GooglePubSubSink extends Sink<State> {

    private static final Logger log = Logger.getLogger(GooglePubSubSink.class);
    private String streamID;
    private String siddhiAppName;
    private GoogleCredentials credentials;
    private TopicAdminClient topicAdminClient;
    private String projectId;
    private ProjectTopicName topic;
    private Publisher publisher;

    @Override
    public Class[] getSupportedInputEventClasses() {
        return new Class[]{String.class};
    }

    @Override
    protected ServiceDeploymentInfo exposeServiceDeploymentInfo() {
        return null;
    }

    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[]{GooglePubSubConstants.TOPIC_ID, GooglePubSubConstants.GOOGLE_PUB_SUB_SERVER_PROJECT_ID,
                GooglePubSubConstants.CREDENTIAL_PATH};
    }

    @Override
    protected StateFactory<State> init(StreamDefinition streamDefinition,
                                       OptionHolder optionHolder,
                                       ConfigReader configReader,
                                       SiddhiAppContext siddhiAppContext) {
        this.siddhiAppName = siddhiAppContext.getName();
        this.streamID = streamDefinition.getId();
        String topicId = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.TOPIC_ID);
        this.projectId = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.GOOGLE_PUB_SUB_SERVER_PROJECT_ID);
        String credentialPath = optionHolder.validateAndGetStaticValue(GooglePubSubConstants.CREDENTIAL_PATH);
        this.topic = ProjectTopicName.of(projectId, topicId);
        File credentialsPath = new File(credentialPath);
        try {
            FileInputStream serviceAccountStream = new FileInputStream(credentialsPath);
            credentials = ServiceAccountCredentials.fromStream(serviceAccountStream);
        } catch (IOException e) {
            throw new SiddhiAppCreationException("The file that contains your service account credentials is not "
                    + "found or you are not permitted to make authenticated calls. Check the credential.path '"
                    + credentialPath + "' defined in stream " + siddhiAppName + " : " + streamID + ".", e);
        }
        return null;
    }

    @Override
    public void publish(Object o, DynamicOptions dynamicOptions, State state) throws ConnectionUnavailableException {
        String message = (String) o;
        ByteString data = ByteString.copyFromUtf8(message);
        PubsubMessage pubsubMessage = PubsubMessage.newBuilder().setData(data).build();
        publisher.publish(pubsubMessage);
    }

    @Override
    public void connect() throws ConnectionUnavailableException {
        createTopic();
        try {
            publisher = Publisher.newBuilder(topic).setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();
        } catch (IOException e) {
            throw new ConnectionUnavailableException("Could not create a publisher bound to the topic : " + topic, e);
        }
    }

    @Override
    public void disconnect() {
        if (publisher != null) {
            try {
                publisher.shutdown();
                publisher.awaitTermination(1, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.error(String.format("Error in shutting down the publisher %s. Message %s.", publisher,
                        e.getMessage()));
            }
        }
    }

    @Override
    public void destroy() {
    }

    /**
     * Creating a topic.
     */
    private void createTopic() {
        try {
            TopicAdminSettings topicAdminSettings = TopicAdminSettings.newBuilder().setCredentialsProvider
                    (FixedCredentialsProvider.create(credentials)).build();
            topicAdminClient = TopicAdminClient.create(topicAdminSettings);
            topicAdminClient.createTopic(topic);
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() == StatusCode.Code.ALREADY_EXISTS) {
                log.info("You have the topic '" + topic + "' in google pub sub server.");
            } else {
                String errorMessage = "An error is caused due to a resource "
                        + e.getStatusCode().getCode() + " in Google Pub Sub server." + " Check whether you have "
                        + "provided a proper project.id for '" + projectId + "' defined in stream " + siddhiAppName
                        + ": " + streamID + " and make sure you have enough access to use all resources in API.";
                log.error(errorMessage);
                throw new SiddhiAppRuntimeException(errorMessage, e);
            }
        } catch (IOException e) {
            throw new SiddhiAppRuntimeException("Could not create the topic " + topic + "in the google pub sub "
                    + "server, under the project.id '" + projectId + "' defined in stream " + siddhiAppName + " : "
                    + streamID + ".", e);
        } finally {
            if (topicAdminClient != null) {
                topicAdminClient.shutdown();
            }
        }
    }
}
