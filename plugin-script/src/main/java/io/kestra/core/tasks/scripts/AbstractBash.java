package io.kestra.core.tasks.scripts;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.tasks.PluginUtilsService;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.RunnerType;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.kestra.plugin.scripts.exec.scripts.services.ScriptService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import static io.kestra.core.utils.Rethrow.throwBiConsumer;
import static io.kestra.core.utils.Rethrow.throwConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Deprecated
abstract public class AbstractBash extends Task {
    @Builder.Default
    @Schema(
        title = "The script runner — by default, Kestra runs all scripts in `DOCKER`."
    )
    @PluginProperty
    @NotNull
    @NotEmpty
    protected RunnerType runner = RunnerType.PROCESS;

    @Schema(
        title = "Docker options when using the `DOCKER` runner."
    )
    @PluginProperty
    protected DockerOptions dockerOptions;

    @Builder.Default
    @Schema(
        title = "Interpreter to use when launching the process."
    )
    @PluginProperty
    @NotNull
    @NotEmpty
    protected String interpreter = "/bin/sh";

    @Builder.Default
    @Schema(
        title = "Interpreter args used"
    )
    @PluginProperty
    protected String[] interpreterArgs = {"-c"};

    @Builder.Default
    @Schema(
        title = "Exit if any non true value is returned",
        description = "This tells bash that it should exit the script if any statement returns a non-true return value. \n" +
            "Setting this to `true` helps catch cases where a command fails and the script continues to run anyway."
    )
    @PluginProperty
    @NotNull
    protected Boolean exitOnFailed = true;

    @Schema(
        title = "The list of files that will be uploaded to internal storage, ",
        description ="use `outputFiles` property instead",
        deprecated = true
    )
    @PluginProperty(dynamic = true)
    @Deprecated
    protected List<String> files;

    @Schema(
        title = "Deprecated Output file",
        description = "use `outputFiles`",
        deprecated = true
    )
    @PluginProperty
    @Deprecated
    protected List<String> outputsFiles;

    @Schema(
        title = "Output file list that will be uploaded to internal storage",
        description = "List of key that will generate temporary files.\n" +
            "On the command, just can use with special variable named `outputFiles.key`.\n" +
            "If you add a files with `[\"first\"]`, you can use the special vars `echo 1 >> {[ outputFiles.first }}`" +
            " and you used on others tasks using `{{ outputs.taskId.outputFiles.first }}`"
    )
    @PluginProperty
    protected List<String> outputFiles;

    @Schema(
        title = "List of output directories that will be uploaded to internal storage",
        description = "List of keys that will generate temporary directories.\n" +
            "On the command, just can use with special variable named `outputDirs.key`.\n" +
            "If you add a files with `[\"myDir\"]`, you can use the special vars `echo 1 >> {[ outputDirs.myDir }}/file1.txt` " +
            "and `echo 2 >> {[ outputDirs.myDir }}/file2.txt` and both files will be uploaded to internal storage." +
            " Then you can used them on others tasks using `{{ outputs.taskId.outputFiles['myDir/file1.txt'] }}`"
    )
    @PluginProperty
    protected List<String> outputDirs;

    @Schema(
        title = "Input files are extra files that will be available in the script's working directory.",
        description = "Define the files **as a map** of a file name being the key, and the value being the file's content.\n" +
            "Alternatively, configure the files **as a JSON string** with the same key/value structure as the map.\n" +
            "In both cases, you can either specify the file's content inline, or reference a file from Kestra's internal storage by its URI, e.g. a file from an input, output of a previous task, or a [namespace file](https://kestra.io/docs/developer-guide/namespace-files)."
    )
    @PluginProperty(
        additionalProperties = String.class,
        dynamic = true
    )
    protected Object inputFiles;

    @Schema(
        title = "One or more additional environment variable(s) to add to the task run."
    )
    @PluginProperty(
        additionalProperties = String.class,
        dynamic = true
    )
    protected Map<String, String> env;

    @Builder.Default
    @Schema(
        title = "Whether to set the execution state in `WARNING` if any `stdErr` is sent."
    )
    @PluginProperty
    @NotNull
    protected Boolean warningOnStdErr = true;

