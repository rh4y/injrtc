package com.ivianuu.injekt.compiler.generator

import com.ivianuu.injekt.Given
import com.ivianuu.injekt.compiler.SrcDir
import com.ivianuu.injekt.compiler.log
import com.ivianuu.injekt.given
import org.jetbrains.kotlin.name.FqName
import java.io.File

@Given(GenerationContext::class)
class FileManager {

    val newFiles = mutableListOf<File>()

    fun generateFile(
        packageFqName: FqName,
        fileName: String,
        code: String,
    ): File {
        val newFile = given<SrcDir>()
            .resolve(packageFqName.asString().replace(".", "/"))
            .also { it.mkdirs() }
            .resolve(fileName)
            .also { newFiles += it }

        log { "generated file $packageFqName.$fileName $code" }

        return newFile
            .also { it.createNewFile() }
            .also { it.writeText(code) }
    }

}
