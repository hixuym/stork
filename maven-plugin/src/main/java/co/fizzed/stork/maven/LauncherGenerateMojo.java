package co.fizzed.stork.maven;

import co.fizzed.stork.launcher.Configuration;
import co.fizzed.stork.launcher.Generator;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Maven plugin akin to the "stork-launcher-generate" command-line app but
 * directly accesses Java library (negating local install requirements).
 * 
 * @author joelauer
 */
@Mojo(name = "launcher-generate",
      defaultPhase = LifecyclePhase.COMPILE,
      threadSafe = true
    )
public class LauncherGenerateMojo extends AbstractMojo {
    
    /**
     * Directory to output generated launchers.
     *
     * @since 1.2.0
     */
    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/stage", required = true)
    protected File outputDirectory;
    
    /**
     * One or more input directories/files to generate.
     * 
     * @since 1.2.0
     */
    @Parameter(property = "inputFiles", defaultValue = "${basedir}/src/main/launchers")
    protected String[] inputFiles;
    
    @Parameter( defaultValue = "${project}", readonly = true )
    protected MavenProject project;
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (this.outputDirectory == null || this.outputDirectory.equals("")) {
            getLog().info("Skipping (outputDirectory is empty)");
            return;
        }
    
        try {
            Generator launcherGenerator = new Generator();
            List<Configuration> configs = launcherGenerator.createConfigsFromFileStrings(Arrays.asList(inputFiles));
            launcherGenerator.runConfigs(configs, outputDirectory);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
    
}