    @Getter(AccessLevel.NONE)
    protected transient Path workingDirectory;

    @Builder.Default
    @Getter(AccessLevel.NONE)
    protected transient Map<String, Object> additionalVars = new HashMap<>();

    protected List<String> finalInterpreter() {
        List<String> interpreters = new ArrayList<>();

        interpreters.add(this.interpreter);
        interpreters.addAll(Arrays.asList(this.interpreterArgs));

        return interpreters;
    }

    protected Map<String, String> finalInputFiles(RunContext runContext) throws IOException, IllegalVariableEvaluationException {
        return this.inputFiles != null ? new HashMap<>(PluginUtilsService.transformInputFiles(runContext, this.inputFiles)) : new HashMap<>();
    }

    protected Map<String, String> finalEnv() throws IOException {
        return this.env != null ? new HashMap<>(this.env) : new HashMap<>();
    }

    protected io.kestra.core.tasks.scripts.ScriptOutput run(RunContext runContext, Supplier<String> commandsSupplier) throws Exception {
        List<String> allOutputs = new ArrayList<>();

        if (this.workingDirectory == null) {
            this.workingDirectory = runContext.tempDir();
        }

        additionalVars.put("workingDir", workingDirectory.toAbsolutePath().toString());

        // deprecated properties
        if (this.outputFiles != null && this.outputFiles.size() > 0) {
            allOutputs.addAll(this.outputFiles);
        }

        if (this.outputsFiles != null && this.outputsFiles.size() > 0) {
            allOutputs.addAll(this.outputsFiles);
        }

        if (files != null && files.size() > 0) {
            allOutputs.addAll(files);
        }

        Map<String, String> outputFiles = PluginUtilsService.createOutputFiles(
            workingDirectory,
            allOutputs,
            additionalVars
        );

        PluginUtilsService.createInputFiles(
            runContext,
            workingDirectory,
            this.finalInputFiles(runContext),
            additionalVars
        );

        List<String> allOutputDirs = new ArrayList<>();

        if (this.outputDirs != null && this.outputDirs.size() > 0) {
            allOutputDirs.addAll(this.outputDirs);
        }

        Map<String, String> outputDirs = PluginUtilsService.createOutputFiles(
            workingDirectory,
            allOutputDirs,
            additionalVars,
            true
        );

        List<String> commandsArgs = ScriptService.scriptCommands(
            this.finalInterpreter(),
            List.of(),
            commandsSupplier.get()
        );

        ScriptOutput run = new CommandsWrapper(runContext)
            .withEnv(this.finalEnv())
            .withWarningOnStdErr(this.warningOnStdErr)
            .withRunnerType(this.runner)
            .withDockerOptions(this.getDockerOptions())
            .withCommands(commandsArgs)
            .addAdditionalVars(this.additionalVars)
            .run();

        // upload output files
        Map<String, URI> uploaded = new HashMap<>();

        // outputFiles
        outputFiles
            .forEach(throwBiConsumer((k, v) -> uploaded.put(k, runContext.putTempFile(new File(runContext.render(v, additionalVars))))));

        // outputDirs
        outputDirs
            .forEach(throwBiConsumer((k, v) -> {
                try (Stream<Path> walk = Files.walk(new File(runContext.render(v, additionalVars)).toPath())) {
                    walk
                        .filter(Files::isRegularFile)
                        .forEach(throwConsumer(path -> {
                            String filename = Path.of(
                                k,
                                Path.of(runContext.render(v, additionalVars)).relativize(path).toString()
                            ).toString();

                            uploaded.put(
                                filename,
                                runContext.putTempFile(path.toFile(), filename)
                            );
                        }));
                }
            }));

        // output
        return io.kestra.core.tasks.scripts.ScriptOutput.builder()
            .exitCode(run.getExitCode())
            .stdOutLineCount(run.getStdOutLineCount())
            .stdErrLineCount(run.getStdErrLineCount())
            .warningOnStdErr(this.warningOnStdErr)
            .vars(run.getVars())
            .files(uploaded)
            .outputFiles(uploaded)
            .build();
    }
}
