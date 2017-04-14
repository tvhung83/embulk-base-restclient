package org.embulk.base.restclient;

import java.util.List;

import com.google.common.base.Preconditions;

import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskReport;
import org.embulk.config.TaskSource;
import org.embulk.spi.Exec;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;

import org.embulk.base.restclient.record.ValueLocator;

/**
 * RestClientInputPluginBaseUnsafe is an "unsafe" base class of input plugin implementations.
 *
 * Do not use this {@code RestClientInputPluginBaseUnsafe} directly unless you really need this.
 * For almost all cases, inherit {@code RestClientInputPluginBase} instead.
 *
 * This {@code RestClientInputPluginBaseUnsafe} is here to allow overriding the plugin methods
 * such as {@code transaction} and {@code resume}. Overriding them can cause unexpected behavior.
 * Plugins using this {@code RestClientInputPluginBaseUnsafe} may have difficulties to catch up
 * with updates of this library.
 *
 * Use {@code RestClientInputPluginBase} instead of {@code RestClientInputPluginBaseUnsafe}.
 * Plugin methods of {@code RestClientInputPluginBase} are all {@code final} not to be overridden.
 */
public class RestClientInputPluginBaseUnsafe<T extends RestClientInputTaskBase>
        extends RestClientPluginBase<T>
        implements InputPlugin
{
    protected RestClientInputPluginBaseUnsafe(Class<T> taskClass,
                                              ConfigDiffBuildable<T> configDiffBuilder,
                                              InputTaskValidatable<T> inputTaskValidator,
                                              ServiceDataDispatcherBuildable<T> serviceDataDispatcherBuilder,
                                              ServiceDataIngestable<T> serviceDataIngester,
                                              ServiceResponseMapperBuildable<T> serviceResponseMapperBuilder)
    {
        this.taskClass = taskClass;
        this.configDiffBuilder = configDiffBuilder;
        this.inputTaskValidator = inputTaskValidator;
        this.serviceDataDispatcherBuilder = serviceDataDispatcherBuilder;
        this.serviceDataIngester = serviceDataIngester;
        this.serviceResponseMapperBuilder = serviceResponseMapperBuilder;
    }

    protected RestClientInputPluginBaseUnsafe(Class<T> taskClass,
                                              ConfigDiffBuildable<T> configDiffBuilder,
                                              InputTaskValidatable<T> inputTaskValidator,
                                              ServiceDataIngestable<T> serviceDataIngester,
                                              ServiceResponseMapperBuildable<T> serviceResponseMapperBuilder)
    {
        this(taskClass,
             configDiffBuilder,
             inputTaskValidator,
             new DefaultServiceDataDispatcherBuilder<T>(),
             serviceDataIngester,
             serviceResponseMapperBuilder);
    }

    protected RestClientInputPluginBaseUnsafe(Class<T> taskClass,
                                              RestClientInputPluginDelegate<T> delegate)
    {
        this(taskClass, delegate, delegate, delegate, delegate, delegate);
    }

    @Override
    public ConfigDiff transaction(ConfigSource config, InputPlugin.Control control)
    {
        final T task = loadConfig(config, this.taskClass);
        this.inputTaskValidator.validateInputTask(task);

        TaskSource dumpedTaskSource = task.dump();
        final int taskCount = this.serviceDataDispatcherBuilder
            .buildServiceDataDispatcher(task)
            .splitToTasks(dumpedTaskSource);

        final Schema schema = this.serviceResponseMapperBuilder.buildServiceResponseMapper(task).getEmbulkSchema();
        return resume(task.dump(), schema, taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource, Schema schema, int taskCount, InputPlugin.Control control)
    {
        final T task = taskSource.loadTask(this.taskClass);
        final List<TaskReport> taskReports = control.run(taskSource, schema, taskCount);
        return this.configDiffBuilder.buildConfigDiff(task, schema, taskCount, taskReports);
    }

    @Override
    public void cleanup(TaskSource taskSource, Schema schema, int taskCount, List<TaskReport> successTaskReports)
    {
    }

    @Override
    public TaskReport run(TaskSource taskSource, Schema schema, int taskIndex, PageOutput output)
    {
        final T task = taskSource.loadTask(this.taskClass);
        this.serviceDataDispatcherBuilder
            .buildServiceDataDispatcher(task)
            .hintPerTask(taskIndex, taskSource);

        final ServiceResponseMapper<? extends ValueLocator> serviceResponseMapper =
            this.serviceResponseMapperBuilder.buildServiceResponseMapper(task);

        try (PageBuilder pageBuilder = new PageBuilder(Exec.getBufferAllocator(), schema, output)) {
            // When failing around |PageBuidler| in |ingestServiceData|, |pageBuilder.finish()| should not be called.
            TaskReport taskReport = Preconditions.checkNotNull(
                this.serviceDataIngester.ingestServiceData(
                    task,
                    serviceResponseMapper.createRecordImporter(),
                    taskIndex,
                    pageBuilder));
            pageBuilder.finish();
            return taskReport;
        }
    }

    @Override
    public ConfigDiff guess(ConfigSource config)
    {
        return Exec.newConfigDiff();
    }

    private final Class<T> taskClass;
    private final ConfigDiffBuildable<T> configDiffBuilder;
    private final InputTaskValidatable<T> inputTaskValidator;
    private final ServiceDataDispatcherBuildable<T> serviceDataDispatcherBuilder;
    private final ServiceDataIngestable<T> serviceDataIngester;
    private final ServiceResponseMapperBuildable<T> serviceResponseMapperBuilder;
}
