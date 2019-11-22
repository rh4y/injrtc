package com.ivianuu.injekt.gradle

import com.google.auto.service.AutoService
import org.gradle.api.Project
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinGradleSubplugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

@AutoService(KotlinGradleSubplugin::class)
open class InjektGradleSubplugin : KotlinGradleSubplugin<AbstractCompile> {

    override fun isApplicable(project: Project, task: AbstractCompile) =
        project.plugins.hasPlugin(InjektGradlePlugin::class.java)

    override fun apply(
        project: Project,
        kotlinCompile: AbstractCompile,
        javaCompile: AbstractCompile?,
        variantData: Any?,
        androidProjectHandler: Any?,
        kotlinCompilation: KotlinCompilation<*>?
    ): List<SubpluginOption> {
        val sourceSetName = if (variantData != null) {
            // Lol
            variantData.javaClass.getMethod("getName").run {
                isAccessible = true
                invoke(variantData) as String
            }
        } else {
            if (kotlinCompilation == null) error("In non-Android projects, Kotlin compilation should not be null")
            kotlinCompilation.compilationName
        }

        val outputDir = File(project.buildDir, "generated/source/injekt/$sourceSetName/")
        kotlinCompilation?.allKotlinSourceSets?.forEach {
            it.kotlin.srcDir(outputDir)
            it.kotlin.exclude { it.file.startsWith(outputDir) }
        }

        // Lol #2
        variantData?.javaClass?.methods?.first { it.name =="addJavaSourceFoldersToModel" }?.apply {
            isAccessible = true
            invoke(variantData, outputDir)
        }

        return listOf(
            SubpluginOption(
                "outputDir", outputDir.absolutePath
            )
        )
    }

    override fun getCompilerPluginId(): String = "com.ivianuu.injekt"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = com.ivianuu.injekt.BuildConfig.GROUP_ID,
        artifactId = com.ivianuu.injekt.BuildConfig.ARTIFACT_ID,
        version = com.ivianuu.injekt.BuildConfig.VERSION
    )
}