package io.kestra.plugin.scripts.exec;

import com.google.common.annotations.Beta;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.models.tasks.*;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.RunnerType;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractExecScript extends Task implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    @Builder.Default
    @Schema(
        title = "The task runner to use — by default, Kestra runs all scripts in `DOCKER`.",
        description = "Only used if the `taskRunner` property is not set"
    )
    @PluginProperty
    @NotEmpty
    protected RunnerType runner = RunnerType.DOCKER;

    @Schema(
        title = "The task runner container image, only used if the task runner is container-based."
    )
    @PluginProperty
    protected String containerImage;

    @Schema(
        title = "The task runner to use.",
        description = "Task runners are provided by plugins, each have their own properties."
    )
    @PluginProperty
    @Valid
    @Beta
    protected TaskRunner taskRunner;

    @Schema(
        title = "A list of commands that will run before the `commands`, allowing to set up the environment e.g. `pip install -r requirements.txt`."
    )
    @PluginProperty(dynamic = true)
    protected List<String> beforeCommands;

    @Schema(
        title = "Additional environment variables for the current process."
    )
    @PluginProperty(
        additionalProperties = String.class,
        dynamic = true
    )
    protected Map<String, String> env;

    @Builder.Default
    @Schema(
        title = "Whether to set the task state to `WARNING` if any `stdErr` is emitted."
    )
    @PluginProperty
    @NotNull
    protected Boolean warningOnStdErr = true;

    @Builder.Default
    @Schema(
        title = "Which interpreter to use."
    )
    @PluginProperty
    @NotEmpty
    protected List<String> interpreter = List.of("/bin/sh", "-c");

    @Builder.Default
    @Schema(
        title = "List of process exit codes considered successful"
    )
    @PluginProperty
    @NotEmpty
    protected List<Integer> successExitCodes = List.of(0);

    private NamespaceFiles namespaceFiles;

    private Object inputFiles;

    private List<String> outputFiles;

    abstract public DockerOptions getDocker();

    /**
     * Allow to set Docker options defaults values.
     * To make it works, it is advised to set the 'docker' field like:
     *
     * <pre>{@code
     *     @Schema(
     *         title = "Docker options when using the `DOCKER` runner",
     *         defaultValue = "{image=python, pullPolicy=ALWAYS}"
     *     )
     *     @PluginProperty
     *     @Builder.Default
     *     protected DockerOptions docker = DockerOptions.builder().build();
     * }</pre>
     */
    protected DockerOptions injectDefaults(@NotNull DockerOptions original) {
        return original;
    }

    protected CommandsWrapper commands(RunContext runContext) throws IllegalVariableEvaluationException {
        return new CommandsWrapper(runContext)
            .withEnv(this.getEnv())
            .withWarningOnStdErr(this.getWarningOnStdErr())
            .withRunnerType(this.getRunner())
            .withContainerImage(this.containerImage)
            .withTaskRunner(this.taskRunner)
            .withDockerOptions(this.injectDefaults(getDocker()))
            .withSuccessExitCodes(this.successExitCodes)
            .withNamespaceFiles(this.namespaceFiles)
            .withInputFiles(this.inputFiles)
            .withOutputFiles(this.outputFiles);
    }
}
