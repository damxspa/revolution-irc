package io.mrarm.irc.build

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

public class SettingsBuilderPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.plugins.withId('com.android.application') {
            project.extensions.configure(ApplicationAndroidComponentsExtension.class) { androidComponents ->
                setupTasks(project, androidComponents)
            }
        }
        project.plugins.withId('com.android.library') {
            project.extensions.configure(LibraryAndroidComponentsExtension.class) { androidComponents ->
                setupTasks(project, androidComponents)
            }
        }
    }

    private void setupTasks(Project project, androidComponents) {
        def genTask = project.tasks.register("generateSettings", GenerateSettingsTask.class) {
            it.settingsFile.set(project.file("settings.yml"))
            it.outputDirectory.set(project.layout.buildDirectory.dir("generated/source/settings"))
        }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            variant.sources.java?.addGeneratedSourceDirectory(genTask, { it.outputDirectory })
        }
    }

}

abstract class GenerateSettingsTask extends DefaultTask {
    @InputFile
    @Optional
    abstract RegularFileProperty getSettingsFile()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        if (getSettingsFile().isPresent() && getSettingsFile().get().asFile.exists()) {
            SettingsBuilder.generateJavaFiles(getSettingsFile().get().asFile, getOutputDirectory().get().asFile)
        }
    }
}
