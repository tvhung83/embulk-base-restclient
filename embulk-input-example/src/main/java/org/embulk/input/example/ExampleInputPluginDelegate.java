package org.embulk.input.example;

import java.util.List;
import java.util.Locale;

import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.Schema;
import org.embulk.spi.type.Types;

import org.embulk.base.restclient.DefaultServiceDataSplitter;
import org.embulk.base.restclient.RestClientInputPluginDelegate;
import org.embulk.base.restclient.RestClientInputTaskBase;
import org.embulk.base.restclient.ServiceDataSplitter;
import org.embulk.base.restclient.jackson.JacksonServiceRecord;
import org.embulk.base.restclient.jackson.JacksonServiceResponseMapper;
import org.embulk.base.restclient.jackson.StringJsonParser;
import org.embulk.base.restclient.record.RecordImporter;

import org.embulk.util.retryhelper.jaxrs.JAXRSClientCreator;
import org.embulk.util.retryhelper.jaxrs.JAXRSRetryHelper;
import org.embulk.util.retryhelper.jaxrs.JAXRSSingleRequester;
import org.embulk.util.retryhelper.jaxrs.StringJAXRSResponseEntityReader;

import org.slf4j.Logger;

public class ExampleInputPluginDelegate
        implements RestClientInputPluginDelegate<ExampleInputPluginDelegate.PluginTask>
{
    public interface PluginTask
            extends RestClientInputTaskBase
    {
        @Config("maximum_retries")
        @ConfigDefault("7")
        public int getMaximumRetries();

        @Config("initial_retry_interval_millis")
        @ConfigDefault("1000")
        public int getInitialRetryIntervalMillis();

        @Config("maximum_retry_interval_millis")
        @ConfigDefault("60000")
        public int getMaximumRetryIntervalMillis();
    }

    private final StringJsonParser jsonParser = new StringJsonParser();

    @Override  // Overridden from |InputTaskValidatable|
    public void validateInputTask(PluginTask task)
    {
    }

    @Override  // Overridden from |ServiceResponseMapperBuildable|
    public JacksonServiceResponseMapper buildServiceResponseMapper(PluginTask task)
    {
        return JacksonServiceResponseMapper.builder()
            .add("id", Types.LONG)
            .build();
    }

    @Override  // Overridden from |ConfigDiffBuildable|
    public ConfigDiff buildConfigDiff(PluginTask task, Schema schema, int taskCount, List<TaskReport> taskReports)
    {
        // should implement for incremental data loading
        // consider |incremental| config here
        return Exec.newConfigDiff();
    }

    @Override  // Overridden from |ServiceDataSplitterBuildable|
    public ServiceDataSplitter<PluginTask> buildServiceDataSplitter(final PluginTask task)
    {
        return new DefaultServiceDataSplitter<PluginTask>();
    }

    @Override  // Overridden from |ServiceDataIngestable|
    public TaskReport ingestServiceData(final PluginTask task,
                                        RecordImporter recordImporter,
                                        int taskIndex,
                                        PageBuilder pageBuilder)
    {
        try (JAXRSRetryHelper retryHelper = new JAXRSRetryHelper(
                 task.getMaximumRetries(),
                 task.getInitialRetryIntervalMillis(),
                 task.getMaximumRetryIntervalMillis(),
                 new JAXRSClientCreator() {
                     @Override
                     public javax.ws.rs.client.Client create() {
                         return javax.ws.rs.client.ClientBuilder.newBuilder().build();
                     }
                 })) {
            String content = fetch(retryHelper);
            ArrayNode records = extractArrayField(content);

            for (JsonNode record : records) {
                if (!record.isObject()) {
                    logger.warn(String.format(Locale.ENGLISH, "A record must be Json object: %s", record.toString()));
                    continue;
                }
                try {
                    recordImporter.importRecord(new JacksonServiceRecord((ObjectNode) record), pageBuilder);
                }
                catch (Exception e) {
                    logger.warn(String.format(Locale.ENGLISH, "Skipped json: %s", record.toString()), e);
                }
            }
        }

        return Exec.newTaskReport();
    }

    private ArrayNode extractArrayField(String content)
    {
        ObjectNode jsonObject = jsonParser.parseJsonObject(content);
        JsonNode jn = jsonObject.get("ids");
        if (jn.isArray()) {
            return (ArrayNode) jn;
        }
        else {
            throw new DataException("Expected array node: " + jsonObject.toString());
        }
    }

    private String fetch(JAXRSRetryHelper retryHelper)
    {
        return retryHelper.requestWithRetry(
            new StringJAXRSResponseEntityReader(),
            new JAXRSSingleRequester() {
                @Override
                public Response requestOnce(javax.ws.rs.client.Client client)
                {
                    final String url = String.format(Locale.ENGLISH, "http://localhost:8080");
                    return client.target(url).request().get();
                }

                @Override
                public boolean isResponseStatusToRetry(javax.ws.rs.core.Response response)
                {
                    return response.getStatus() / 100 != 4;
                }
            });
    }

    private final Logger logger = Exec.getLogger(ExampleInputPluginDelegate.class);
}
