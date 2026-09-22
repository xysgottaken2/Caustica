package dev.comfyfluffy.caustica.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyMinecraftAbi extends DefaultTask {
    @Classpath abstract ConfigurableFileCollection getMinecraftClasspath()
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) abstract RegularFileProperty getContractFile()
    @OutputFile abstract RegularFileProperty getReportFile()

    @TaskAction
    void verify() {
        File report = reportFile.get().asFile
        report.parentFile.mkdirs()
        // Do not leave a stale success report after a malformed contract or unreadable classfile.
        report.setText('Minecraft ABI verification incomplete\n', 'UTF-8')
        def requirements = AbiContract.parse(contractFile.get().asFile.getText('UTF-8'))
        List<String> errors = AbiContract.verify(requirements, minecraftClasspath.files)
        report.setText(errors.empty
                ? "PASS: ${requirements.size()} member signatures found; terrain extraction call sites/order checked. This is NOT a GPU or Mixin execution test.\n"
                : "FAIL\n${errors.join('\n')}\n", 'UTF-8')
        if (!errors.empty) {
            throw new GradleException("Minecraft 26.3 ABI mismatch; do not port 26.2 hooks blindly.\n${errors.join('\n')}")
        }
        logger.lifecycle("Minecraft ABI: {} member signatures verified (no GPU execution)", requirements.size())
    }
}
